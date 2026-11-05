package vaddempudi.gnanesh.syntaxcli.ime;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ClipboardPanelView extends LinearLayout {

    private static final int PREVIEW_MAX_CHARS = 300;
    private static final int PANEL_HEIGHT_DP   = 352;
    private static final int ITEM_BORDER_COLOR = 0xFF1F6B1F;
    private static final int ITEM_BG_COLOR     = 0xFF0A0A0A;
    private static final int TIME_COLOR        = 0xFF00AA00;

    private static final SimpleDateFormat SAME_DAY_FMT =
            new SimpleDateFormat("HH:mm", Locale.US);
    private static final SimpleDateFormat OTHER_DAY_FMT =
            new SimpleDateFormat("dd MMM yyyy  HH:mm", Locale.US);

    public interface ClipboardPickListener {
        void onClipPicked(ClipEntry entry);
        void onClipClosed();
    }

    private final float density;
    private final ClipboardPickListener listener;
    private final ClipboardStore store;
    private final Handler main = new Handler(Looper.getMainLooper());

    public ClipboardPanelView(Context ctx, ClipboardPickListener listener) {
        super(ctx);
        this.density = ctx.getResources().getDisplayMetrics().density;
        this.listener = listener;
        this.store = ClipboardStore.get(ctx);
        setOrientation(VERTICAL);
        setBackgroundColor(0xFF050505);
        build();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int h = dp(PANEL_HEIGHT_DP);
        int childWidthSpec  = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY);
        int childHeightSpec = MeasureSpec.makeMeasureSpec(h,     MeasureSpec.EXACTLY);
        super.onMeasure(childWidthSpec, childHeightSpec);
        setMeasuredDimension(width, h);
    }

    private void build() {
        removeAllViews();

        LinearLayout header = new LinearLayout(getContext());
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(8), dp(12), dp(8));

        TextView title = new TextView(getContext());
        title.setText("CLIPBOARD");
        title.setTextColor(0xFF33FF33);
        title.setTextSize(12);
        title.setTypeface(Typeface.MONOSPACE);
        header.addView(title, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        TextView clear = new TextView(getContext());
        clear.setText("CLEAR");
        clear.setTextColor(0xFFFF6666);
        clear.setTextSize(11);
        clear.setTypeface(Typeface.MONOSPACE);
        clear.setPadding(dp(14), dp(6), dp(14), dp(6));
        GradientDrawable clearBg = new GradientDrawable();
        clearBg.setColor(0xFF1A0A0A);
        clearBg.setCornerRadius(dp(4));
        clearBg.setStroke(dp(1), 0xFF6B1F1F);
        clear.setBackground(clearBg);
        clear.setClickable(true);
        clear.setOnClickListener(v -> {
            store.clear();
            build();
        });
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        clearLp.rightMargin = dp(14);
        header.addView(clear, clearLp);

        TextView close = new TextView(getContext());
        close.setText("X");
        close.setTextColor(0xFF33FF33);
        close.setTextSize(14);
        close.setTypeface(Typeface.MONOSPACE);
        close.setGravity(Gravity.CENTER);
        close.setPadding(dp(14), dp(4), dp(14), dp(4));
        GradientDrawable closeBg = new GradientDrawable();
        closeBg.setColor(0xFF0A1A0A);
        closeBg.setCornerRadius(dp(4));
        closeBg.setStroke(dp(1), ITEM_BORDER_COLOR);
        close.setBackground(closeBg);
        close.setClickable(true);
        close.setOnClickListener(v -> {
            if (listener != null) listener.onClipClosed();
        });
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        closeLp.rightMargin = dp(6);
        header.addView(close, closeLp);

        addView(header);

        View divider = new View(getContext());
        divider.setBackgroundColor(0xFF1F3F1F);
        addView(divider, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(1)));

        ScrollView scroll = new ScrollView(getContext());
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, 0, 1f));
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setBackgroundColor(0xFF050505);

        LinearLayout content = new LinearLayout(getContext());
        content.setOrientation(VERTICAL);
        content.setPadding(dp(6), dp(6), dp(6), dp(6));
        scroll.addView(content);

        List<ClipEntry> items = store.all();
        if (items.isEmpty()) {
            TextView empty = new TextView(getContext());
            empty.setText("No clipboard history.");
            empty.setTextColor(0xFF00AA00);
            empty.setTextSize(12);
            empty.setTypeface(Typeface.MONOSPACE);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(12), dp(24), dp(12), dp(24));
            content.addView(empty);
        } else {
            for (ClipEntry e : items) {
                content.addView(buildRow(e));
            }
        }

        addView(scroll);
    }

    private View buildRow(ClipEntry e) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(VERTICAL);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ITEM_BG_COLOR);
        bg.setCornerRadius(dp(6));
        bg.setStroke(dp(1), ITEM_BORDER_COLOR);
        row.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(4), dp(5), dp(4), dp(5));
        row.setLayoutParams(lp);

        LinearLayout body = new LinearLayout(getContext());
        body.setOrientation(HORIZONTAL);
        body.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(body, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        if (e.type == ClipEntry.TYPE_IMAGE) {
            ImageView iv = new ImageView(getContext());
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(dp(56), dp(56));
            ivLp.rightMargin = dp(12);
            iv.setLayoutParams(ivLp);
            iv.setBackgroundColor(0xFF000000);
            body.addView(iv);

            TextView label = new TextView(getContext());
            label.setText("IMAGE");
            label.setTextColor(0xFF33FF33);
            label.setTextSize(12);
            label.setTypeface(Typeface.MONOSPACE);
            label.setLayoutParams(new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            body.addView(label);

            final String path = e.localPath;
            new Thread(() -> {
                Bitmap bmp = decodeScaled(path, 128);
                if (bmp != null) {
                    main.post(() -> iv.setImageBitmap(bmp));
                }
            }, "clip-thumb").start();
        } else if (e.type == ClipEntry.TYPE_FILE) {
            TextView icon = new TextView(getContext());
            icon.setText("[FILE]");
            icon.setTextColor(0xFF33FF33);
            icon.setTextSize(12);
            icon.setTypeface(Typeface.MONOSPACE);
            icon.setPadding(0, 0, dp(12), 0);
            body.addView(icon);

            TextView label = new TextView(getContext());
            String name = e.displayName != null ? e.displayName : "file";
            String mime = e.mimeType != null ? e.mimeType : "application/octet-stream";
            label.setText(name + "\n" + mime);
            label.setTextColor(0xFF33FF33);
            label.setTextSize(12);
            label.setTypeface(Typeface.MONOSPACE);
            label.setMaxLines(2);
            label.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            label.setLayoutParams(new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            body.addView(label);
        } else {
            TextView text = new TextView(getContext());
            String preview = e.text;
            if (preview.length() > PREVIEW_MAX_CHARS) {
                preview = preview.substring(0, PREVIEW_MAX_CHARS) + "…";
            }
            text.setText(preview);
            text.setTextColor(0xFF33FF33);
            text.setTextSize(12);
            text.setTypeface(Typeface.MONOSPACE);
            text.setMaxLines(4);
            text.setSingleLine(false);
            text.setEllipsize(android.text.TextUtils.TruncateAt.END);
            text.setLayoutParams(new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            body.addView(text);
        }

        TextView stamp = new TextView(getContext());
        stamp.setText(formatTimestamp(e.timestamp));
        stamp.setTextColor(TIME_COLOR);
        stamp.setTextSize(10);
        stamp.setTypeface(Typeface.MONOSPACE);
        stamp.setGravity(Gravity.END);
        LinearLayout.LayoutParams stampLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        stampLp.topMargin = dp(6);
        row.addView(stamp, stampLp);

        row.setClickable(true);
        row.setFocusable(true);
        final ClipEntry entry = e;
        row.setOnClickListener(v -> {
            if (listener != null) listener.onClipPicked(entry);
        });

        return row;
    }

    private String formatTimestamp(long ts) {
        if (ts <= 0) return "";
        long now = System.currentTimeMillis();
        java.util.Calendar today = java.util.Calendar.getInstance();
        java.util.Calendar then  = java.util.Calendar.getInstance();
        then.setTimeInMillis(ts);

        boolean sameDay = today.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR)
                && today.get(java.util.Calendar.DAY_OF_YEAR)
                == then.get(java.util.Calendar.DAY_OF_YEAR);

        Date d = new Date(ts);
        if (sameDay) {
            return "TODAY  " + SAME_DAY_FMT.format(d);
        }
        long days = Math.abs((now - ts) / (1000L * 60 * 60 * 24));
        if (days == 1) {
            return "YESTERDAY  " + SAME_DAY_FMT.format(d);
        }
        return OTHER_DAY_FMT.format(d);
    }

    private static Bitmap decodeScaled(String path, int reqSize) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            int sample = 1;
            while ((bounds.outWidth / sample) > reqSize * 2
                    || (bounds.outHeight / sample) > reqSize * 2) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            return BitmapFactory.decodeFile(path, opts);
        } catch (Throwable t) {
            return null;
        }
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}