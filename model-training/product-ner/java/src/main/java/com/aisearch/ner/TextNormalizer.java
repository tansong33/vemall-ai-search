package com.aisearch.ner;

/**
 * 字符级归一化 —— 必须和 Python 的 src/nerkit/text_norm.py 逐字符一致。
 *
 * <p><b>铁律：只做 1:1 替换，输出长度必须等于输入长度。</b>
 * 任何会改变长度的操作（比如 NFKC 会把 "㍿" 展开成多个字符）都会让所有已存的
 * offset 失效，前端高亮和 ES 过滤会整体错位。
 *
 * <p>一致性由 {@code fixture_normalization.json} 在单元测试里强制保证。
 */
public final class TextNormalizer {

    private TextNormalizer() {}

    private static final int FULLWIDTH_OFFSET = 0xFEE0;
    private static final char FW_START = 0xFF01;
    private static final char FW_END = 0xFF5E;

    public static String normalize(String text) {
        if (text == null) return "";
        char[] out = new char[text.length()];
        for (int i = 0; i < text.length(); i++) {
            out[i] = normalizeChar(text.charAt(i));
        }
        String result = new String(out);
        if (result.length() != text.length()) {   // 理论上不可能，留作断言
            throw new IllegalStateException("normalisation changed length; offsets would break");
        }
        return result;
    }

    static char normalizeChar(char ch) {
        if (isSpaceLike(ch) || isZeroWidth(ch)) {
            return ' ';
        }
        if (ch >= FW_START && ch <= FW_END) {
            ch = (char) (ch - FULLWIDTH_OFFSET);
        }
        // 只小写 ASCII：Python 的 str.lower() 与 Java 的 Character.toLowerCase()
        // 在少数码点上结果不同，限定 A-Z 才能保证两端完全一致。
        if (ch >= 'A' && ch <= 'Z') {
            ch = (char) (ch + 32);
        }
        return ch;
    }

    private static boolean isSpaceLike(char ch) {
        return ch == '\u00a0' || (ch >= '\u2000' && ch <= '\u200a')
                || ch == '\u202f' || ch == '\u205f' || ch == '\u3000';
    }

    private static boolean isZeroWidth(char ch) {
        return ch == '\u200b' || ch == '\u200c' || ch == '\u200d' || ch == '\ufeff';
    }
}
