package cn.vetech.ai.search.server.service;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次搜索过程中的降级与缓存状态收集器。
 *
 * <p>单独成类而非塞进 VO：降级是编排层才知道的事实，dao 与缓存层不应感知它。</p>
 */
public final class DegradeContext {

    /** degradeReasons 的取值集合，与 fccapi 的 SearchResponse Javadoc 保持一致。 */
    public static final String REDIS_UNAVAILABLE = "REDIS_UNAVAILABLE";
    public static final String NER_UNAVAILABLE = "NER_UNAVAILABLE";
    public static final String MODEL_UNAVAILABLE = "MODEL_UNAVAILABLE";
    public static final String DEDUP_SKIPPED = "DEDUP_SKIPPED";

    private final List<String> reasons = new ArrayList<>();
    @Getter
    private String cacheStatus = "MISS";
    @Getter
    private long cacheLookupMs;

    public void add(String reason) {
        if (!reasons.contains(reason)) {
            reasons.add(reason);
        }
    }

    void hit(long lookupMs) {
        this.cacheStatus = "HIT";
        this.cacheLookupMs = lookupMs;
    }

    void miss(long lookupMs) {
        this.cacheStatus = "MISS";
        this.cacheLookupMs = lookupMs;
    }

    public void unavailable() {
        this.cacheStatus = "UNAVAILABLE";
    }

    public boolean isDegraded() {
        return !reasons.isEmpty();
    }

    public List<String> getReasons() {
        return Collections.unmodifiableList(reasons);
    }
}
