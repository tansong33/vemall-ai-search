package cn.vetech.aimall.service.suggestion;

import cn.vetech.aimall.model.dto.SuggestionItem;
import cn.vetech.aimall.model.search.DictionaryTerm;
import cn.vetech.aimall.repository.ProductCatalogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DictionarySuggestionSourceTest {
    private DictionarySuggestionSource source;

    @BeforeEach
    void setUp() {
        ProductCatalogRepository repository = mock(ProductCatalogRepository.class);
        when(repository.loadCategories()).thenReturn(Arrays.asList(
                new DictionaryTerm("C1", "保温杯", "水杯,杯子"),
                new DictionaryTerm("C2", "医用保温箱", "冷藏箱")));
        when(repository.loadBrands()).thenReturn(Collections.singletonList(
                new DictionaryTerm("B1", "膳魔师", "THERMOS,thermos中国")));
        source = new DictionarySuggestionSource(repository);
        source.refresh();
    }

    @Test
    void supportsPrefixInfixAndSuffixAssociation() {
        SuggestionItem prefix = first("保温");
        SuggestionItem infix = first("用保");
        SuggestionItem suffix = first("魔师");

        assertThat(prefix.getText()).isEqualTo("保温杯");
        assertThat(prefix.getMatchPosition()).isEqualTo("PREFIX");
        assertThat(infix.getText()).isEqualTo("医用保温箱");
        assertThat(infix.getMatchPosition()).isEqualTo("INFIX");
        assertThat(suffix.getText()).isEqualTo("膳魔师");
        assertThat(suffix.getMatchPosition()).isEqualTo("SUFFIX");
    }

    @Test
    void aliasMatchReturnsCanonicalText() {
        SuggestionItem item = first("therm");

        assertThat(item.getText()).isEqualTo("膳魔师");
        assertThat(item.getMatchedText()).isEqualTo("THERMOS");
        assertThat(item.getType()).isEqualTo("BRAND");
        assertThat(item.getMatchPosition()).isEqualTo("PREFIX");
    }

    private SuggestionItem first(String query) {
        List<SuggestionItem> items = source.suggest(query, null, null, 10);
        assertThat(items).isNotEmpty();
        return items.get(0);
    }
}
