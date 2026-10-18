package vaddempudi.gnanesh.syntaxcli.ime;

import android.content.Context;
import android.graphics.Typeface;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds each emoji grid ONCE on first use (or eagerly via {@link #warm(Context)}),
 * keeps the resulting Views in RAM forever. Re-opening the emoji panel just
 * re-attaches the cached Views — no allocations, no measure passes, no I/O.
 */
public final class EmojiGridCache {

    private static volatile EmojiGridCache INSTANCE;
    private static final Object LOCK = new Object();

    /** Category name → fully-built grid View (LinearLayout of rows of TextViews). */
    private final Map<String, View> grids = new HashMap<>();

    /** Index → same View, for O(1) tab lookup. */
    private final SparseArray<View> byIndex = new SparseArray<>();

    private Context appCtx;
    private float density;
    private EmojiPaletteView.EmojiPickListener picker;

    private EmojiGridCache(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
        this.density = appCtx.getResources().getDisplayMetrics().density;
    }

    public static EmojiGridCache get(Context ctx) {
        if (INSTANCE == null) {
            synchronized (LOCK) {
                if (INSTANCE == null) INSTANCE = new EmojiGridCache(ctx);
            }
        }
        return INSTANCE;
    }

    /** Listener the cells call on tap. Set once from BasicImeService. */
    public void setPicker(EmojiPaletteView.EmojiPickListener p) {
        this.picker = p;
    }

    /**
     * Build every category's grid now (call from BasicImeService.onCreate).
     * Runs on a background thread you provide, or main thread if you accept
     * the one-time cost (~50-100ms). Best: call from a worker.
     */
    public void warm() {
        synchronized (LOCK) {
            if (!grids.isEmpty()) return; // already warm
            List<EmojiData.Category> cats = EmojiData.categories();
            for (int i = 0; i < cats.size(); i++) {
                EmojiData.Category c = cats.get(i);
                if (c.items == null || c.items.length == 0) continue;
                View grid = build(c.items);
                grids.put(c.name, grid);
                byIndex.put(i, grid);
            }
        }
    }

    public View gridFor(String name) {
        View v = grids.get(name);
        if (v == null) {
            // First call for this category — build lazily (rare if warm() ran)
            synchronized (LOCK) {
                v = grids.get(name);
                if (v == null) {
                    List<EmojiData.Category> cats = EmojiData.categories();
                    for (int i = 0; i < cats.size(); i++) {
                        EmojiData.Category c = cats.get(i);
                        if (!c.name.equals(name)) continue;
                        if (c.items == null || c.items.length == 0) return null;
                        v = build(c.items);
                        grids.put(name, v);
                        byIndex.put(i, v);
                        break;
                    }
                }
            }
        }
        if (v != null && v.getParent() instanceof android.view.ViewGroup) {
            // Detach so the panel can re-attach it in another container
            ((android.view.ViewGroup) v.getParent()).removeView(v);
        }
        return v;
    }

    public View gridFor(int index) {
        View v = byIndex.get(index);
        if (v == null) {
            List<EmojiData.Category> cats = EmojiData.categories();
            if (index >= 0 && index < cats.size()) {
                return gridFor(cats.get(index).name);
            }
            return null;
        }
        if (v.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) v.getParent()).removeView(v);
        }
        return v;
    }

    // ────────────────────────────────────────────────────────────
    // Grid construction — identical layout to before, just built once
    // ────────────────────────────────────────────────────────────
    private View build(String[] emojis) {
        LinearLayout wrap = new LinearLayout(appCtx);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(0, dp(2), 0, dp(4));

        final int cellPx = dp(KeyboardConfig.EMOJI_CELL_SIZE_DP);
        final int gap = dp(2);
        final int screenW = appCtx.getResources().getDisplayMetrics().widthPixels;
        final int usable = Math.max(screenW, cellPx + gap);
        final int perRow = Math.max(1, usable / (cellPx + gap));
        final int textSize = (int) KeyboardConfig.EMOJI_TEXT_SIZE_SP;

        for (int i = 0; i < emojis.length; i += perRow) {
            LinearLayout row = new LinearLayout(appCtx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, cellPx));

            for (int j = i; j < i + perRow && j < emojis.length; j++) {
                final String emoji = emojis[j];
                TextView cell = new TextView(appCtx);
                cell.setText(emoji);
                cell.setGravity(Gravity.CENTER);
                cell.setTextColor(KeyboardConfig.COLOR_EMOJI_TEXT);
                cell.setSingleLine(true);
                cell.setIncludeFontPadding(false);
                cell.setMaxLines(1);
                cell.setTypeface(Typeface.DEFAULT);

                int sp = emoji.length() > 4 ? (int) (textSize * 0.55f) : textSize;
                cell.setTextSize(sp);

                LinearLayout.LayoutParams lp =
                        new LinearLayout.LayoutParams(cellPx, cellPx);
                boolean isLastInRow = (j == i + perRow - 1) || (j == emojis.length - 1);
                if (!isLastInRow) lp.setMarginEnd(gap);
                cell.setLayoutParams(lp);

                cell.setClickable(true);
                cell.setOnClickListener(v -> {
                    EmojiPaletteView.EmojiPickListener l = picker;
                    if (l != null) l.onEmojiPicked(emoji);
                });
                row.addView(cell);
            }
            wrap.addView(row);
        }
        return wrap;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}