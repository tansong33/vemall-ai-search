package cn.vetech.ai.search.fccapi.api.debug;

import cn.vetech.ai.search.fccapi.api.search.SearchRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 搜索链路调试请求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SearchDebugRequest extends SearchRequest {

    /** 是否返回 ES 原始 DSL。默认 false，避免响应体过大。 */
    private boolean includeEsDsl = false;
}
