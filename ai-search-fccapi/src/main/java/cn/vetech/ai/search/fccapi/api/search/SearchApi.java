package cn.vetech.ai.search.fccapi.api.search;

import cn.vetech.ai.search.fccapi.ApiResponse;

/**
 * 商品搜索对外契约。
 */
public interface SearchApi {

    /** 商品搜索。唯一的生产接口。 */
    ApiResponse<SearchResponse> search(SearchRequest request);
}
