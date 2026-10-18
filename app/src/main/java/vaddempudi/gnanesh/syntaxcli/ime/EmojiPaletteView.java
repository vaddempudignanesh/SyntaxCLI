package vaddempudi.gnanesh.syntaxcli.ime;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class EmojiPaletteView extends LinearLayout {

    private static final String TAG = "EmojiPalette";

    // ── ADJUST THESE TO CHANGE THE PANEL SIZE ──
    private static final int PANEL_HEIGHT_DP       = 352;
    private static final int TAB_ROW_HEIGHT_DP     = 40;   // bigger — was 24
    private static final int BOTTOM_BAR_HEIGHT_DP  = 60;   // ★ match keyboard ROW_HEIGHT_DP
    private static final int SECTION_HEADER_DP     = 24;   // was 20

    public interface EmojiPickListener {
        void onEmojiPicked(String emoji);
    }

    public interface PanelControl {
        void closePanel();
        void sendEnter();
        void sendBackspace();
    }

    private final float density;
    private final EmojiPickListener listener;

    private LinearLayout tabRow;
    private ScrollView emojiScroll;
    private LinearLayout emojiContent;
    private int activeIndex = 0;

    // ★ Cache built grid views so re-renders don't rebuild ~1500 TextViews
    private View[] cachedGrids;

    // Header top-y within emojiContent, per section
    private int[] sectionTopPx;
    // True while we programmatically scroll to a section — don't fight the user
    private boolean suppressScrollSync = false;

    public EmojiPaletteView(Context ctx, EmojiPickListener listener) {
        super(ctx);
        this.density = ctx.getResources().getDisplayMetrics().density;
        this.listener = listener;
        setOrientation(VERTICAL);
        setBackgroundColor(KeyboardConfig.COLOR_EMOJI_PANEL_BG);
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
        List<EmojiData.Category> cats = EmojiData.categories();
        if (cats == null || cats.isEmpty()) { /* …unchanged guard… */ return; }

        EmojiGridCache cache = EmojiGridCache.get(getContext());
        // Make sure the picker callback is wired (also set from BasicImeService)
        cache.setPicker(listener);

        // Tab strip
        HorizontalScrollView tabScroll = new HorizontalScrollView(getContext());
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabScroll.setLayoutParams(new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(TAB_ROW_HEIGHT_DP)));
        tabRow = new LinearLayout(getContext());
        tabRow.setOrientation(HORIZONTAL);
        tabScroll.addView(tabRow);
        addView(tabScroll);

        // Scroll area
        emojiScroll = new ScrollView(getContext());
        emojiScroll.setLayoutParams(new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, 0, 1f));
        emojiScroll.setFillViewport(true);
        emojiScroll.setVerticalScrollBarEnabled(true);
        emojiScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        emojiScroll.setBackgroundColor(KeyboardConfig.COLOR_EMOJI_PANEL_BG);

        emojiContent = new LinearLayout(getContext());
        emojiContent.setOrientation(VERTICAL);
        emojiScroll.addView(emojiContent);
        addView(emojiScroll);

        emojiScroll.setOnScrollChangeListener((v, sx, sy, osx, osy) ->
                syncActiveTabFromScroll(sy));

        addView(buildBottomBar());

        // Tabs
        for (int i = 0; i < cats.size(); i++) {
            final int idx = i;
            final EmojiData.Category cat = cats.get(i);
            TextView tab = new TextView(getContext());
            tab.setText(cat.name);
            tab.setTypeface(Typeface.MONOSPACE);
            tab.setTextSize(10);
            tab.setPadding(dp(10), dp(2), dp(10), dp(2));
            tab.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT);
            lp.setMarginEnd(dp(3));
            tab.setLayoutParams(lp);
            tab.setOnClickListener(v -> {
                activeIndex = idx;
                highlightTabs();
                scrollToSection(idx);
            });
            tabRow.addView(tab);
        }

        sectionTopPx = new int[cats.size()];

        // ★ Attach cached grids — synchronous, instant, no allocations
        emojiContent.removeAllViews();
        for (int i = 0; i < cats.size(); i++) {
            emojiContent.addView(makeHeader(cats.get(i).name));
            View grid = cache.gridFor(i);
            if (grid != null) emojiContent.addView(grid);
        }

        highlightTabs();

        // Measure header positions AFTER layout, then jump to active tab
        emojiContent.post(() -> {
            measureSectionTops();
            scrollToSection(activeIndex);
        });
    }

    /** Show headers immediately so the panel isn't blank while grids build. */
    private void renderSkeleton(List<EmojiData.Category> cats) {
        emojiContent.removeAllViews();
        for (int c = 0; c < cats.size(); c++) {
            emojiContent.addView(makeHeader(cats.get(c).name));
        }
    }

    /** Attach cached grids under the headers. */
    private void populateAll(List<EmojiData.Category> cats) {
        emojiContent.removeAllViews();
        for (int c = 0; c < cats.size(); c++) {
            emojiContent.addView(makeHeader(cats.get(c).name));
            if (cachedGrids[c] != null) emojiContent.addView(cachedGrids[c]);
        }
        // Record header top-y now that layout runs
        emojiContent.post(this::measureSectionTops);
    }

    private TextView makeHeader(String name) {
        TextView header = new TextView(getContext());
        header.setText(name);
        header.setTypeface(Typeface.MONOSPACE);
        header.setTextSize(11);
        header.setTextColor(KeyboardConfig.COLOR_EMOJI_CATEGORY_TEXT);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), 0, dp(8), 0);
        header.setBackgroundColor(0xFF0A0A0A);
        header.setLayoutParams(new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(SECTION_HEADER_DP)));
        return header;
    }

    private void measureSectionTops() {
        if (emojiContent == null) return;
        int section = 0;
        for (int i = 0; i < emojiContent.getChildCount(); i++) {
            View v = emojiContent.getChildAt(i);
            if (v instanceof TextView) {
                if (section < sectionTopPx.length) {
                    sectionTopPx[section] = v.getTop();
                }
                section++;
            }
        }
    }

    /** Update highlighted tab as user scrolls. */
    private void syncActiveTabFromScroll(int scrollY) {
        if (suppressScrollSync || sectionTopPx == null) return;
        int best = 0;
        for (int i = 0; i < sectionTopPx.length; i++) {
            if (scrollY + dp(SECTION_HEADER_DP) >= sectionTopPx[i]) {
                best = i;
            } else {
                break;
            }
        }
        if (best != activeIndex) {
            activeIndex = best;
            highlightTabs();
        }
    }

    private void highlightTabs() {
        for (int i = 0; i < tabRow.getChildCount(); i++) {
            TextView tab = (TextView) tabRow.getChildAt(i);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(4));
            if (i == activeIndex) {
                bg.setColor(KeyboardConfig.COLOR_EMOJI_CATEGORY_SEL);
                bg.setStroke(dp(1), KeyboardConfig.COLOR_EMOJI_CATEGORY_TEXT);
                tab.setBackground(bg);
                tab.setTextColor(0xFF000000);
            } else {
                bg.setColor(0xFF0A0A0A);
                bg.setStroke(dp(1), 0xFF1F3F1F);
                tab.setBackground(bg);
                tab.setTextColor(KeyboardConfig.COLOR_EMOJI_CATEGORY_TEXT);
            }
        }
    }

    private void scrollToSection(int sectionIndex) {
        if (emojiScroll == null || sectionTopPx == null) return;
        if (sectionIndex < 0 || sectionIndex >= sectionTopPx.length) return;
        suppressScrollSync = true;
        int y = sectionTopPx[sectionIndex];
        emojiScroll.post(() -> {
            emojiScroll.smoothScrollTo(0, y);
            emojiScroll.postDelayed(() -> suppressScrollSync = false, 350);
        });
    }

    private View buildEmojiGrid(String[] emojis) {
        LinearLayout wrap = new LinearLayout(getContext());
        wrap.setOrientation(VERTICAL);
        wrap.setPadding(0, dp(2), 0, dp(4));

        final int cellPx = dp(KeyboardConfig.EMOJI_CELL_SIZE_DP);
        final int gap = dp(2);
        final int screenW = getResources().getDisplayMetrics().widthPixels;
        final int usable = Math.max(screenW, cellPx + gap);
        final int perRow = Math.max(1, usable / (cellPx + gap));
        final int textSize = (int) KeyboardConfig.EMOJI_TEXT_SIZE_SP;

        for (int i = 0; i < emojis.length; i += perRow) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(HORIZONTAL);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, cellPx));

            for (int j = i; j < i + perRow && j < emojis.length; j++) {
                final String emoji = emojis[j];
                TextView cell = new TextView(getContext());
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
                    if (listener != null) listener.onEmojiPicked(emoji);
                });
                row.addView(cell);
            }
            wrap.addView(row);
        }
        return wrap;
    }

    private View buildBottomBar() {
        LinearLayout bar = new LinearLayout(getContext());
        bar.setOrientation(HORIZONTAL);
        bar.setBackgroundColor(KeyboardConfig.COLOR_EMOJI_PANEL_BG);
        bar.setPadding(dp(4), dp(4), dp(4), dp(4));
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(BOTTOM_BAR_HEIGHT_DP)));

        TextView abc = makeBottomKey("ABC", 1.2f);
        abc.setOnClickListener(v -> {
            if (listener instanceof PanelControl) {
                ((PanelControl) listener).closePanel();
            }
        });
        bar.addView(abc);

        TextView space = makeBottomKey("space", 3.5f);
        space.setOnClickListener(v -> {
            if (listener != null) listener.onEmojiPicked(" ");
        });
        bar.addView(space);

        TextView back = makeBottomKey("\u232B", 1.2f);
        back.setOnClickListener(v -> {
            if (listener instanceof PanelControl) {
                ((PanelControl) listener).sendBackspace();
            }
        });
        bar.addView(back);

        TextView enter = makeBottomKey("\u23CE", 1.2f);
        enter.setOnClickListener(v -> {
            if (listener instanceof PanelControl) {
                PanelControl pc = (PanelControl) listener;
                pc.closePanel();
                pc.sendEnter();
            }
        });
        bar.addView(enter);

        return bar;
    }

    private TextView makeBottomKey(String label, float weight) {
        TextView tv = new TextView(getContext());
        tv.setText(label);
        tv.setGravity(Gravity.CENTER);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(KeyboardConfig.COLOR_EMOJI_CATEGORY_TEXT);
        tv.setTextSize(14);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF0A0A0A);
        bg.setCornerRadius(dp(6));
        bg.setStroke(dp(1), KeyboardConfig.COLOR_EMOJI_CATEGORY_TEXT);
        tv.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LayoutParams.MATCH_PARENT, weight);
        lp.setMarginEnd(dp(4));
        tv.setLayoutParams(lp);
        tv.setClickable(true);
        return tv;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}