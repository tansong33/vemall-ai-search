package cn.vetech.ai.search.server.service.ner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for BERT WordPiece tokenization.
 *
 * Ported from ai-search-dev. Verifies Chinese character handling and
 * WordPiece sub-word offset tracking for ONNX NER model input.
 *
 * NOTE: BertWordPieceTokenizer needs to be created in package
 * cn.vetech.ai.search.server.ner — port from ai-search-dev as-is.
 */
class BertWordPieceTokenizerTest {

    @TempDir
    Path tempDirectory;

    @Test
    void preservesChineseAndWordPieceCharacterOffsets() throws Exception {
        Path vocabulary = tempDirectory.resolve("vocab.txt");
        Files.write(vocabulary, Arrays.asList(
                "[PAD]", "[UNK]", "[CLS]", "[SEP]", "华", "为", "mate", "##60"
        ), StandardCharsets.UTF_8);
        BertWordPieceTokenizer tokenizer = new BertWordPieceTokenizer(vocabulary, 10);

        BertWordPieceTokenizer.Encoding encoding = tokenizer.encode("华为 Mate60");

        assertThat(encoding.getInputIds()[0]).startsWith(2, 4, 5, 6, 7, 3);
        assertThat(encoding.getSpans().get(1).getStart()).isZero();
        assertThat(encoding.getSpans().get(2).getEnd()).isEqualTo(2);
        assertThat(encoding.getSpans().get(3).getStart()).isEqualTo(3);
        assertThat(encoding.getSpans().get(4).getEnd()).isEqualTo(9);
    }

    @Test
    void ranerModeTokenizesByCharacterAndKeepsWhitespaceOffsets() throws Exception {
        Path vocabulary = tempDirectory.resolve("raner-vocab.txt");
        Files.write(vocabulary, Arrays.asList(
                "[PAD]", "[UNK]", "[CLS]", "[SEP]", "e", "h"
        ), StandardCharsets.UTF_8);
        BertWordPieceTokenizer tokenizer =
                new BertWordPieceTokenizer(vocabulary, 8, true);

        BertWordPieceTokenizer.Encoding encoding = tokenizer.encode("eh ");

        assertThat(encoding.getInputIds()[0]).startsWith(2, 4, 5, 1, 3);
        assertThat(encoding.getLabelMask()[0])
                .containsExactly(false, true, true, true, false, false, false, false);
        assertThat(encoding.getSpans().get(3).getStart()).isEqualTo(2);
        assertThat(encoding.getSpans().get(3).getEnd()).isEqualTo(3);
    }
}
