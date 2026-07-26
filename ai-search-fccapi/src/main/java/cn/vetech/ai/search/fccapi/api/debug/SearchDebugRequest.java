package cn.vetech.ai.search.fccapi.api.debug;

import cn.vetech.ai.search.fccapi.api.search.SearchRequest;

/**
 * 搜索链路调试请求。
 */
public class SearchDebugRequest extends SearchRequest {

    /** 是否返回 ES 原始 DSL。默认 false，避免响应体过大。 */
    private boolean includeEsDsl = false;

    public boolean isIncludeEsDsl() {
        return includeEsDsl;
    }

    public void setIncludeEsDsl(boolean includeEsDsl) {
        this.includeEsDsl = includeEsDsl;
    }
}
