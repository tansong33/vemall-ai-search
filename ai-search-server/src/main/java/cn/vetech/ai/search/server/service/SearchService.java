package cn.vetech.ai.search.server.service;

import cn.vetech.ai.search.fccapi.api.search.SearchRequest;
import cn.vetech.ai.search.server.dao.ProductSearchDao;
import cn.vetech.ai.search.server.dao.SearchCacheDao;
import cn.vetech.ai.search.server.dao.SearchDataAccessException;
import cn.vetech.ai.search.server.service.dto.NerEntityDto;
import cn.vetech.ai.search.server.service.dto.QueryUnderstandDto;
import cn.vetech.ai.search.server.service.dto.SearchQueryDto;
import cn.vetech.ai.search.server.service.ner.NerRecognizer;
import cn.vetech.ai.search.server.service.query.QueryUnderstandingService;
import cn.vetech.ai.search.server.service.vo.SearchItemVo;
import cn.vetech.ai.search.server.service.vo.SearchResultVo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 搜索编排：缓存 → NER → Query 理解 → ES 检索 → SPU 去重 → 回写缓存。
 *
 * <p>任一环节失败都降级而非中断，唯一的例外是 ES 不可用且没有可用缓存 —— 那时如实抛出，
 * 由 rest 层转 503，不返回空结果假装成功。</p>
 */
@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private final NerRecognizer nerRecognizer;
    private final QueryUnderstandingService queryUnderstanding;
    private final ProductSearchDao productSearchDao;
    private final SearchCacheDao cacheDao;
    private final SpuDeduplicator spuDeduplicator;

    public SearchService(NerRecognizer nerRecognizer,
                         QueryUnderstandingService queryUnderstanding,
                         ProductSearchDao productSearchDao,
                         SearchCacheDao cacheDao,
                         SpuDeduplicator spuDeduplicator) {
        this.nerRecognizer = nerRecognizer;
        this.queryUnderstanding = queryUnderstanding;
        this.productSearchDao = productSearchDao;
        this.cacheDao = cacheDao;
        this.spuDeduplicator = spuDeduplicator;
    }

    public SearchResultVo search(SearchQueryDto query, DegradeContext degrade) {
        long started = System.currentTimeMillis();
        long cacheStarted = System.currentTimeMillis();

        String cacheKey = buildCacheKey(query);
        // 调试链路要求如实报告缓存状态，但不能因命中而跳过后续阶段，否则各段全空
        boolean cacheHit = false;
        SearchResultVo hot = readHotQuery(query);
        if (hot != null) {
            cacheHit = true;
            if (!query.isAlwaysRunPipeline()) {
                degrade.hit(System.currentTimeMillis() - cacheStarted);
                hot.setCostMs(System.currentTimeMillis() - started);
                return hot;
            }
        }
        if (!cacheHit) {
            SearchResultVo cached = readCache(cacheKey, degrade);
            if (cached != null) {
                cacheHit = true;
                if (!query.isAlwaysRunPipeline()) {
                    degrade.hit(System.currentTimeMillis() - cacheStarted);
                    cached.setCostMs(System.currentTimeMillis() - started);
                    return cached;
                }
            }
        }
        if (cacheHit) {
            degrade.hit(System.currentTimeMillis() - cacheStarted);
        } else {
            degrade.miss(System.currentTimeMillis() - cacheStarted);
        }

        recognize(query, degrade);
        understand(query, degrade);

        SearchResultVo result;
        try {
            result = productSearchDao.search(query);
        } catch (SearchDataAccessException e) {
            log.error("ES 检索失败 query={}", query.getQuery(), e);
            throw e;
        }

        // 去重只作用于当前页，无从推知全局去重后的总数，因此 total 仍取 ES 命中数。
        try {
            result.setItems(spuDeduplicator.deduplicate(result.getItems()));
        } catch (RuntimeException e) {
            log.error("SPU 去重失败，返回未去重结果", e);
            degrade.add(DegradeContext.DEDUP_SKIPPED);
        }

        writeCache(cacheKey, result, degrade);
        result.setCostMs(System.currentTimeMillis() - started);
        log.info("搜索完成 query={} total={} took={}ms cache={} degraded={} reasons={}",
                query.getQuery(), result.getTotal(), result.getCostMs(),
                degrade.getCacheStatus(), degrade.isDegraded(), degrade.getReasons());
        return result;
    }

    private void recognize(SearchQueryDto query, DegradeContext degrade) {
        try {
            List<NerEntityDto> entities = nerRecognizer.recognize(query.getQuery());
            query.setEntities(entities == null ? new ArrayList<NerEntityDto>() : entities);
        } catch (RuntimeException e) {
            log.error("NER 识别失败，退化为全文搜索 query={}", query.getQuery(), e);
            query.setEntities(new ArrayList<NerEntityDto>());
            degrade.add(DegradeContext.NER_UNAVAILABLE);
        }
    }

    private void understand(SearchQueryDto query, DegradeContext degrade) {
        try {
            QueryUnderstandDto understand = queryUnderstanding.process(query.getQuery());
            query.setRewrittenQuery(understand.getRewrittenQuery());
            query.setSynonyms(understand.getSynonyms());
        } catch (RuntimeException e) {
            log.error("Query 理解失败，跳过改写与同义词 query={}", query.getQuery(), e);
            degrade.add(DegradeContext.MODEL_UNAVAILABLE);
        }
    }

    private SearchResultVo readHotQuery(SearchQueryDto query) {
        try {
            SearchResultVo hot = cacheDao.getHotQuery(query.getQuery());
            if (hot == null || hot.getItems() == null || hot.getItems().isEmpty()) {
                return null;
            }
            int from = (query.getPage() - 1) * query.getPageSize();
            if (from >= hot.getItems().size()) {
                return null;
            }
            return slice(hot, from, query.getPageSize());
        } catch (RuntimeException e) {
            log.error("热搜词缓存读取异常，跳过 query={}", query.getQuery(), e);
            return null;
        }
    }

    private SearchResultVo readCache(String cacheKey, DegradeContext degrade) {
        try {
            return cacheDao.getSearchResult(cacheKey);
        } catch (RuntimeException e) {
            log.error("结果缓存读取异常 key={}", cacheKey, e);
            degrade.add(DegradeContext.REDIS_UNAVAILABLE);
            return null;
        }
    }

    /**
     * dao 层按约定吞掉所有 Redis 异常，读操作因此无法区分「未命中」和「Redis 挂了」。
     * 写入回报成败是唯一零成本的探测点：未命中时本来就要写一次。
     */
    private void writeCache(String cacheKey, SearchResultVo result, DegradeContext degrade) {
        boolean written;
        try {
            written = cacheDao.putSearchResult(cacheKey, result);
        } catch (RuntimeException e) {
            log.error("结果缓存写入异常 key={}", cacheKey, e);
            written = false;
        }
        if (!written) {
            degrade.add(DegradeContext.REDIS_UNAVAILABLE);
        }
    }

    /** 缓存 key 的维度必须含 sort 与 pageSize，否则换排序或换页大小会串缓存。 */
    String buildCacheKey(SearchQueryDto query) {
        Map<String, String> dimensions = new HashMap<String, String>();
        SearchRequest.Filters filters = query.getFilters();
        if (filters != null) {
            putIfPresent(dimensions, "brands", join(filters.getBrands()));
            putIfPresent(dimensions, "categories", join(filters.getCategories()));
            putIfPresent(dimensions, "minPriceFen", filters.getMinPriceFen());
            putIfPresent(dimensions, "maxPriceFen", filters.getMaxPriceFen());
            if (Boolean.TRUE.equals(filters.getInStock())) {
                dimensions.put("inStock", "true");
            }
        }
        dimensions.put("sort", query.getSort() == null
                ? SearchRequest.Sort.RELEVANCE.name() : query.getSort().name());
        dimensions.put("pageSize", String.valueOf(query.getPageSize()));
        return cacheDao.buildSearchResultKey(query.getQuery(), dimensions, query.getPage());
    }

    private static SearchResultVo slice(SearchResultVo cached, int from, int pageSize) {
        List<SearchItemVo> all = cached.getItems();
        int to = Math.min(from + pageSize, all.size());
        SearchResultVo result = new SearchResultVo();
        result.setItems(new ArrayList<SearchItemVo>(all.subList(from, to)));
        result.setTotal(all.size());
        result.setRawTotal(cached.getRawTotal());
        result.setFacets(cached.getFacets());
        return result;
    }

    private static void putIfPresent(Map<String, String> target, String key, Object value) {
        if (value != null && !String.valueOf(value).isEmpty()) {
            target.put(key, String.valueOf(value));
        }
    }

    private static String join(List<String> values) {
        return values == null || values.isEmpty() ? null : String.join(",", values);
    }
}
