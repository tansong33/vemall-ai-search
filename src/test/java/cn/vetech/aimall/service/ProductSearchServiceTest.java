package cn.vetech.aimall.service;

import cn.vetech.aimall.config.AiMallProperties;
import cn.vetech.aimall.model.dto.IntentResult;
import cn.vetech.aimall.model.entity.Product;
import cn.vetech.aimall.repository.ProductCatalogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductSearchServiceTest {

    private ProductCatalogRepository repository;
    private ProductSearchService service;

    @BeforeEach
    void setUp() {
        repository = mock(ProductCatalogRepository.class);
        service = new ProductSearchService(repository, new AiMallProperties());
    }

    @Test
    void exactIdUsesPrimaryKeyRoute() {
        Product product = new Product();
        product.setId("123");
        product.setStock(BigDecimal.TEN);
        when(repository.findExact(org.mockito.ArgumentMatchers.eq("123"), any())).thenReturn(product);
        IntentResult intent = new IntentResult();
        intent.setProductId("123");

        DbSearchResult result = service.search(intent);

        assertThat(result.getRoute()).isEqualTo("EXACT_ID");
        assertThat(result.getProducts()).containsExactly(product);
        verify(repository, never()).search(any());
    }

    @Test
    void priceOnlyQueryDoesNotFallBackToBroadTableScan() {
        IntentResult intent = new IntentResult();
        intent.setBudgetMax(new BigDecimal("50"));

        DbSearchResult result = service.search(intent);

        assertThat(result.getRoute()).isEqualTo("NEED_MORE_INFO");
        assertThat(result.getProducts()).isEmpty();
        verify(repository, never()).searchStructured(any());
    }
}
