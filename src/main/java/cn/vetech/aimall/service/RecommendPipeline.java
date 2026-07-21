package cn.vetech.aimall.service;

import cn.vetech.aimall.config.AiMallProperties;
import cn.vetech.aimall.model.dto.IntentResult;
import cn.vetech.aimall.model.dto.RecommendRequest;
import cn.vetech.aimall.model.dto.RecommendResponse;
import cn.vetech.aimall.model.dto.ScoredProduct;
import cn.vetech.aimall.model.dto.SearchTrace;
import cn.vetech.aimall.service.ner.IntentRecognizer;
import cn.vetech.aimall.service.ner.RuleBasedNerService;
import cn.vetech.aimall.service.recall.RecallOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** Cache -> NER -> 数据库有界召回 -> 规则精排 -> 模板组装。主链路没有外部 AI 调用。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendPipeline {

    private final IntentRecognizer nerService;
    private final RecallOrchestrator recallOrchestrator;
    private final ProductRuleEngine ruleEngine;
    private final ResponseAssembler responseAssembler;
    private final SearchCacheService cacheService;
    private final AiMallProperties properties;

    public RecommendResponse recommend(RecommendRequest request) {
        long started = System.nanoTime();
        validate(request);
        String normalizedQuery = RuleBasedNerService.normalize(request.getQuery());

        Optional<RecommendResponse> cached = cacheService.get(normalizedQuery);
        if (cached.isPresent()) {
            RecommendResponse response = cached.get();
            SearchTrace cachedTrace = response.getTrace();
            String cacheSource = cachedTrace == null ? "L1" : cachedTrace.getCacheSource();
            int candidateCount = cachedTrace == null ? response.getProducts().size() : cachedTrace.getCandidateCount();
            SearchTrace trace = new SearchTrace();
            trace.setRoute("CACHE");
            trace.setCacheSource(cacheSource);
            trace.setCandidateCount(candidateCount);
            trace.setNerSource(cachedTrace == null && response.getIntent() != null
                    ? response.getIntent().getNerSource() : cachedTrace == null ? null : cachedTrace.getNerSource());
            trace.setModelVersion(cachedTrace == null && response.getIntent() != null
                    ? response.getIntent().getModelVersion() : cachedTrace == null ? null : cachedTrace.getModelVersion());
            trace.setRuleVersion(properties.getVersions().getRule());
            trace.setIndexVersion(properties.getVersions().getIndex());
            trace.setTotalMs(elapsedMs(started));
            response.setTrace(trace);
            response.setFromCache(true);
            return response;
        }

        long stage = System.nanoTime();
        IntentResult intent = nerService.extract(normalizedQuery);
        long nerMs = elapsedMs(stage);

        stage = System.nanoTime();
        DbSearchResult dbResult = recallOrchestrator.recall(intent);
        long databaseMs = elapsedMs(stage);

        stage = System.nanoTime();
        List<ScoredProduct> top = ruleEngine.rank(dbResult.getProducts(), intent);
        long ruleMs = elapsedMs(stage);

        RecommendResponse response = new RecommendResponse();
        response.setIntent(intent);
        response.setNeedClarification(!intent.getClarifications().isEmpty());
        response.setReply(responseAssembler.reply(top.size(), intent));
        response.setProducts(top.stream()
                .map(scored -> responseAssembler.toCard(scored, intent))
                .collect(Collectors.toList()));

        SearchTrace trace = new SearchTrace();
        trace.setRoute(dbResult.getRoute());
        trace.setNerSource(intent.getNerSource());
        trace.setModelVersion(intent.getModelVersion());
        trace.setRuleVersion(properties.getVersions().getRule());
        trace.setIndexVersion(properties.getVersions().getIndex());
        trace.setCandidateCount(dbResult.getProducts().size());
        trace.setNerMs(nerMs);
        trace.setDatabaseMs(databaseMs);
        trace.setRuleMs(ruleMs);
        trace.setTotalMs(elapsedMs(started));
        response.setTrace(trace);
        cacheService.put(normalizedQuery, response);

        log.info("search route={} candidates={} returned={} ner={}ms db={}ms rule={}ms total={}ms",
                trace.getRoute(), trace.getCandidateCount(), top.size(), nerMs, databaseMs,
                ruleMs, trace.getTotalMs());
        return response;
    }

    private void validate(RecommendRequest request) {
        if (request == null || !StringUtils.hasText(request.getQuery())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query 不能为空");
        }
        if (request.getQuery().length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query 最长 200 个字符");
        }
    }

    private long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
