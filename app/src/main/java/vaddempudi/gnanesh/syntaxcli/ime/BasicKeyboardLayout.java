package vaddempudi.gnanesh.syntaxcli.ime;

import java.util.ArrayList;
import java.util.List;

public final class BasicKeyboardLayout {

    public final List<BasicKey[]> letterRows;
    public final List<BasicKey[]> symbolPage1;
    public final List<BasicKey[]> symbolPage2;
    public final List<BasicKey[]> symbolPage3;

    public BasicKeyboardLayout() {
        letterRows = new ArrayList<>();
        symbolPage1 = new ArrayList<>();
        symbolPage2 = new ArrayList<>();
        symbolPage3 = new ArrayList<>();
        buildLetters();
        buildSymbolPage1();
        buildSymbolPage2();
        buildSymbolPage3();
    }

    private void buildLetters() {

        // Row 1 — numbers
        letterRows.add(new BasicKey[] {
                sym("1"), sym("2"), sym("3"), sym("4"), sym("5"),
                sym("6"), sym("7"), sym("8"), sym("9"), sym("0")
        });

        // Row 2 — QWERTY top
        letterRows.add(new BasicKey[] {
                key("q"), key("w"), key("e"), key("r"), key("t"),
                key("y"), key("u"), key("i"), key("o"), key("p")
        });

        // Row 3 — ASDF
        letterRows.add(new BasicKey[] {
                key("a"), key("s"), key("d"), key("f"), key("g"),
                key("h"), key("j"), key("k"), key("l")
        });

        // Row 4 — SHIFT + ZXCV + BACKSPACE
        letterRows.add(new BasicKey[] {
                new BasicKey(BasicKey.CODE_SHIFT, null, null, "\u21E7", 1.5f),
                key("z"), key("x"), key("c"), key("v"),
                key("b"), key("n"), key("m"),
                new BasicKey(BasicKey.CODE_DELETE, null, null, "\u232B", 1.5f)
        });

        // Row 5 — ?123, comma, space, dot, ENTER
        letterRows.add(new BasicKey[] {
                new BasicKey(BasicKey.CODE_SYMBOLS, null, null, "?123", 1.5f),
                sym(","),
                new BasicKey(BasicKey.CODE_SPACE, null, null, "space", 4f),
                sym("."),
                new BasicKey(BasicKey.CODE_ENTER, null, null, "\u23CE", 1.5f)
        });
    }

    private void buildSymbolPage1() {
        // Row 1 — numbers
        symbolPage1.add(new BasicKey[] {
                sym("1"), sym("2"), sym("3"), sym("4"), sym("5"),
                sym("6"), sym("7"), sym("8"), sym("9"), sym("0")
        });

        // Row 2 — @ # ₹ _ & - + ( ) /
        symbolPage1.add(new BasicKey[] {
                sym("@"), sym("#"), sym("\u20B9"), sym("_"), sym("&"),
                sym("-"), sym("+"), sym("("), sym(")"), sym("/")
        });

        // Row 3 — "=\<" (2 buttons wide), space, " * " ' : ; ! ? " (last button backspace)
        // First button: "=\<"  →  CODE_SYM_PAGE_2 (opens symbols page 2)
        // Weight distribution: 2 + 1 + 1 + 1 + 1 + 1 + 1 + 1 + 1 (9 slots)
        //   First button   = 2 slots   (opens page 2)
        //   Second button  = 1/3 slot?  No — "=\<" is the first button and it
        //                     occupies the full width of one button PLUS 1/3 of
        //                     the second button. To express that as a weight,
        //                     we use a first-button weight of 1.34f and the
        //                     back button weight of 1.66f (so both look correct
        //                     against the rest of the row).
        symbolPage1.add(new BasicKey[] {
                new BasicKey(BasicKey.CODE_SYM_PAGE_2, null, null, "=\\<", 1.34f),
                sym("*"), sym("\""), sym("'"), sym(":"),
                sym(";"), sym("!"), sym("?"),
                new BasicKey(BasicKey.CODE_DELETE, null, null, "\u232B", 1.66f)
        });

        // Row 4 — ABC, comma, space, period, ENTER
        symbolPage1.add(new BasicKey[] {
                new BasicKey(BasicKey.CODE_ABC, null, null, "ABC", 1.5f),
                sym(","),
                new BasicKey(BasicKey.CODE_SPACE, null, null, "space", 4f),
                sym("."),
                new BasicKey(BasicKey.CODE_ENTER, null, null, "\u23CE", 1.5f)
        });
    }

    private void buildSymbolPage2() {
        // Row 1 — ~ ` | • √ π ÷ × ¶ ∆
        symbolPage2.add(new BasicKey[] {
                sym("~"), sym("`"), sym("|"), sym("\u2022"), sym("\u221A"),
                sym("\u03C0"), sym("\u00F7"), sym("\u00D7"), sym("\u00B6"), sym("\u2206")
        });

        // Row 2 — € $ ₹ ^ ° = { } "
        symbolPage2.add(new BasicKey[] {
                sym("\u20AC"), sym("$"), sym("\u20B9"), sym("^"), sym("\u00B0"),
                sym("="), sym("{"), sym("}"), sym("\""), sym("\\")
        });

        // Row 3 — ?123 (1/3 width), %, ©, ®, ™, c/o, [, ], ⌫
        //   First button  = 1/3 of row        → CODE_SYM_PAGE_1 (returns to page 1)
        //   Last button   = backspace same width
        symbolPage2.add(new BasicKey[] {
                new BasicKey(BasicKey.CODE_SYM_PAGE_1, null, null, "?123", 1.34f),
                sym("%"), sym("\u00A9"), sym("\u00AE"), sym("\u2122"),
                sym("\u2105"), sym("["), sym("]"),
                new BasicKey(BasicKey.CODE_DELETE, null, null, "\u232B", 1.66f)
        });

        // Row 4 — ABC, space, ENTER (>) — matching page 1 action row
        symbolPage2.add(new BasicKey[] {
                new BasicKey(BasicKey.CODE_ABC, null, null, "ABC", 1.5f),
                new BasicKey(BasicKey.CODE_SPACE, null, null, "space", 4f),
                new BasicKey(BasicKey.CODE_ENTER, null, null, "\u23CE", 1.5f)
        });
    }

    private void buildSymbolPage3() {
        symbolPage3.add(new BasicKey[] {
                sym("\u2190"), sym("\u2191"), sym("\u2192"), sym("\u2193"),
                sym("\u2194"), sym("\u2195"), sym("\u21A9"), sym("\u21AA"),
                sym("\u21BB"), sym("\u21BA")
        });
        symbolPage3.add(new BasicKey[] {
                sym("\u2605"), sym("\u2606"), sym("\u2665"), sym("\u2661"),
                sym("\u266A"), sym("\u266B"), sym("\u2660"), sym("\u2663"),
                sym("\u2666"), sym("\u2664")
        });
        symbolPage3.add(new BasicKey[] {
                sym("\u2022"), sym("\u25CF"), sym("\u25CB"), sym("\u25A0"),
                sym("\u25A1"), sym("\u25B2"), sym("\u25BC"), sym("\u25B6"),
                sym("\u25C0"), sym("\u2B50")
        });
        symbolPage3.add(new BasicKey[] {
                new BasicKey(BasicKey.CODE_SYM_PAGE_1, null, null, "1/3", 1.5f),
                new BasicKey(BasicKey.CODE_DELETE, null, null, "\u232B", 1.5f),
                new BasicKey(BasicKey.CODE_SPACE, null, null, "space", 4f),
                new BasicKey(BasicKey.CODE_ABC, null, null, "ABC", 1.5f),
                new BasicKey(BasicKey.CODE_ENTER, null, null, "\u23CE", 1.5f)
        });
    }

    private static BasicKey key(String letter) {
        String upper = letter.toUpperCase(java.util.Locale.US);
        return new BasicKey(BasicKey.CODE_NONE, letter, upper, null, 1f);
    }

    private static BasicKey sym(String s) {
        return new BasicKey(BasicKey.CODE_NONE, s, s, null, 1f);
    }
}