package cn.vetech.ai.search.fccapi.api.debug;

import cn.vetech.ai.search.fccapi.ApiResponse;

/**
 * 搜索链路调试契约。
 */
public interface SearchDebugApi {

    /** 搜索链路可视化。仅供内网调试页使用，不对外开放。 */
    ApiResponse<SearchDebugResponse> pipeline(SearchDebugRequest request);
}
