package cn.vetech.ai.search.server.service;

import cn.vetech.ai.search.server.dao.TextAnalyzeDao;
import cn.vetech.ai.search.server.model.dto.EsAnalyzeResult;
import cn.vetech.ai.search.server.model.dto.ModelResult;
import cn.vetech.ai.search.server.model.dto.NerEntity;
import cn.vetech.ai.search.server.model.dto.NerResult;
import cn.vetech.ai.search.server.model.dto.PipelineRequest;
import cn.vetech.ai.search.server.model.dto.SearchPipelineResponse;
import cn.vetech.ai.search.server.service.dto.NerEntityDto;
import cn.vetech.ai.search.server.service.ner.NerRecognizer;
import cn.vetech.ai.search.server.service.query.QueryUnderstandingService;
import cn.vetech.ai.search.server.service.recall.RecallOrchestrator;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class SearchPipelineService {

    private final NerRecognizer nerRecognizer;
    private final QueryUnderstandingService queryUnderstanding;
    private final TextAnalyzeDao textAnalyzeDao;
    private final RecallOrchestrator recallOrchestrator;

    public SearchPipelineService(NerRecognizer nerRecognizer,
                                 QueryUnderstandingService queryUnderstanding,
                                 TextAnalyzeDao textAnalyzeDao,
                                 RecallOrchestrator recallOrchestrator) {
        this.nerRecognizer = nerRecognizer;
        this.queryUnderstanding = queryUnderstanding;
        this.textAnalyzeDao = textAnalyzeDao;
        this.recallOrchestrator = recallOrchestrator;
    }

    public SearchPipelineResponse execute(PipelineRequest request) {
        SearchPipelineResponse response = new SearchPipelineResponse();

        long started = System.nanoTime();
        NerResult ner = new NerResult();
        ner.setEntities(toLegacy(nerRecognizer.recognize(request.getQuery())));
        ner.setCostMs(elapsedMs(started));
        ner.setProvider(nerRecognizer.provider());
        ner.setModelVersion(nerRecognizer.modelVersion());
        response.setNerResult(ner);

        ModelResult modelResult = queryUnderstanding.process(request.getQuery(), ner);
        response.setModelResult(modelResult);

        EsAnalyzeResult analysis = textAnalyzeDao.analyze(request.getQuery(), "ik_max_word");
        response.setEsAnalyzeResult(analysis);

        response.setSearchResult(recallOrchestrator.recall(request.getQuery(), modelResult, ner.getEntities(),
                request.getSort(), request.getFilters()));
        response.setTotalCostMs(response.calculateTotalCost());
        return response;
    }

    public NerResult recognize(String query) {
        long started = System.nanoTime();
        NerResult result = new NerResult();
        result.setEntities(toLegacy(nerRecognizer.recognize(query)));
        result.setCostMs(elapsedMs(started));
        result.setProvider(nerRecognizer.provider());
        result.setModelVersion(nerRecognizer.modelVersion());
        return result;
    }

    public EsAnalyzeResult analyze(String text, String analyzer) {
        return textAnalyzeDao.analyze(text, analyzer);
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    /** 过渡期适配：新的 NerEntityDto 转旧的 model.dto.NerEntity。T07 删除本类时一并移除。 */
    private static List<NerEntity> toLegacy(List<NerEntityDto> entities) {
        List<NerEntity> legacy = new ArrayList<NerEntity>();
        if (entities == null) {
            return legacy;
        }
        for (NerEntityDto dto : entities) {
            NerEntity entity = new NerEntity();
            entity.setText(dto.getText());
            entity.setLabel(dto.getLabel());
            entity.setStart(dto.getStart());
            entity.setEnd(dto.getEnd());
            legacy.add(entity);
        }
        return legacy;
    }
}
