package com.tsong.aisearch.service.ner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

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
}
