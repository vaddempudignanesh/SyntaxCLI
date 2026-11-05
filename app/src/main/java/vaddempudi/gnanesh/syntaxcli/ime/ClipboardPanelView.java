package vaddempudi.gnanesh.syntaxcli.ime;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

public class ClipboardPanelView extends LinearLayout {

    public interface ClipboardPickListener {
        void onClipPicked(String text);
        void onClipClosed();
    }

    private final float density;
    private final ClipboardPickListener listener;
    private final ClipboardStore store;

    public ClipboardPanelView(Context ctx, ClipboardPickListener listener) {
        super(ctx);
        this.density = ctx.getResources().getDisplayMetrics().density;
        this.listener = listener;
        this.store = ClipboardStore.get(ctx);
        setOrientation(VERTICAL);
        setBackgroundColor(0xFF050505);
        build();
    }

    private void build() {
        LinearLayout header = new LinearLayout(getContext());
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(10), dp(6), dp(10), dp(6));

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
        clear.setPadding(dp(8), dp(4), dp(8), dp(4));
        clear.setOnClickListener(v -> {
            store.clear();
            removeAllViews();
            build();
        });
        header.addView(clear);

        TextView close = new TextView(getContext());
        close.setText("X");
        close.setTextColor(0xFF33FF33);
        close.setTextSize(14);
        close.setTypeface(Typeface.MONOSPACE);
        close.setPadding(dp(10), dp(2), dp(10), dp(2));
        close.setOnClickListener(v -> {
            if (listener != null) listener.onClipClosed();
        });
        header.addView(close);

        addView(header);

        ScrollView scroll = new ScrollView(getContext());
        scroll.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setBackgroundColor(0xFF050505);

        LinearLayout content = new LinearLayout(getContext());
        content.setOrientation(VERTICAL);
        scroll.addView(content);

        List<String> items = store.all();
        if (items.isEmpty()) {
            TextView empty = new TextView(getContext());
            empty.setText("No clipboard history.");
            empty.setTextColor(0xFF00AA00);
            empty.setTextSize(12);
            empty.setTypeface(Typeface.MONOSPACE);
            empty.setPadding(dp(12), dp(12), dp(12), dp(12));
            content.addView(empty);
        } else {
            for (String s : items) {
                TextView row = new TextView(getContext());
                row.setText(s);
                row.setTextColor(0xFF33FF33);
                row.setTextSize(12);
                row.setTypeface(Typeface.MONOSPACE);
                row.setPadding(dp(10), dp(8), dp(10), dp(8));
                row.setMaxLines(3);
                row.setSingleLine(false);
                row.setEllipsize(android.text.TextUtils.TruncateAt.END);
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(0xFF0A0A0A);
                bg.setCornerRadius(dp(4));
                bg.setStroke(dp(1), 0xFF1F3F1F);
                row.setBackground(bg);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
                lp.setMargins(dp(4), dp(3), dp(4), dp(3));
                row.setLayoutParams(lp);
                final String text = s;
                row.setOnClickListener(v -> {
                    if (listener != null) listener.onClipPicked(text);
                });
                content.addView(row);
            }
        }

        addView(scroll);
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}