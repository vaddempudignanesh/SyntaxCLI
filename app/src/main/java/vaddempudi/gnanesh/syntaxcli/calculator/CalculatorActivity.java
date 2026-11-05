package vaddempudi.gnanesh.syntaxcli.calculator;

import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

import vaddempudi.gnanesh.syntaxcli.R;

public class CalculatorActivity extends AppCompatActivity {

    private static final String PREFS = "ohi_calc_store";
    private static final String KEY_HISTORY = "history";
    private static final int MAX_HISTORY = 500;

    // ── Engine state ──
    private String currentInput = "";   // the raw expression string shown in display
    private boolean justEvaluated = false;
    private int openParenCount = 0;

    private final DecimalFormat fmt = new DecimalFormat("#.##########");

    // ── Views ──
    private TextView display, expression;
    private View historyPanel;
    private RecyclerView historyList;
    private HistoryAdapter historyAdapter;
    private TextView historyEmpty;

    // ── History ──
    private final List<String> history = new ArrayList<>();
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_calculator);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        loadHistory();

        display        = findViewById(R.id.calc_display);
        expression     = findViewById(R.id.calc_expression);
        historyPanel   = findViewById(R.id.calc_history_panel);
        historyList    = findViewById(R.id.calc_history_scroll);
        historyEmpty   = findViewById(R.id.calc_history_empty);

        historyList.setLayoutManager(new LinearLayoutManager(this));
        historyList.setItemAnimator(null);
        historyAdapter = new HistoryAdapter();
        historyList.setAdapter(historyAdapter);

        setupButtons();
        refreshDisplay();
        refreshHistory();
    }

    // ═══════════════════ History persistence ═══════════════════

    private void loadHistory() {
        history.clear();
        String raw = prefs.getString(KEY_HISTORY, "");
        if (raw == null || raw.isEmpty()) return;
        for (String line : raw.split("\n")) {
            if (!line.isEmpty()) history.add(line);
        }
    }

    private void saveHistory() {
        while (history.size() > MAX_HISTORY) history.remove(0);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < history.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(history.get(i));
        }
        prefs.edit().putString(KEY_HISTORY, sb.toString()).apply();
    }

    private void addHistory(String entry) {
        if (history.size() >= MAX_HISTORY) {
            history.remove(0);
            historyAdapter.notifyItemRemoved(0);
        }
        history.add(entry);
        historyAdapter.notifyItemInserted(history.size() - 1);
        if (historyPanel.getVisibility() == View.VISIBLE) {
            historyList.scrollToPosition(history.size() - 1);
        }
        updateEmptyState();
        saveHistory();
    }

    private void clearHistory() {
        int n = history.size();
        history.clear();
        historyAdapter.notifyItemRangeRemoved(0, n);
        updateEmptyState();
        saveHistory();
    }

    private void refreshHistory() {
        historyAdapter.notifyDataSetChanged();
        updateEmptyState();
        if (!history.isEmpty()) {
            historyList.scrollToPosition(history.size() - 1);
        }
    }

    private void updateEmptyState() {
        if (historyEmpty != null) {
            historyEmpty.setVisibility(history.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    private void setupButtons() {
        int[] numberIds = {
                R.id.btn_0, R.id.btn_1, R.id.btn_2, R.id.btn_3, R.id.btn_4,
                R.id.btn_5, R.id.btn_6, R.id.btn_7, R.id.btn_8, R.id.btn_9
        };
        for (int id : numberIds) {
            View b = findViewById(id);
            b.setOnClickListener(v -> {
                CharSequence t = ((TextView) v).getText();
                appendNumber(t.toString());
            });
            animate(b);
        }

        bind(R.id.btn_plus,     v -> setOperator("+"));
        bind(R.id.btn_minus,    v -> setOperator("-"));
        bind(R.id.btn_multiply, v -> setOperator("×"));
        bind(R.id.btn_divide,   v -> setOperator("÷"));
        bind(R.id.btn_equals,   v -> calculate());
        bind(R.id.btn_clear,    v -> clearAll());
        bind(R.id.btn_decimal,  v -> appendDecimal());
        bind(R.id.btn_backspace,v -> backspace());
        bind(R.id.btn_history,  v -> toggleHistory());

        bind(R.id.btn_open_paren,  v -> appendOpenParen());
        bind(R.id.btn_close_paren, v -> appendCloseParen());

        View clearHist = findViewById(R.id.btn_clear_history);
        if (clearHist != null) {
            clearHist.setOnClickListener(v -> clearHistory());
            animate(clearHist);
        }

        View back = findViewById(R.id.btn_back);
        if (back != null) {
            back.setOnClickListener(v -> finish());
            animate(back);
        }
    }

    private void bind(int id, View.OnClickListener l) {
        View v = findViewById(id);
        if (v == null) return;
        v.setOnClickListener(l);
        animate(v);
    }

    // ═══════════════════ Expression builder ═══════════════════

    private void appendNumber(String num) {
        if (justEvaluated) {
            currentInput = "";
            openParenCount = 0;
            justEvaluated = false;
        }
        if (currentInput.endsWith(")")) return;

        // Collapse leading zeros like "007"
        char last = currentInput.isEmpty() ? 0 : currentInput.charAt(currentInput.length() - 1);
        if (last == '0') {
            char prev = currentInput.length() >= 2
                    ? currentInput.charAt(currentInput.length() - 2) : 0;
            if (!Character.isDigit(prev) && prev != '.' && prev != ')') {
                currentInput = currentInput.substring(0, currentInput.length() - 1);
            }
        }
        if (currentInput.length() >= 40) return;
        currentInput += num;
        refreshDisplay();
    }

    private void appendDecimal() {
        if (justEvaluated) {
            currentInput = "";
            openParenCount = 0;
            justEvaluated = false;
        }
        if (currentInput.endsWith(")")) return;

        // Check current number token for an existing '.'
        int i = currentInput.length() - 1;
        while (i >= 0 && (Character.isDigit(currentInput.charAt(i))
                || currentInput.charAt(i) == '.')) {
            if (currentInput.charAt(i) == '.') return;
            i--;
        }
        if (i >= 0 && currentInput.charAt(i) == ')') return;

        if (currentInput.isEmpty()
                || "+-×÷(".indexOf(currentInput.charAt(currentInput.length() - 1)) >= 0) {
            currentInput += "0.";
        } else {
            currentInput += ".";
        }
        refreshDisplay();
    }

    private void appendOpenParen() {
        if (justEvaluated) {
            currentInput = "";
            openParenCount = 0;
            justEvaluated = false;
        }
        if (!currentInput.isEmpty()) {
            char last = currentInput.charAt(currentInput.length() - 1);
            boolean lastIsValue = Character.isDigit(last) || last == ')' || last == '.';
            if (lastIsValue) {
                currentInput += "×";   // implicit multiply: 5( → 5×(
            }
        }
        currentInput += "(";
        openParenCount++;
        refreshDisplay();
    }

    private void appendCloseParen() {
        if (openParenCount <= 0) return;
        if (currentInput.isEmpty()) return;
        char last = currentInput.charAt(currentInput.length() - 1);
        if (last == '(' || last == '+' || last == '-' || last == '×' || last == '÷') return;
        currentInput += ")";
        openParenCount--;
        refreshDisplay();
    }

    private void setOperator(String op) {
        if (justEvaluated) {
            justEvaluated = false;
            openParenCount = 0;
        }
        if (currentInput.isEmpty()) {
            if (op.equals("-")) { currentInput = "-"; refreshDisplay(); }
            return;
        }
        char last = currentInput.charAt(currentInput.length() - 1);
        if (last == '+' || last == '-' || last == '×' || last == '÷') {
            if (currentInput.length() == 1) return;   // keep a lone leading minus
            currentInput = currentInput.substring(0, currentInput.length() - 1) + op;
        } else if (last == '(') {
            if (op.equals("-")) currentInput += "-";
        } else {
            currentInput += op;
        }
        refreshDisplay();
    }

    private void calculate() {
        if (currentInput.isEmpty()) return;

        // Auto-close any open parens so eval succeeds
        StringBuilder sb = new StringBuilder(currentInput);
        int toClose = openParenCount;
        while (toClose-- > 0) sb.append(')');
        String expr = sb.toString();

        double result;
        try {
            result = Evaluator.eval(expr);
        } catch (Throwable t) {
            result = Double.NaN;
        }

        String resultStr = format(result);
        addHistory(expr + " = " + resultStr);

        currentInput = resultStr;
        openParenCount = 0;
        justEvaluated = true;
        refreshDisplay();
    }

    private void clearAll() {
        currentInput = "";
        openParenCount = 0;
        justEvaluated = false;
        refreshDisplay();
    }

    private void backspace() {
        if (justEvaluated) {
            currentInput = "";
            justEvaluated = false;
            openParenCount = 0;
            refreshDisplay();
            return;
        }
        if (currentInput.isEmpty()) return;
        char removed = currentInput.charAt(currentInput.length() - 1);
        currentInput = currentInput.substring(0, currentInput.length() - 1);
        if (removed == '(') openParenCount = Math.max(0, openParenCount - 1);
        if (removed == ')') openParenCount++;
        refreshDisplay();
    }

    private void toggleHistory() {
        if (historyPanel == null) return;
        if (historyPanel.getVisibility() == View.VISIBLE) {
            historyPanel.setVisibility(View.GONE);
        } else {
            historyPanel.setVisibility(View.VISIBLE);
            refreshHistory();
        }
    }

    // ═══════════════════ Display ═══════════════════

    private void refreshDisplay() {
        display.setText(currentInput.isEmpty() ? "0" : currentInput);

        // Live preview of the running value in the secondary line
        if (!currentInput.isEmpty() && !justEvaluated) {
            try {
                double preview = Evaluator.eval(
                        currentInput + repeatChar(')', openParenCount));
                expression.setText(format(preview));
            } catch (Throwable t) {
                expression.setText("");
            }
        } else {
            expression.setText("");
        }
    }

    private static String repeatChar(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    private String format(double d) {
        if (Double.isNaN(d)) return "Error";
        if (Double.isInfinite(d)) return "Error";
        if (d == (long) d) return String.valueOf((long) d);
        String s = fmt.format(d);
        if (s.startsWith(".")) s = "0" + s;
        return s;
    }

    // ═══════════════════ Recursive-descent evaluator ═══════════════════

    private static final class Evaluator {
        private final String s;
        private int pos;

        private Evaluator(String s) { this.s = s; }

        static double eval(String expr) {
            Evaluator p = new Evaluator(expr);
            double v = p.parseExpr();
            p.skipWs();
            if (p.pos < p.s.length()) throw new IllegalArgumentException("trailing input");
            return v;
        }

        private void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        private char peek() { skipWs(); return pos < s.length() ? s.charAt(pos) : '\0'; }

        private double parseExpr() {
            double left = parseTerm();
            while (true) {
                char c = peek();
                if (c == '+' || c == '-') {
                    pos++;
                    double right = parseTerm();
                    left = (c == '+') ? left + right : left - right;
                } else break;
            }
            return left;
        }

        private double parseTerm() {
            double left = parseUnary();
            while (true) {
                char c = peek();
                if (c == '×' || c == '*' || c == '÷' || c == '/') {
                    pos++;
                    double right = parseUnary();
                    if (c == '×' || c == '*') left = left * right;
                    else {
                        if (right == 0) return Double.NaN;
                        left = left / right;
                    }
                } else break;
            }
            return left;
        }

        private double parseUnary() {
            char c = peek();
            if (c == '-') { pos++; return -parseUnary(); }
            if (c == '+') { pos++; return parseUnary(); }
            return parseAtom();
        }

        private double parseAtom() {
            char c = peek();
            if (c == '(') {
                pos++;
                double v = parseExpr();
                if (peek() != ')') throw new IllegalArgumentException("missing )");
                pos++;
                return v;
            }
            int start = pos;
            while (pos < s.length()
                    && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) {
                pos++;
            }
            if (start == pos) throw new IllegalArgumentException("number expected");
            return Double.parseDouble(s.substring(start, pos));
        }
    }

    // ═══════════════════ Recycler adapter ═══════════════════

    private class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.VH> {

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView tv = new TextView(CalculatorActivity.this);
            tv.setTextColor(0xFF00FF00);
            tv.setTextSize(13);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            tv.setPadding(dp(8), dp(6), dp(8), dp(6));
            tv.setBackgroundColor(0x00000000);
            tv.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            return new VH(tv);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            h.text.setText(history.get(position));
        }

        @Override
        public int getItemCount() { return history.size(); }

        class VH extends RecyclerView.ViewHolder {
            final TextView text;
            VH(@NonNull View v) {
                super(v);
                text = (TextView) v;
            }
        }
    }

    // ═══════════════════ Visual feedback ═══════════════════

    private void animate(View v) {
        v.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate()
                            .scaleX(0.72f).scaleY(0.72f)
                            .rotation(3f)
                            .alpha(0.5f)
                            .setDuration(55)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator(2f))
                            .start();
                    break;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate()
                            .scaleX(1f).scaleY(1f)
                            .rotation(0f)
                            .alpha(1f)
                            .setDuration(160)
                            .setInterpolator(new android.view.animation.OvershootInterpolator(2.2f))
                            .start();
                    break;
            }
            return false;
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}