package cn.vetech.ai.search.server.service.ner;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * BERT WordPiece 分词器
 *
 * 将文本编码为 ONNX 模型所需的 input_ids / attention_mask / token_type_ids，
 * 并记录每个 token 对应的原始文本 span，用于将模型输出映射回原始字符位置。
 *
 */
public class BertWordPieceTokenizer {

    private final Map<String, Integer> vocabulary;
    private final int unknownId;
    private final int clsId;
    private final int sepId;
    private final int padId;
    private final int maxLength;
    private final boolean characterLevel;

    public BertWordPieceTokenizer(Path vocabularyPath, int maxLength) throws IOException {
        this(vocabularyPath, maxLength, false);
    }

    public BertWordPieceTokenizer(Path vocabularyPath, int maxLength,
                                  boolean characterLevel) throws IOException {
        this.vocabulary = loadVocabulary(vocabularyPath);
        this.unknownId = requiredId("[UNK]");
        this.clsId = requiredId("[CLS]");
        this.sepId = requiredId("[SEP]");
        this.padId = requiredId("[PAD]");
        this.maxLength = Math.max(8, maxLength);
        this.characterLevel = characterLevel;
    }

    public Encoding encode(String text) {
        String input = text == null ? "" : text;
        List<Token> content = characterLevel ? characterTokenize(input) : basicTokenize(input);
        if (content.size() > maxLength - 2) {
            content = new ArrayList<Token>(content.subList(0, maxLength - 2));
        }

        long[][] inputIds = new long[1][maxLength];
        long[][] attentionMask = new long[1][maxLength];
        long[][] tokenTypeIds = new long[1][maxLength];
        boolean[][] labelMask = new boolean[1][maxLength];
        long[][][] offsetMapping = new long[1][maxLength][2];
        List<TokenSpan> spans = new ArrayList<TokenSpan>(maxLength);

        int position = 0;
        inputIds[0][position] = clsId;
        attentionMask[0][position] = 1;
        spans.add(TokenSpan.special());
        position++;
        for (Token token : content) {
            inputIds[0][position] = token.id;
            attentionMask[0][position] = 1;
            labelMask[0][position] = true;
            offsetMapping[0][position][0] = token.start;
            offsetMapping[0][position][1] = token.end;
            spans.add(new TokenSpan(token.start, token.end));
            position++;
        }
        inputIds[0][position] = sepId;
        attentionMask[0][position] = 1;
        spans.add(TokenSpan.special());
        position++;
        while (position < maxLength) {
            inputIds[0][position] = padId;
            spans.add(TokenSpan.special());
            position++;
        }
        return new Encoding(inputIds, attentionMask, tokenTypeIds, labelMask,
                offsetMapping, spans);
    }

    /**
     * RaNER 的 tokenizer_config.json 使用 is_split_into_words=true，
     * 因此推理时必须与训练保持一致：按 Unicode 字符切分，空格也作为 UNK 保留。
     */
    private List<Token> characterTokenize(String text) {
        List<Token> result = new ArrayList<Token>();
        int cursor = 0;
        while (cursor < text.length()) {
            int codePoint = text.codePointAt(cursor);
            int end = cursor + Character.charCount(codePoint);
            String token = new String(Character.toChars(codePoint)).toLowerCase(Locale.ROOT);
            Integer id = vocabulary.get(token);
            result.add(new Token(id == null ? unknownId : id, cursor, end));
            cursor = end;
        }
        return result;
    }

    private List<Token> basicTokenize(String text) {
        List<Token> result = new ArrayList<Token>();
        int cursor = 0;
        while (cursor < text.length()) {
            char current = text.charAt(cursor);
            if (Character.isWhitespace(current)) {
                cursor++;
                continue;
            }
            if (isCjk(current) || isPunctuation(current)) {
                String value = String.valueOf(current);
                result.add(new Token(vocabulary.containsKey(value) ? vocabulary.get(value) : unknownId,
                        cursor, cursor + 1));
                cursor++;
                continue;
            }

            int start = cursor;
            while (cursor < text.length()) {
                char c = text.charAt(cursor);
                if (Character.isWhitespace(c) || isCjk(c) || isPunctuation(c)) break;
                cursor++;
            }
            result.addAll(wordPiece(text.substring(start, cursor).toLowerCase(Locale.ROOT), start));
        }
        return result;
    }

    private List<Token> wordPiece(String word, int absoluteStart) {
        List<Token> pieces = new ArrayList<Token>();
        int start = 0;
        while (start < word.length()) {
            int end = word.length();
            Integer tokenId = null;
            int matchedEnd = -1;
            while (end > start) {
                String piece = word.substring(start, end);
                String candidate = start == 0 ? piece : "##" + piece;
                tokenId = vocabulary.get(candidate);
                if (tokenId != null) {
                    matchedEnd = end;
                    break;
                }
                end--;
            }
            if (tokenId == null) {
                pieces.clear();
                pieces.add(new Token(unknownId, absoluteStart, absoluteStart + word.length()));
                return pieces;
            }
            pieces.add(new Token(tokenId, absoluteStart + start, absoluteStart + matchedEnd));
            start = matchedEnd;
        }
        return pieces;
    }

    private int requiredId(String token) {
        Integer value = vocabulary.get(token);
        if (value == null) throw new IllegalArgumentException("Missing required vocabulary token: " + token);
        return value;
    }

    private static Map<String, Integer> loadVocabulary(Path path) throws IOException {
        Map<String, Integer> result = new HashMap<String, Integer>();
        BufferedReader reader = null;
        try {
            reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
            String line;
            int id = 0;
            while ((line = reader.readLine()) != null) {
                result.put(line.trim(), id++);
            }
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (IOException ignored) { }
            }
        }
        return result;
    }

    private static boolean isCjk(char value) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(value);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A;
    }

    private static boolean isPunctuation(char value) {
        int type = Character.getType(value);
        return type == Character.CONNECTOR_PUNCTUATION
                || type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION
                || type == Character.END_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION
                || type == Character.FINAL_QUOTE_PUNCTUATION;
    }

    private static final class Token {
        private final int id;
        private final int start;
        private final int end;

        private Token(int id, int start, int end) {
            this.id = id;
            this.start = start;
            this.end = end;
        }
    }

    public static final class TokenSpan {
        private final int start;
        private final int end;

        private TokenSpan(int start, int end) {
            this.start = start;
            this.end = end;
        }

        public static TokenSpan special() { return new TokenSpan(-1, -1); }
        public int getStart() { return start; }
        public int getEnd() { return end; }
        public boolean isSpecial() { return start < 0; }
    }

    public static final class Encoding {
        private final long[][] inputIds;
        private final long[][] attentionMask;
        private final long[][] tokenTypeIds;
        private final boolean[][] labelMask;
        private final long[][][] offsetMapping;
        private final List<TokenSpan> spans;

        private Encoding(long[][] inputIds, long[][] attentionMask, long[][] tokenTypeIds,
                         boolean[][] labelMask, long[][][] offsetMapping,
                         List<TokenSpan> spans) {
            this.inputIds = inputIds;
            this.attentionMask = attentionMask;
            this.tokenTypeIds = tokenTypeIds;
            this.labelMask = labelMask;
            this.offsetMapping = offsetMapping;
            this.spans = spans;
        }

        public long[][] getInputIds() { return inputIds; }
        public long[][] getAttentionMask() { return attentionMask; }
        public long[][] getTokenTypeIds() { return tokenTypeIds; }
        public boolean[][] getLabelMask() { return labelMask; }
        public long[][][] getOffsetMapping() { return offsetMapping; }
        public List<TokenSpan> getSpans() { return spans; }
    }
}
