package vaddempudi.gnanesh.syntaxcli.mediareader;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import vaddempudi.gnanesh.syntaxcli.filemanager.FileLog;

public final class DocxReader {

    private static final int MAX_OUTPUT_CHARS = 2_000_000;

    public static final char BOLD_ON    = '\u0001';
    public static final char BOLD_OFF   = '\u0002';
    public static final char ITAL_ON    = '\u0003';
    public static final char ITAL_OFF   = '\u0004';
    public static final char UNDER_ON   = '\u0005';
    public static final char UNDER_OFF  = '\u0006';
    public static final char STRIKE_ON  = '\u0007';
    public static final char STRIKE_OFF = '\u0008';

    public static final char IMAGE_MARKER = '\uE000';
    public static final char PAGE_BREAK   = '\uE001';

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
        StringBuilder out = new StringBuilder(Math.min(16384, MAX_OUTPUT_CHARS));

        if (imageOutputDir != null && !imageOutputDir.exists()) imageOutputDir.mkdirs();

        String documentXml = null;
        List<String> relIds = new ArrayList<>();
        List<String> relTargets = new ArrayList<>();

        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(docx))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if ("word/document.xml".equals(name)) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 20);
                    byte[] buf = new byte[32768];
                    int n;
                    while ((n = zis.read(buf)) > 0) bos.write(buf, 0, n);
                    documentXml = bos.toString("UTF-8");
                    bos.close();
                } else if ("word/_rels/document.xml.rels".equals(name)) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 16);
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = zis.read(buf)) > 0) bos.write(buf, 0, n);
                    parseRels(bos.toString("UTF-8"), relIds, relTargets);
                    bos.close();
                } else if (imageOutputDir != null && name.startsWith("word/media/")) {
                    String fname = name.substring("word/media/".length());
                    File target = new File(imageOutputDir, fname);
                    File parent = target.getParentFile();
                    if (parent != null) parent.mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(target)) {
                        byte[] buf = new byte[32768];
                        int n;
                        while ((n = zis.read(buf)) > 0) fos.write(buf, 0, n);
                    }
                }
            }
        }

        if (documentXml == null) throw new Exception("Not a valid DOCX: missing word/document.xml");
        FileLog.e("DocxReader: document.xml len=" + documentXml.length()
                + " rels=" + relIds.size());

        parseDocumentXml(documentXml, out, r.images, imageOutputDir, relIds, relTargets);

        FileLog.e("DocxReader: parsed out.len=" + out.length()
                + " images=" + r.images.size());

        r.markedText = out.toString();
        return r;
    }

    private static void parseRels(String xml, List<String> ids, List<String> targets)
            throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        p.setInput(new StringReader(xml));
        int ev = p.getEventType();
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && "Relationship".equals(p.getName())) {
                String id = null, target = null, type = null;
                for (int i = 0; i < p.getAttributeCount(); i++) {
                    String an = p.getAttributeName(i);
                    if ("Id".equals(an)) id = p.getAttributeValue(i);
                    else if ("Target".equals(an)) target = p.getAttributeValue(i);
                    else if ("Type".equals(an)) type = p.getAttributeValue(i);
                }
                if (id != null && target != null && type != null) {
                    ids.add(id);
                    targets.add(target);
                }
            }
            ev = p.next();
        }
    }

    private static File resolveImage(String relId, List<String> relIds,
                                     List<String> relTargets, File imageOutputDir) {
        if (relId == null || imageOutputDir == null) return null;
        int idx = relIds.indexOf(relId);
        if (idx < 0) return null;
        String target = relTargets.get(idx);
        if (target.startsWith("/")) target = target.substring(1);
        String clean = target;
        if (clean.startsWith("../")) clean = clean.substring(3);
        if (clean.startsWith("word/")) clean = clean.substring(5);
        if (clean.startsWith("media/")) clean = clean.substring(6);
        File f = new File(imageOutputDir, clean);
        return f.exists() ? f : null;
    }

    private static void parseDocumentXml(String xml, StringBuilder out,
                                         List<ImageRef> images, File imageOutputDir,
                                         List<String> relIds, List<String> relTargets)
            throws Exception {

        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        parser.setInput(new StringReader(xml));

        boolean bold = false, italic = false, underline = false, strike = false;
        boolean inText = false, inRun = false;
        int inTable = 0, inRow = 0, inCell = 0;

        // Drawing state — emit marker only when drawing/pict END
        int drawingDepth = 0;
        String drawRelId = null;
        String drawAlt = null;

        // mc:AlternateContent — process Choice, skip Fallback
        int altContentDepth = 0;
        boolean inFallback = false;

        int event = parser.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (out.length() >= MAX_OUTPUT_CHARS) break;
            String name = parser.getName();

            switch (event) {
                case XmlPullParser.START_TAG: {
                    if (name == null) break;

                    if ("AlternateContent".equals(name)) { altContentDepth++; break; }
                    if ("Fallback".equals(name) && altContentDepth > 0) {
                        inFallback = true; break;
                    }
                    if (inFallback) break;

                    switch (name) {
                        case "tbl":
                            inTable++;
                            out.append('\n');
                            break;
                        case "tr":
                            if (inTable > 0) {
                                inRow++;
                                out.append('\n');
                            }
                            break;
                        case "tc":
                            if (inRow > 0) {
                                inCell++;
                                if (inCell > 1) out.append('\t');
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

                        case "b": if (inRun) bold = true; break;
                        case "i": if (inRun) italic = true; break;
                        case "u": if (inRun) underline = true; break;
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
                            if (inRun) out.append('-');
                            break;
                        case "softHyphen":
                            if (inRun) out.append('\u00AD');
                            break;

                        case "lastRenderedPageBreak":
                            out.append(PAGE_BREAK);
                            break;

                        case "drawing":
                        case "pict":
                            drawingDepth++;
                            break;

                        case "docPr":
                        case "cNvPr": {
                            String alt = getAttr(parser, "descr");
                            if (alt == null || alt.isEmpty()) alt = getAttr(parser, "title");
                            if (alt == null || alt.isEmpty()) alt = getAttr(parser, "name");
                            if (alt != null) drawAlt = alt;
                            break;
                        }

                        case "blip":
                        case "imagedata": {
                            String rid = getAttr(parser, "embed");
                            if (rid == null) rid = getAttr(parser, "id");
                            if (rid == null) rid = getAttr(parser, "r:embed");
                            if (rid == null) rid = getAttr(parser, "r:id");
                            if (rid != null) drawRelId = rid;
                            break;
                        }
                    }
                    break;
                }

                case XmlPullParser.TEXT:
                    if (inText && !inFallback) {
                        String text = parser.getText();
                        if (text != null) out.append(text);
                    }
                    break;

                case XmlPullParser.END_TAG: {
                    if (name == null) break;

                    if ("Fallback".equals(name) && inFallback) {
                        inFallback = false;
                        break;
                    }
                    if ("AlternateContent".equals(name)) {
                        if (altContentDepth > 0) altContentDepth--;
                        break;
                    }
                    if (inFallback) break;

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
                        case "b": if (inRun) bold = false; break;
                        case "i": if (inRun) italic = false; break;
                        case "u": if (inRun) underline = false; break;
                        case "strike":
                        case "dstrike":
                            if (inRun) strike = false;
                            break;
                        case "r":
                            inRun = false;
                            break;
                        case "tc":
                            if (inCell > 0) inCell--;
                            break;
                        case "tr":
                            if (inRow > 0) inRow--;
                            break;
                        case "tbl":
                            if (inTable > 0) inTable--;
                            out.append('\n');
                            break;
                        case "p":
                            if (inCell == 0) out.append('\n');
                            break;

                        case "drawing":
                        case "pict": {
                            if (drawingDepth > 0) drawingDepth--;
                            if (drawingDepth == 0) {
                                File f = resolveImage(drawRelId, relIds, relTargets, imageOutputDir);
                                if (f != null) {
                                    images.add(new ImageRef(drawRelId, f.getName(), f));
                                    out.append(IMAGE_MARKER);
                                } else if (drawRelId != null) {
                                    out.append("[Image");
                                    if (drawAlt != null && !drawAlt.isEmpty())
                                        out.append(": ").append(drawAlt);
                                    out.append(']');
                                }
                                drawRelId = null;
                                drawAlt = null;
                            }
                            break;
                        }
                    }
                    break;
                }
            }
            event = parser.next();
        }
    }

    private static String getAttr(XmlPullParser parser, String name) {
        int count = parser.getAttributeCount();
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
            if (c == BOLD_ON || c == BOLD_OFF || c == ITAL_ON || c == ITAL_OFF
                    || c == UNDER_ON || c == UNDER_OFF || c == STRIKE_ON || c == STRIKE_OFF
                    || c == IMAGE_MARKER || c == PAGE_BREAK) continue;
            sb.append(c);
        }
        return sb.toString();
    }
}