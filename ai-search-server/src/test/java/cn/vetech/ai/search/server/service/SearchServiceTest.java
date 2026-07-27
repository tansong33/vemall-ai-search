package cn.vetech.ai.search.server.service;

import cn.vetech.ai.search.fccapi.api.search.SearchRequest;
import cn.vetech.ai.search.server.dao.ProductSearchDao;
import cn.vetech.ai.search.server.dao.SearchCacheDao;
import cn.vetech.ai.search.server.service.dto.NerEntityDto;
import cn.vetech.ai.search.server.service.dto.QueryUnderstandDto;
import cn.vetech.ai.search.server.service.dto.SearchQueryDto;
import cn.vetech.ai.search.server.service.ner.NerRecognizer;
import cn.vetech.ai.search.server.service.query.QueryUnderstandingService;
import cn.vetech.ai.search.server.service.vo.SearchItemVo;
import cn.vetech.ai.search.server.service.vo.SearchResultVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SearchServiceTest {

    private NerRecognizer nerRecognizer;
    private QueryUnderstandingService queryUnderstanding;
    private ProductSearchDao productSearchDao;
    private SearchCacheDao cacheDao;
    private SearchService service;

    @BeforeEach
    void setUp() {
        nerRecognizer = Mockito.mock(NerRecognizer.class);
        queryUnderstanding = Mockito.mock(QueryUnderstandingService.class);
        productSearchDao = Mockito.mock(ProductSearchDao.class);
        cacheDao = Mockito.mock(SearchCacheDao.class);

        Mockito.when(queryUnderstanding.process(Mockito.anyString()))
                .thenReturn(new QueryUnderstandDto());
        Mockito.when(cacheDao.buildSearchResultKey(Mockito.anyString(), Mockito.anyMap(), Mockito.anyInt()))
                .thenReturn("search:result:key");
        Mockito.when(productSearchDao.search(Mockito.any(SearchQueryDto.class)))
                .thenReturn(resultWith("sku1"));

        service = new SearchService(nerRecognizer, queryUnderstanding,
                productSearchDao, cacheDao, new SpuDeduplicator());
    }

    @Test
    void returnsResultAndFlagsDegradeWhenRedisUnavailable() {
        Mockito.when(cacheDao.getHotQuery(Mockito.anyString()))
                .thenThrow(new IllegalStateException("redis down"));
        Mockito.when(cacheDao.getSearchResult(Mockito.anyString()))
                .thenThrow(new IllegalStateException("redis down"));
        // dao 按约定不抛异常，用返回 false 表示 Redis 不可用
        Mockito.when(cacheDao.putSearchResult(Mockito.anyString(), Mockito.any(SearchResultVo.class)))
                .thenReturn(false);
        Mockito.when(nerRecognizer.recognize(Mockito.anyString()))
                .thenReturn(Collections.<NerEntityDto>emptyList());

        DegradeContext degrade = new DegradeContext();
        SearchResultVo result = service.search(query("手机"), degrade);

        assertThat(result.getItems()).isNotEmpty();
        assertThat(degrade.getReasons()).contains("REDIS_UNAVAILABLE");
    }

    @Test
    void fallsBackToFullTextWhenNerFails() {
        Mockito.when(nerRecognizer.recognize(Mockito.anyString()))
                .thenThrow(new IllegalStateException("onnx exploded"));

        DegradeContext degrade = new DegradeContext();
        SearchQueryDto query = query("手机");
        SearchResultVo result = service.search(query, degrade);

        assertThat(result.getItems()).isNotEmpty();
        assertThat(query.getEntities()).isEmpty();
        assertThat(degrade.getReasons()).contains("NER_UNAVAILABLE");
    }

    @Test
    void cacheKeyDimensionsIncludeSortAndPageSize() {
        SearchQueryDto query = query("手机");
        query.setSort(SearchRequest.Sort.PRICE_ASC);
        query.setPageSize(50);

        service.buildCacheKey(query);

        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        Mockito.verify(cacheDao).buildSearchResultKey(Mockito.eq("手机"), captor.capture(), Mockito.eq(1));
        assertThat(captor.getValue())
                .containsEntry("sort", "PRICE_ASC")
                .containsEntry("pageSize", "50");
    }

    @Test
    void hotQueryCacheIsSlicedByPage() {
        SearchResultVo hot = new SearchResultVo();
        hot.setItems(new ArrayList<>(Arrays.asList(item("a"), item("b"), item("c"))));
        Mockito.when(cacheDao.getHotQuery("手机")).thenReturn(hot);

        SearchQueryDto query = query("手机");
        query.setPage(2);
        query.setPageSize(2);
        DegradeContext degrade = new DegradeContext();

        SearchResultVo result = service.search(query, degrade);

        assertThat(result.getItems()).extracting(SearchItemVo::getSkuId).containsExactly("c");
        assertThat(degrade.getCacheStatus()).isEqualTo("HIT");
        Mockito.verify(productSearchDao, Mockito.never()).search(Mockito.any(SearchQueryDto.class));
    }

    private static SearchQueryDto query(String text) {
        SearchQueryDto query = new SearchQueryDto();
        query.setQuery(text);
        return query;
    }

    private static SearchResultVo resultWith(String skuId) {
        SearchResultVo result = new SearchResultVo();
        result.setItems(new ArrayList<>(Collections.singletonList(item(skuId))));
        result.setTotal(1);
        result.setRawTotal(1);
        return result;
    }

    private static SearchItemVo item(String skuId) {
        SearchItemVo item = new SearchItemVo();
        item.setSkuId(skuId);
        return item;
    }
}
