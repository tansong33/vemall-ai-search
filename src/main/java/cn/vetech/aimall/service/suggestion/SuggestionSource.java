package cn.vetech.aimall.service.suggestion;

import cn.vetech.aimall.model.dto.SuggestionItem;

import java.util.List;

/** 联想数据源扩展点：当前为内存类目/品牌，后续可并行接热词、用户历史和 ES ngram。 */
public interface SuggestionSource {
    String name();
    boolean isReady();
    List<SuggestionItem> suggest(String normalizedQuery, String tenantCode, String channelCode, int limit);
}
