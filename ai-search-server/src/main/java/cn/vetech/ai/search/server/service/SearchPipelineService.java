package cn.vetech.ai.search.server.service;

import cn.vetech.ai.search.server.model.dto.EsAnalyzeResult;
import cn.vetech.ai.search.server.model.dto.ModelResult;
import cn.vetech.ai.search.server.model.dto.NerResult;
import cn.vetech.ai.search.server.model.dto.PipelineRequest;
import cn.vetech.ai.search.server.model.dto.SearchPipelineResponse;
import cn.vetech.ai.search.server.repository.TextAnalysisRepository;
import cn.vetech.ai.search.server.service.ner.NerRecognizer;
import cn.vetech.ai.search.server.service.query.QueryUnderstandingService;
import cn.vetech.ai.search.server.service.recall.RecallOrchestrator;
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

        response.setSearchResult(recallOrchestrator.recall(request.getQuery(), modelResult, ner.getEntities(),
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
