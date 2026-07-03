package cn.vetech.aimall.service;

import cn.vetech.aimall.config.AiMallProperties;
import cn.vetech.aimall.model.dto.IntentResult;
import cn.vetech.aimall.model.dto.RecommendRequest;
import cn.vetech.aimall.model.dto.RecommendResponse;
import cn.vetech.aimall.model.dto.ScoredProduct;
import cn.vetech.aimall.service.recall.HybridRecallService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vetech.aimall.model.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 推荐主编排（Pipeline）：缓存 -> 意图理解 -> 混合召回 -> 精排 -> 生成 -> 回填缓存。
 * 每一步都是独立 Service，单测/替换/归因互不影响 —— 迭代调优时按层归因就靠这个结构。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendPipeline {

    private final IntentService intentService;
    private final HybridRecallService recallService;
    private final RerankService rerankService;
    private final GenerationService generationService;
    private final StringRedisTemplate redisTemplate;
    private final AiMallProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    public RecommendResponse recommend(RecommendRequest request) {
        long start = System.currentTimeMillis();
        String cacheKey = cacheKey(request);

        // ---------- 0) Redis 查询缓存：相同问题直接返回，模型调用成本归零 ----------
        if (props.getCache().isEnabled() && request.getImageBase64() == null) {
            try {
                String cached = redisTemplate.opsForValue().get(cacheKey);
                if (cached != null) {
                    RecommendResponse resp = mapper.readValue(cached, RecommendResponse.class);
                    resp.setFromCache(true);
                    log.info("缓存命中 key={}，耗时 {}ms", cacheKey, System.currentTimeMillis() - start);
                    return resp;
                }
            } catch (Exception e) {
                log.warn("Redis 读取失败（不影响主流程）: {}", e.getMessage());
            }
        }

        // ---------- 1) 意图理解 ----------
        IntentResult intent = intentService.extract(
                request.getQuery(), request.getImageBase64(), request.getImageMimeType());
        log.info("意图: category={}, budgetMax={}, scenes={}, keywords={}, clarifications={}",
                intent.getCategory(), intent.getBudgetMax(), intent.getScenes(),
                intent.getKeywords(), intent.getClarifications());

        // ---------- 2) 混合召回 + 硬过滤（类目过严自动放宽重试） ----------
        List<ScoredProduct> candidates = recallService.recallWithRelax(request.getQuery(), intent);

        // ---------- 3) 精排 ----------
        List<ScoredProduct> top = rerankService.rerank(candidates, intent);

        // ---------- 4) 生成话术 + 商品卡 ----------
        RecommendResponse resp = new RecommendResponse();
        resp.setIntent(intent);
        resp.setNeedClarification(!intent.getClarifications().isEmpty());
        resp.setReply(generationService.generateReply(request.getQuery(), intent, top));
        resp.setProducts(top.stream()
                .map(sp -> generationService.toCard(sp, intent))
                .collect(Collectors.toList()));

        // ---------- 5) 回填缓存 ----------
        if (props.getCache().isEnabled() && request.getImageBase64() == null) {
            try {
                redisTemplate.opsForValue().set(cacheKey, mapper.writeValueAsString(resp),
                        Duration.ofSeconds(props.getCache().getTtlSeconds()));
            } catch (Exception e) {
                log.warn("Redis 写入失败（不影响主流程）: {}", e.getMessage());
            }
        }
        log.info("推荐完成，返回 {} 款商品，总耗时 {}ms", resp.getProducts().size(),
                System.currentTimeMillis() - start);
        return resp;
    }

    private String cacheKey(RecommendRequest req) {
        try {
            String raw = "q:" + req.getQuery();
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("aimall:rec:");
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "aimall:rec:" + Math.abs(String.valueOf(req.getQuery()).hashCode());
        }
    }
}
