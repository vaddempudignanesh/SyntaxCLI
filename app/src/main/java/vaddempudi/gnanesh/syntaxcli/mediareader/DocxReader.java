package vaddempudi.gnanesh.syntaxcli.mediareader;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class DocxReader {

    private static final int MAX_OUTPUT_CHARS = 500_000;

    private static final char BOLD_ON      = '\u0001';
    private static final char BOLD_OFF     = '\u0002';
    private static final char ITAL_ON      = '\u0003';
    private static final char ITAL_OFF     = '\u0004';
    private static final char UNDER_ON     = '\u0005';
    private static final char UNDER_OFF    = '\u0006';
    private static final char STRIKE_ON    = '\u0007';
    private static final char STRIKE_OFF   = '\u0008';

    public static final char IMAGE_MARKER  = '\uE000';

    public static final class Result {
        public String markedText;
        public List<ImageRef> images;
    }

    public static final class ImageRef {
        public final String relId;
        public final String fileName;
        public final File extractedFile;
        ImageRef(String relId, String fileName, File extractedFile) {
            this.relId = relId;
            this.fileName = fileName;
            this.extractedFile = extractedFile;
        }
    }

    private DocxReader() { }

    public static Result readDocx(File docx, File imageOutputDir) throws Exception {
        Result r = new Result();
        r.images = new ArrayList<>();
        StringBuilder out = new StringBuilder(Math.min(8192, MAX_OUTPUT_CHARS));

        if (imageOutputDir != null && !imageOutputDir.exists()) {
            imageOutputDir.mkdirs();
        }

        String documentXml = null;
        List<String> relIds = new ArrayList<>();
        List<String> relTargets = new ArrayList<>();
        List<String> relTypes = new ArrayList<>();

        ZipInputStream zis = null;
        try {
            zis = new ZipInputStream(new FileInputStream(docx));
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();

                if ("word/document.xml".equals(name)) {
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = zis.read(buf)) > 0) bos.write(buf, 0, n);
                    documentXml = bos.toString("UTF-8");
                    bos.close();
                } else if ("word/_rels/document.xml.rels".equals(name)) {
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = zis.read(buf)) > 0) bos.write(buf, 0, n);
                    parseRels(bos.toString("UTF-8"), relIds, relTargets, relTypes);
                    bos.close();
                } else if (name.startsWith("word/media/") && imageOutputDir != null) {
                    File target = new File(imageOutputDir,
                            name.substring("word/media/".length()));
                    FileOutputStream fos = new FileOutputStream(target);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = zis.read(buf)) > 0) fos.write(buf, 0, n);
                    fos.close();
                }
            }
        } finally {
            if (zis != null) {
                try { zis.close(); } catch (Exception ignored) {}
            }
        }

        if (documentXml == null) {
            throw new Exception("Not a valid DOCX: missing word/document.xml");
        }

        parseDocumentXml(documentXml, out, r.images, imageOutputDir,
                relIds, relTargets, relTypes);

        r.markedText = out.toString();
        return r;
    }

    private static void parseRels(String xml,
                                  List<String> ids,
                                  List<String> targets,
                                  List<String> types) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        p.setInput(new java.io.StringReader(xml));
        int ev = p.getEventType();
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && "Relationship".equals(p.getName())) {
                String id = null, target = null, type = null;
                int ac = p.getAttributeCount();
                for (int i = 0; i < ac; i++) {
                    String an = p.getAttributeName(i);
                    if ("Id".equals(an)) id = p.getAttributeValue(i);
                    else if ("Target".equals(an)) target = p.getAttributeValue(i);
                    else if ("Type".equals(an)) type = p.getAttributeValue(i);
                }
                if (id != null && target != null && type != null) {
                    ids.add(id);
                    targets.add(target);
                    types.add(type);
                }
            }
            ev = p.next();
        }
    }

    private static void parseDocumentXml(String xml,
                                         StringBuilder out,
                                         List<ImageRef> images,
                                         File imageOutputDir,
                                         List<String> relIds,
                                         List<String> relTargets,
                                         List<String> relTypes) throws Exception {

        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        parser.setInput(new java.io.StringReader(xml));

        boolean bold = false, italic = false, underline = false, strike = false;
        boolean inText = false, inRun = false, inTable = false, inRow = false, inCell = false;

        int cellCount = 0;
        int skipDepth = 0;
        int tableDepth = 0;

        String pendingRelId = null;
        String pendingAltText = null;

        int event = parser.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (out.length() >= MAX_OUTPUT_CHARS) break;
            String name = parser.getName();

            switch (event) {
                case XmlPullParser.START_TAG: {
                    if (name == null) break;
                    if (skipDepth > 0) { skipDepth++; break; }

                    switch (name) {
                        case "delText":
                        case "del":
                            skipDepth = 1;
                            break;

                        case "tbl":
                            inTable = true;
                            tableDepth++;
                            out.append('\n');
                            break;

                        case "tr":
                            if (inTable) {
                                inRow = true;
                                cellCount = 0;
                            }
                            break;

                        case "tc":
                            if (inRow) {
                                inCell = true;
                                if (cellCount > 0) out.append('\t');
                                cellCount++;
                            }
                            break;

                        case "r":
                            inRun = true;
                            break;

                        case "t":
                            if (inRun) {
                                inText = true;
                                if (bold)      out.append(BOLD_ON);
                                if (italic)    out.append(ITAL_ON);
                                if (underline) out.append(UNDER_ON);
                                if (strike)    out.append(STRIKE_ON);
                            }
                            break;

                        case "b":   if (inRun) bold = true; break;
                        case "i":   if (inRun) italic = true; break;
                        case "u":   if (inRun) underline = true; break;
                        case "strike":
                        case "dstrike":
                            if (inRun) strike = true;
                            break;

                        case "tab":
                        case "ptab":
                            if (inRun) out.append('\t');
                            break;

                        case "br":
                        case "cr":
                            if (inRun) out.append('\n');
                            break;

                        case "noBreakHyphen":
                        case "softHyphen":
                            if (inRun) out.append('-');
                            break;

                        case "docPr":
                        case "cNvPr": {
                            String alt = getAttr(parser, "descr");
                            if (alt == null || alt.isEmpty()) alt = getAttr(parser, "title");
                            if (alt == null || alt.isEmpty()) alt = getAttr(parser, "name");
                            pendingAltText = alt;
                            break;
                        }

                        case "blip":
                        case "imagedata": {
                            String rid = getAttr(parser, "embed");
                            if (rid == null) rid = getAttr(parser, "id");
                            if (rid == null) rid = getAttr(parser, "r:id");
                            if (rid != null) pendingRelId = rid;
                            break;
                        }

                        case "drawing":
                        case "pict": {
                            File resolvedFile = null;
                            String resolvedName = null;
                            if (pendingRelId != null && imageOutputDir != null) {
                                int idx = relIds.indexOf(pendingRelId);
                                if (idx >= 0) {
                                    String target = relTargets.get(idx);
                                    String cleanTarget = target.startsWith("../")
                                            ? target.substring(3) : target;
                                    if (cleanTarget.startsWith("media/")) {
                                        String fname = cleanTarget.substring(6);
                                        File f = new File(imageOutputDir, fname);
                                        if (f.exists()) {
                                            resolvedFile = f;
                                            resolvedName = fname;
                                        }
                                    }
                                }
                            }

                            if (resolvedFile != null) {
                                images.add(new ImageRef(pendingRelId,
                                        resolvedName, resolvedFile));
                                out.append(IMAGE_MARKER);
                                out.append('\u000B');
                            } else {
                                out.append("[Image");
                                if (pendingAltText != null && !pendingAltText.isEmpty()) {
                                    out.append(": ").append(pendingAltText);
                                }
                                out.append(']');
                            }
                            pendingRelId = null;
                            pendingAltText = null;
                            break;
                        }

                        case "AlternateContent":
                        case "Fallback":
                        case "Choice":
                            break;
                    }
                    break;
                }

                case XmlPullParser.TEXT:
                    if (inText && skipDepth == 0) {
                        String text = parser.getText();
                        if (text != null) out.append(text);
                    }
                    break;

                case XmlPullParser.END_TAG: {
                    if (skipDepth > 0) {
                        skipDepth--;
                        if (skipDepth == 0) break;
                    }
                    if (name == null) break;

                    switch (name) {
                        case "t":
                            if (inText) {
                                if (strike)    out.append(STRIKE_OFF);
                                if (underline) out.append(UNDER_OFF);
                                if (italic)    out.append(ITAL_OFF);
                                if (bold)      out.append(BOLD_OFF);
                                inText = false;
                            }
                            break;
                        case "b":  if (inRun) bold = false; break;
                        case "i":  if (inRun) italic = false; break;
                        case "u":  if (inRun) underline = false; break;
                        case "strike":
                        case "dstrike":
                            if (inRun) strike = false;
                            break;
                        case "r":
                            inRun = false;
                            break;
                        case "tc":
                            inCell = false;
                            break;
                        case "tr":
                            inRow = false;
                            out.append('\n');
                            break;
                        case "tbl":
                            tableDepth--;
                            if (tableDepth <= 0) {
                                inTable = false;
                                tableDepth = 0;
                                out.append('\n');
                            }
                            break;
                        case "p":
                            if (!inCell) out.append('\n').append('\n');
                            break;
                    }
                    break;
                }
            }
            event = parser.next();
        }
    }

    private static String getAttr(XmlPullParser parser, String name) {
        int count = parser.getAttributeCount();
        if (count <= 0) return null;
        for (int i = 0; i < count; i++) {
            String an = parser.getAttributeName(i);
            if (name.equals(an)) return parser.getAttributeValue(i);
        }
        if (name.startsWith("r:")) {
            String local = name.substring(2);
            for (int i = 0; i < count; i++) {
                if (local.equals(parser.getAttributeName(i)))
                    return parser.getAttributeValue(i);
            }
        }
        return null;
    }

    public static String stripMarkers(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == BOLD_ON || c == BOLD_OFF
                    || c == ITAL_ON || c == ITAL_OFF
                    || c == UNDER_ON || c == UNDER_OFF
                    || c == STRIKE_ON || c == STRIKE_OFF
                    || c == IMAGE_MARKER) continue;
            sb.append(c);
        }
        return sb.toString();
    }
}