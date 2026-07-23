package cn.vetech.aimall.service.ner;

import cn.vetech.aimall.model.search.DictionaryTerm;
import cn.vetech.aimall.repository.ProductCatalogRepository;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EntityDictionaryServiceTest {
    @Test
    void matchesAliasAndResolvesCanonicalDatabaseId() {
        ProductCatalogRepository repository = mock(ProductCatalogRepository.class);
        when(repository.loadCategories()).thenReturn(Collections.singletonList(
                new DictionaryTerm("0250020003", "保温杯", "水杯,杯子")));
        when(repository.loadBrands()).thenReturn(Collections.singletonList(
                new DictionaryTerm("BRAND-1", "膳魔师", "THERMOS,thermos中国")));
        EntityDictionaryService dictionary = new EntityDictionaryService(repository);

        dictionary.refresh();

        assertThat(dictionary.matchCategory("想买一个水杯")).isEqualTo("保温杯");
        assertThat(dictionary.resolveCategory("水杯").getId()).isEqualTo("0250020003");
        assertThat(dictionary.matchBrand("thermos保温杯")).isEqualTo("膳魔师");
        assertThat(dictionary.resolveBrand("thermos").getId()).isEqualTo("BRAND-1");
    }
}
