package cn.vetech.ai.search.server.service.ner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.vetech.ai.search.server.config.NerProperties;
import cn.vetech.ai.search.server.model.dto.NerEntity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 跨语言一致性测试：断言 Java 侧输出与 Python 训练流水线逐字符一致。
 *
 * <p>fixture 由 model-training/product-ner/scripts/make_java_bundle.py 生成，
 * 断言的是**线上真正跑的那条路径** —— {@link OnnxNerModelClient} +
 * {@link BertWordPieceTokenizer}，而不是任何独立的参考实现。
 *
 * <p>运行方式（不带 -Dner.bundle 时整个类自动跳过，本地和 CI 不会因为缺 bundle 变红）：
 * <pre>{@code
 * mvn test -Dner.bundle=/path/to/ner-v1/onnx
 * }</pre>
 *
 * <p>这不是"锦上添花"的测试：归一化差一个字符、BIO 解码差一条规则，线上就会出现
 * offset 错位或实体丢失，而且极难排查。参见
 * model-training/product-ner/docs/java_integration.md。
 */
class NerParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Path bundle;
    private static OnnxNerModelClient client;
    private static BertWordPieceTokenizer tokenizer;

    @BeforeAll
    static void loadBundle() throws Exception {
        String configured = System.getProperty("ner.bundle");
        assumeTrue(configured != null && !configured.trim().isEmpty(),
                "跳过：未指定 -Dner.bundle=<bundle 目录>");
        bundle = Paths.get(configured);
        assumeTrue(Files.isDirectory(bundle), "跳过：bundle 目录不存在 " + bundle);
        assumeTrue(Files.isRegularFile(bundle.resolve("model.onnx")),
                "跳过：缺 model.onnx，先跑 scripts/export_onnx.py");

        JsonNode manifest = readJson(bundle.resolve("ner_manifest.json"));

        NerProperties properties = new NerProperties();
        NerProperties.Model config = properties.getModel();
        config.setEnabled(true);
        // fixture 由 fp32 图生成，这里必须用同一个图；int8 的数值差异属于量化验收范畴，
        // 由 Python 侧的 verify_onnx.py 负责。
        config.setModelPath(bundle.resolve("model.onnx").toString());
        config.setVocabularyPath(bundle.resolve("vocab.txt").toString());
        config.setLabelsPath(bundle.resolve("labels.json").toString());
        config.setMaxLength(manifest.path("max_length").asInt(64));
        config.setConfidenceThreshold(manifest.path("decode").path("tau_accept").asDouble(0.60));

        client = new OnnxNerModelClient(properties, MAPPER);
        client.initialize();
        assertThat(client.isReady())
                .as("bundle 加载失败，检查 model.onnx / vocab.txt / labels.json 是否齐全")
                .isTrue();

        tokenizer = new BertWordPieceTokenizer(
                bundle.resolve("vocab.txt"), config.getMaxLength());
    }

    @AfterAll
    static void closeClient() {
        if (client != null) {
            client.closeSession();
        }
    }

    /** 核心验收项：端到端实体输出必须与 Python 完全一致。 */
    @Test
    void endToEndDecodingMatchesPythonFixture() throws Exception {
        Path fixture = bundle.resolve("fixture_decode.json");
        assumeTrue(Files.isRegularFile(fixture),
                "跳过：缺 fixture_decode.json，生成时需要给 make_java_bundle.py 传 --data");

        for (JsonNode row : readJson(fixture)) {
            String query = row.get("query").asText();
            JsonNode expected = row.get("entities");
            List<NerEntity> actual = client.predict(query).getEntities();

            assertThat(actual)
                    .as("实体数量不一致，query=%s", query)
                    .hasSize(expected.size());
            for (int i = 0; i < expected.size(); i++) {
                JsonNode e = expected.get(i);
                NerEntity a = actual.get(i);
                assertThat(a.getStart()).as("start 不一致，query=%s", query)
                        .isEqualTo(e.get("start").asInt());
                assertThat(a.getEnd()).as("end 不一致，query=%s", query)
                        .isEqualTo(e.get("end").asInt());
                assertThat(a.getLabel()).as("label 不一致，query=%s", query)
                        .isEqualTo(e.get("label").asText());
                assertThat(a.getText()).as("实体文本不一致，query=%s", query)
                        .isEqualTo(e.get("text").asText());
                assertThat(a.getConfidence()).as("confidence 不一致，query=%s", query)
                        .isCloseTo(e.get("confidence").asDouble(), org.assertj.core.data.Offset.offset(1e-3));
            }
        }
    }

    /** offset 必须索引原始 query，否则前端高亮和 ES 过滤都会错位。 */
    @Test
    void offsetsIndexTheOriginalQuery() throws Exception {
        for (String query : probeTexts()) {
            for (NerEntity entity : client.predict(query).getEntities()) {
                assertThat(entity.getStart()).isGreaterThanOrEqualTo(0);
                assertThat(entity.getEnd()).isLessThanOrEqualTo(query.length());
                assertThat(query.substring(entity.getStart(), entity.getEnd()))
                        .as("offset 没有索引到原串，query=%s", query)
                        .isEqualTo(entity.getText());
            }
        }
    }

    /**
     * token 的 offset 不允许越出输入串。
     *
     * <p>这条专门盯 {@link BertWordPieceTokenizer#basicTokenize} 里的
     * {@code toLowerCase(Locale.ROOT)}：少数码点（U+0130 等）小写后会变成两个字符，
     * 而 span 是按小写串的下标算、按原串来解释的，长度一变 offset 就整体漂移。
     * Python 侧刻意只小写 A-Z 来规避这个问题（见 nerkit/text_norm.py 的注释）。
     */
    @Test
    void tokenizerOffsetsNeverEscapeTheInput() throws Exception {
        List<String> probes = new ArrayList<>(probeTexts());
        probes.add("İSTANBUL 保温杯");   // LATIN CAPITAL LETTER I WITH DOT ABOVE
        probes.add("ÅNGSTRÖM 3M");  // 带变音符的拉丁字母

        for (String probe : probes) {
            BertWordPieceTokenizer.Encoding encoding = tokenizer.encode(probe);
            for (BertWordPieceTokenizer.TokenSpan span : encoding.getSpans()) {
                if (span.isSpecial()) {
                    continue;
                }
                assertThat(span.getStart())
                        .as("token start 为负，probe=%s", probe).isGreaterThanOrEqualTo(0);
                assertThat(span.getEnd())
                        .as("token end 越出输入长度，probe=%s", probe)
                        .isLessThanOrEqualTo(probe.length());
                assertThat(span.getStart())
                        .as("token span 为空或反向，probe=%s", probe)
                        .isLessThan(span.getEnd());
            }
        }
    }

    /**
     * 归一化一致性。
     *
     * <p>Python 侧在训练和推理前都会跑 {@code nerkit.text_norm.normalize_text}
     * （全角→半角、零宽→空格、只小写 A-Z，严格等长）。backend 目前**没有**这一步，
     * 原始 query 直接进 WordPiece。两端只要有一端归一化、另一端不归一化，就不是同一个函数。
     *
     * <p>本测试断言两种写法应当分词成同一串 token id。**在 backend 补上等长归一化之前
     * 它预期会失败** —— 这是有意保留的：一个红着的测试比一个不存在的测试安全得多。
     * 修法见 model-training/product-ner/docs/java_integration.md §3。
     */
    @Test
    void rawAndNormalisedFormsTokeniseIdentically() throws Exception {
        Path fixture = bundle.resolve("fixture_normalization.json");
        assumeTrue(Files.isRegularFile(fixture), "跳过：缺 fixture_normalization.json");

        for (JsonNode row : readJson(fixture)) {
            String raw = row.get("raw").asText();
            String normalized = row.get("normalized").asText();

            assertThat(normalized.length())
                    .as("fixture 自身违反等长约定，raw=%s", raw)
                    .isEqualTo(raw.length());

            assertThat(tokenizer.encode(raw).getInputIds()[0])
                    .as("backend 未做等长归一化：%s 与 %s 分词结果不同", raw, normalized)
                    .isEqualTo(tokenizer.encode(normalized).getInputIds()[0]);
        }
    }

    private static List<String> probeTexts() throws Exception {
        List<String> texts = new ArrayList<>();
        Path fixture = bundle.resolve("fixture_decode.json");
        if (Files.isRegularFile(fixture)) {
            for (JsonNode row : readJson(fixture)) {
                texts.add(row.get("query").asText());
            }
        }
        if (texts.isEmpty()) {
            texts.add("华为手机 256G 黑色");
            texts.add("公牛插座");
        }
        return texts;
    }

    private static JsonNode readJson(Path path) throws Exception {
        return MAPPER.readTree(Files.newBufferedReader(path, java.nio.charset.StandardCharsets.UTF_8));
    }
}
