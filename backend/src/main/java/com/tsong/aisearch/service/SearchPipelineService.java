package com.tsong.aisearch.service;

import com.tsong.aisearch.model.dto.EsAnalyzeResult;
import com.tsong.aisearch.model.dto.ModelResult;
import com.tsong.aisearch.model.dto.NerResult;
import com.tsong.aisearch.model.dto.PipelineRequest;
import com.tsong.aisearch.model.dto.SearchPipelineResponse;
import com.tsong.aisearch.repository.TextAnalysisRepository;
import com.tsong.aisearch.service.ner.NerRecognizer;
import com.tsong.aisearch.service.query.QueryUnderstandingService;
import com.tsong.aisearch.service.recall.RecallOrchestrator;
import org.springframework.stereotype.Service;

@Service
public class SearchPipelineService {

    private final NerRecognizer nerRecognizer;
    private final QueryUnderstandingService queryUnderstanding;
    private final TextAnalysisRepository textAnalysisRepository;
    private final RecallOrchestrator recallOrchestrator;

    public SearchPipelineService(NerRecognizer nerRecognizer,
                                 QueryUnderstandingService queryUnderstanding,
                                 TextAnalysisRepository textAnalysisRepository,
                                 RecallOrchestrator recallOrchestrator) {
        this.nerRecognizer = nerRecognizer;
        this.queryUnderstanding = queryUnderstanding;
        this.textAnalysisRepository = textAnalysisRepository;
        this.recallOrchestrator = recallOrchestrator;
    }

    public SearchPipelineResponse execute(PipelineRequest request) {
        SearchPipelineResponse response = new SearchPipelineResponse();

        long started = System.nanoTime();
        NerResult ner = new NerResult();
        ner.setEntities(nerRecognizer.recognize(request.getQuery()));
        ner.setCostMs(elapsedMs(started));
        ner.setProvider(nerRecognizer.provider());
        ner.setModelVersion(nerRecognizer.modelVersion());
        response.setNerResult(ner);

        ModelResult modelResult = queryUnderstanding.process(request.getQuery(), ner);
        response.setModelResult(modelResult);

        EsAnalyzeResult analysis = textAnalysisRepository.analyze(request.getQuery(), "ik_max_word");
        response.setEsAnalyzeResult(analysis);

        response.setSearchResult(recallOrchestrator.recall(request.getQuery(), modelResult,
                request.getSort(), request.getFilters()));
        response.setTotalCostMs(response.calculateTotalCost());
        return response;
    }

    public NerResult recognize(String query) {
        long started = System.nanoTime();
        NerResult result = new NerResult();
        result.setEntities(nerRecognizer.recognize(query));
        result.setCostMs(elapsedMs(started));
        result.setProvider(nerRecognizer.provider());
        result.setModelVersion(nerRecognizer.modelVersion());
        return result;
    }

    public EsAnalyzeResult analyze(String text, String analyzer) {
        return textAnalysisRepository.analyze(text, analyzer);
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
