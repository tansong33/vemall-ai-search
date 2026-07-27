package cn.vetech.ai.search.rest.assembler;

import cn.vetech.ai.search.fccapi.api.search.SearchRequest;
import cn.vetech.ai.search.fccapi.api.search.SearchResponse;
import cn.vetech.ai.search.server.config.SearchProperties;
import cn.vetech.ai.search.server.service.DegradeContext;
import cn.vetech.ai.search.server.service.dto.SearchQueryDto;
import cn.vetech.ai.search.server.service.vo.FacetsVo;
import cn.vetech.ai.search.server.service.vo.SearchItemVo;
import cn.vetech.ai.search.server.service.vo.SearchResultVo;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** fccapi 契约与 server dto/vo 之间的字段搬运，无业务逻辑。 */
@Component
public class SearchAssembler {

    private final SearchProperties properties;

    public SearchAssembler(SearchProperties properties) {
        this.properties = properties;
    }

    public SearchQueryDto toDto(SearchRequest request) {
        SearchQueryDto dto = new SearchQueryDto();
        dto.setQuery(request.getQuery() == null ? "" : request.getQuery().trim());
        dto.setSort(request.getSort() == null ? SearchRequest.Sort.RELEVANCE : request.getSort());
        dto.setFilters(request.getFilters());
        dto.setPage(request.getPage() == null || request.getPage() < 1 ? 1 : request.getPage());
        dto.setPageSize(normalizePageSize(request.getPageSize()));
        return dto;
    }

    private int normalizePageSize(Integer requested) {
        SearchProperties.Search search = properties.getSearch();
        if (requested == null || requested < 1) {
            return search.getDefaultPageSize();
        }
        return Math.min(requested, search.getMaxPageSize());
    }

    public SearchResponse toResponse(SearchResultVo vo, SearchQueryDto query,
                                     DegradeContext degrade) {
        SearchResponse response = new SearchResponse();
        response.setTotal(vo.getTotal());
        response.setRawTotal(vo.getRawTotal());
        response.setPage(query.getPage());
        response.setPageSize(query.getPageSize());
        response.setItems(toItems(vo.getItems()));
        response.setFacets(toFacets(vo.getFacets()));
        response.setTookMs(vo.getCostMs());
        response.setCacheStatus(degrade.getCacheStatus());
        response.setDegraded(degrade.isDegraded());
        response.setDegradeReasons(new ArrayList<String>(degrade.getReasons()));
        return response;
    }

    private List<SearchResponse.Item> toItems(List<SearchItemVo> items) {
        List<SearchResponse.Item> result = new ArrayList<SearchResponse.Item>();
        if (items == null) {
            return result;
        }
        for (SearchItemVo vo : items) {
            SearchResponse.Item item = new SearchResponse.Item();
            item.setSkuId(vo.getSkuId());
            item.setSpuId(vo.getSpuId());
            item.setTitle(vo.getTitle());
            item.setHighlightTitle(vo.getHighlightTitle());
            item.setBrandName(vo.getBrandName());
            item.setCategoryName(vo.getCategoryName());
            item.setImageUrl(vo.getImageUrl());
            item.setPriceFen(vo.getPriceFen());
            item.setInStock(vo.getInStock());
            item.setScore(vo.getScore());
            result.add(item);
        }
        return result;
    }

    private SearchResponse.Facets toFacets(FacetsVo vo) {
        SearchResponse.Facets facets = new SearchResponse.Facets();
        if (vo == null) {
            return facets;
        }
        facets.setBrands(toBuckets(vo.getBrands()));
        facets.setCategories(toBuckets(vo.getCategories()));
        return facets;
    }

    private List<SearchResponse.Facets.Bucket> toBuckets(List<FacetsVo.Bucket> buckets) {
        List<SearchResponse.Facets.Bucket> result = new ArrayList<SearchResponse.Facets.Bucket>();
        if (buckets == null) {
            return result;
        }
        for (FacetsVo.Bucket vo : buckets) {
            SearchResponse.Facets.Bucket bucket = new SearchResponse.Facets.Bucket();
            bucket.setKey(vo.getKey());
            bucket.setCount(vo.getCount());
            result.add(bucket);
        }
        return result;
    }
}
