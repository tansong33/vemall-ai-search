package cn.vetech.aimall.service.ner;

import cn.vetech.aimall.config.AiMallProperties;
import cn.vetech.aimall.model.dto.IntentResult;
import cn.vetech.aimall.model.dto.NerEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HybridIntentRecognizerTest {

    private AiMallProperties properties;
    private RuleBasedNerService rule;

    @BeforeEach
    void setUp() {
        properties = new AiMallProperties();
        EntityDictionaryService dictionary = mock(EntityDictionaryService.class);
        when(dictionary.matchCategory(anyString())).thenReturn(null);
        when(dictionary.matchBrand(anyString())).thenReturn(null);
        rule = new RuleBasedNerService(dictionary);
    }

    @Test
    void hybridAddsAcceptedModelEntitiesButKeepsRuleBudget() {
        properties.getNer().setMode("hybrid");
        NerModelClient client = fixedClient(new NerModelOutput("minirbt-test-v1", 3, Arrays.asList(
                new NerEntity(0, 3, "膳魔师", "BRAND", 0.98, "test"),
                new NerEntity(3, 6, "保温杯", "PRODUCT_TYPE", 0.90, "test"))));
        HybridIntentRecognizer recognizer = recognizer(client);

        IntentResult result = recognizer.extract("膳魔师保温杯50元以内");

        assertThat(result.getBudgetMax()).isEqualByComparingTo("50");
        assertThat(result.getBrand()).isEqualTo("膳魔师");
        assertThat(result.getKeywords()).contains("膳魔师", "保温杯");
        assertThat(result.getNerSource()).isEqualTo("HYBRID");
        assertThat(result.getModelVersion()).isEqualTo("minirbt-test-v1");
    }

    @Test
    void rejectsLowConfidenceOrInvalidSpan() {
        properties.getNer().setMode("hybrid");
        NerModelClient client = fixedClient(new NerModelOutput("bad-v1", 1, Arrays.asList(
                new NerEntity(0, 2, "华为", "BRAND", 0.50, "test"),
                new NerEntity(2, 20, "越界", "PRODUCT_TYPE", 0.99, "test"))));

        IntentResult result = recognizer(client).extract("华为耳机");

        assertThat(result.getBrand()).isNull();
        assertThat(result.getModelEntities()).isEmpty();
    }

    @Test
    void modelFailureFallsBackWithoutBreakingSearch() {
        properties.getNer().setMode("model");
        NerModelClient failing = new NerModelClient() {
            @Override public NerModelOutput predict(String normalizedQuery) { throw new IllegalStateException("boom"); }
            @Override public boolean isReady() { return true; }
            @Override public String provider() { return "test"; }
            @Override public String modelVersion() { return "failing-v1"; }
        };

        IntentResult result = recognizer(failing).extract("预算80元以内的保温杯");

        assertThat(result.getNerSource()).isEqualTo("RULE_FALLBACK");
        assertThat(result.getBudgetMax()).isEqualByComparingTo("80");
        assertThat(result.getCategory()).isEqualTo("水杯");
    }

    @Test
    void exactIdNeverCallsModel() {
        properties.getNer().setMode("hybrid");
        NerModelClient failing = new NerModelClient() {
            @Override public NerModelOutput predict(String normalizedQuery) { throw new AssertionError("must not run"); }
            @Override public boolean isReady() { return true; }
            @Override public String provider() { return "test"; }
            @Override public String modelVersion() { return "test-v1"; }
        };

        IntentResult result = recognizer(failing).extract("商品编号123");

        assertThat(result.getProductId()).isEqualTo("123");
        assertThat(result.getNerSource()).isEqualTo("RULE");
    }

    private HybridIntentRecognizer recognizer(NerModelClient client) {
        return new HybridIntentRecognizer(rule, client, new NerConfidencePolicy(properties), properties);
    }

    private NerModelClient fixedClient(final NerModelOutput output) {
        return new NerModelClient() {
            @Override public NerModelOutput predict(String normalizedQuery) { return output; }
            @Override public boolean isReady() { return true; }
            @Override public String provider() { return "test"; }
            @Override public String modelVersion() { return output.getModelVersion(); }
        };
    }
}
