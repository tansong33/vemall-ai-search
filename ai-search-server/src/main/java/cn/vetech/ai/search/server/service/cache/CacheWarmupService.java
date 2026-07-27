package cn.vetech.ai.search.server.service.cache;

import cn.vetech.ai.search.server.dao.SearchCacheDao;
import cn.vetech.ai.search.server.service.DegradeContext;
import cn.vetech.ai.search.server.service.SearchService;
import cn.vetech.ai.search.server.service.dto.SearchQueryDto;
import cn.vetech.ai.search.server.service.vo.CacheWarmupVo;
import cn.vetech.ai.search.server.service.vo.SearchResultVo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 热搜词缓存预热：逐个执行搜索并把结果写入热搜词缓存。
 *
 * <p>启动预热默认关闭；开启时异步执行且失败只记 WARN —— ES 没起来不能让应用起不来。</p>
 */
@Service
public class CacheWarmupService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CacheWarmupService.class);

    /** 热搜词缓存一次存这么多条，不分页；命中后由 SearchService 切片。 */
    private static final int WARMUP_PAGE_SIZE = 200;

    private final SearchService searchService;
    private final SearchCacheDao cacheDao;

    @Value("${ai-search.search.warmup-on-startup:false}")
    private boolean warmupOnStartup;

    private volatile List<String> hotQueries = Collections.emptyList();

    public CacheWarmupService(SearchService searchService, SearchCacheDao cacheDao) {
        this.searchService = searchService;
        this.cacheDao = cacheDao;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!warmupOnStartup) {
            return;
        }
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    warmup();
                } catch (RuntimeException e) {
                    log.warn("启动预热失败，不影响应用运行: {}", e.getMessage());
                }
            }
        }, "cache-warmup");
        thread.setDaemon(true);
        thread.start();
    }

    public CacheWarmupVo warmup() {
        long started = System.currentTimeMillis();
        List<String> queries = getHotQueries();
        CacheWarmupVo result = new CacheWarmupVo();
        result.setTotal(queries.size());
        result.setQueries(queries);

        for (String query : queries) {
            try {
                SearchQueryDto dto = new SearchQueryDto();
                dto.setQuery(query);
                dto.setPage(1);
                dto.setPageSize(WARMUP_PAGE_SIZE);
                SearchResultVo data = searchService.search(dto, new DegradeContext());
                cacheDao.putHotQuery(query, data);
                result.setSucceeded(result.getSucceeded() + 1);
            } catch (RuntimeException e) {
                result.setFailed(result.getFailed() + 1);
                log.warn("预热失败 query={}: {}", query, e.getMessage());
            }
        }
        result.setCostMs(System.currentTimeMillis() - started);
        log.info("热搜词预热完成 total={} ok={} failed={} took={}ms",
                result.getTotal(), result.getSucceeded(), result.getFailed(), result.getCostMs());
        return result;
    }

    public List<String> getHotQueries() {
        if (!hotQueries.isEmpty()) {
            return hotQueries;
        }
        List<String> loaded = new ArrayList<String>();
        ClassPathResource resource = new ClassPathResource("hot-queries.txt");
        if (!resource.exists()) {
            log.warn("hot-queries.txt 不存在，预热将无事可做");
            return loaded;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String value = line.trim();
                if (!value.isEmpty() && !value.startsWith("#")) {
                    loaded.add(value);
                }
            }
        } catch (Exception e) {
            log.error("读取 hot-queries.txt 失败", e);
        }
        hotQueries = Collections.unmodifiableList(loaded);
        return hotQueries;
    }
}
