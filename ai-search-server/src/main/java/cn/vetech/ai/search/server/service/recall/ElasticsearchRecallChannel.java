package cn.vetech.ai.search.server.service.recall;

import cn.vetech.ai.search.fccapi.api.search.SearchRequest;
import cn.vetech.ai.search.server.dao.ProductSearchDao;
import cn.vetech.ai.search.server.model.dto.ModelResult;
import cn.vetech.ai.search.server.model.dto.NerEntity;
import cn.vetech.ai.search.server.model.dto.SearchResult;
import cn.vetech.ai.search.server.service.dto.NerEntityDto;
import cn.vetech.ai.search.server.service.dto.SearchQueryDto;
import cn.vetech.ai.search.server.service.vo.FacetsVo;
import cn.vetech.ai.search.server.service.vo.SearchItemVo;
import cn.vetech.ai.search.server.service.vo.SearchResultVo;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class ElasticsearchRecallChannel implements RecallChannel {

    private final ProductSearchDao productSearchDao;

    public ElasticsearchRecallChannel(ProductSearchDao productSearchDao) {
        this.productSearchDao = productSearchDao;
    }

    @Override
    public String name() {
        return "elasticsearch";
    }

    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public SearchResult recall(String query, ModelResult modelResult, List<NerEntity> nerEntities, String sort,
                               Map<String, Object> filters) {
        SearchQueryDto searchQuery = new SearchQueryDto();
        searchQuery.setQuery(query);
        if (modelResult != null) {
            searchQuery.setRewrittenQuery(modelResult.getRewrittenQuery());
            searchQuery.setSynonyms(modelResult.getSynonyms());
        }
        searchQuery.setEntities(toEntities(nerEntities));
        searchQuery.setSort(toSort(sort));
        searchQuery.setFilters(toFilters(filters));
        return toLegacyResult(productSearchDao.search(searchQuery));
    }

    private List<NerEntityDto> toEntities(List<NerEntity> entities) {
        List<NerEntityDto> result = new ArrayList<NerEntityDto>();
        if (entities == null) {
            return result;
        }
        for (NerEntity entity : entities) {
            if (entity == null) {
                continue;
            }
            NerEntityDto item = new NerEntityDto();
            item.setText(entity.getText());
            item.setLabel(entity.getLabel());
            item.setStart(entity.getStart());
            item.setEnd(entity.getEnd());
            item.setConfidence(entity.getConfidence() == null
                    ? null : entity.getConfidence().floatValue());
            result.add(item);
        }
        return result;
    }

    private SearchRequest.Sort toSort(String sort) {
        if (sort == null || "default".equalsIgnoreCase(sort)) {
            return SearchRequest.Sort.RELEVANCE;
        }
        try {
            return SearchRequest.Sort.valueOf(sort.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return SearchRequest.Sort.RELEVANCE;
        }
    }

    private SearchRequest.Filters toFilters(Map<String, Object> values) {
        SearchRequest.Filters filters = new SearchRequest.Filters();
        if (values == null) {
            return filters;
        }
        filters.setBrands(strings(values.get("brands")));
        filters.setCategories(strings(values.get("categories")));
        filters.setMinPriceFen(longValue(values.get("minPriceFen")));
        filters.setMaxPriceFen(longValue(values.get("maxPriceFen")));
        if (values.get("inStock") instanceof Boolean) {
            filters.setInStock((Boolean) values.get("inStock"));
        } else if (values.get("inStock") != null) {
            filters.setInStock(Boolean.valueOf(String.valueOf(values.get("inStock"))));
        }
        return filters;
    }

    private List<String> strings(Object value) {
        List<String> result = new ArrayList<String>();
        if (!(value instanceof Collection)) {
            return result;
        }
        for (Object item : (Collection<?>) value) {
            if (item != null) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    private Long longValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private SearchResult toLegacyResult(SearchResultVo source) {
        SearchResult result = new SearchResult();
        result.setTotal(source.getTotal());
        result.setCostMs(source.getCostMs());

        List<SearchResult.ProductItem> products = new ArrayList<SearchResult.ProductItem>();
        if (source.getItems() != null) {
            for (SearchItemVo sourceItem : source.getItems()) {
                SearchResult.ProductItem item = new SearchResult.ProductItem();
                item.setId(sourceItem.getSkuId());
                item.setTitle(sourceItem.getTitle());
                item.setHighlightTitle(sourceItem.getHighlightTitle());
                item.setBrand(sourceItem.getBrandName());
                item.setCategory(sourceItem.getCategoryName());
                item.setImage(sourceItem.getImageUrl());
                item.setPrice(sourceItem.getPriceFen() == null
                        ? 0.0 : sourceItem.getPriceFen() / 100.0);
                item.setScore(sourceItem.getScore() == null ? 0.0f : sourceItem.getScore());
                products.add(item);
            }
        }
        result.setProducts(products);

        SearchResult.AggregationResult aggregations = new SearchResult.AggregationResult();
        if (source.getFacets() != null) {
            aggregations.setBrands(toBuckets(source.getFacets().getBrands()));
            aggregations.setCategories(toBuckets(source.getFacets().getCategories()));
        }
        result.setAggregations(aggregations);
        return result;
    }

    private List<SearchResult.AggBucket> toBuckets(List<FacetsVo.Bucket> source) {
        List<SearchResult.AggBucket> result = new ArrayList<SearchResult.AggBucket>();
        if (source == null) {
            return result;
        }
        for (FacetsVo.Bucket bucket : source) {
            result.add(new SearchResult.AggBucket(bucket.getKey(), bucket.getCount()));
        }
        return result;
    }
}
