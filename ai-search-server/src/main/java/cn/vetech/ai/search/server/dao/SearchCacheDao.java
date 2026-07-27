package cn.vetech.ai.search.server.dao;

import cn.vetech.ai.search.server.service.dto.NerResultDto;
import cn.vetech.ai.search.server.service.vo.SearchResultVo;

import java.util.Map;

/**
 * 统一封装搜索链路对 Redis 缓存的访问。
 */
public interface SearchCacheDao {

    /**
     * Redis 是否可达，用于调试链路如实展示状态。
     */
    boolean isAvailable();

    SearchResultVo getSearchResult(String cacheKey);

    /** @return true 表示写入成功；false 表示 Redis 不可用，调用方据此标记降级。 */
    boolean putSearchResult(String cacheKey, SearchResultVo data);

    /**
     * 读取一次缓存约 200 条、不含分页切片的热搜词结果。
     */
    SearchResultVo getHotQuery(String query);

    /**
     * 写入一次缓存约 200 条、不含分页切片的热搜词结果。
     */
    void putHotQuery(String query, SearchResultVo data);

    NerResultDto getNerResult(String query);

    void putNerResult(String query, NerResultDto result);

    /**
     * 拼装带版本维度的结果缓存 key，供搜索服务与调试链路共用。
     *
     * dimensions 必须包含 sort 和 pageSize，避免不同排序或分页大小共用缓存。
     */
    String buildSearchResultKey(String query, Map<String, String> dimensions, int page);
}
