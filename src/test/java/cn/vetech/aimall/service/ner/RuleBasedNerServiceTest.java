package cn.vetech.aimall.service.ner;

import cn.vetech.aimall.model.dto.IntentResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuleBasedNerServiceTest {

    private EntityDictionaryService dictionary;
    private RuleBasedNerService ner;

    @BeforeEach
    void setUp() {
        dictionary = mock(EntityDictionaryService.class);
        when(dictionary.matchCategory(anyString())).thenReturn(null);
        when(dictionary.matchBrand(anyString())).thenReturn(null);
        ner = new RuleBasedNerService(dictionary);
    }

    @Test
    void extractsScenesBudgetAndHardAttributes() {
        IntentResult result = ner.extract("夏天办公室降暑的员工福利，预算50元以内，要现货和专票");

        assertThat(result.getCategory()).isNull();
        assertThat(result.getBudgetMax()).isEqualByComparingTo(new BigDecimal("50"));
        assertThat(result.getScenes()).contains("夏季", "办公", "降暑", "员工福利");
        assertThat(result.getAttributes()).containsEntry("现货", "true")
                .containsEntry("可开专票", "true");
        assertThat(result.getSearchText()).doesNotContain("预算50", "以内");
        assertThat(result.getSearchText()).contains("夏季", "办公", "降暑", "员工福利");
    }

    @Test
    void extractsPriceRangeCapacityAndMaterial() {
        IntentResult result = ner.extract("找一个50到100元的500ml 316不锈钢保温杯");

        assertThat(result.getCategory()).isEqualTo("水杯");
        assertThat(result.getBudgetMin()).isEqualByComparingTo("50");
        assertThat(result.getBudgetMax()).isEqualByComparingTo("100");
        assertThat(result.getAttributes()).containsEntry("容量", "500ml")
                .containsEntry("材质", "316不锈钢");
    }

    @Test
    void usesDatabaseBrandDictionaryAndExactIdRoute() {
        when(dictionary.matchBrand(anyString())).thenReturn("膳魔师");
        IntentResult brand = ner.extract("膳魔师商务保温杯");
        IntentResult exact = ner.extract("商品编号：123");

        assertThat(brand.getBrand()).isEqualTo("膳魔师");
        assertThat(exact.getProductId()).isEqualTo(123L);
        assertThat(exact.getRoute()).isEqualTo("EXACT_ID");
    }
}
