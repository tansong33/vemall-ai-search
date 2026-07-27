package cn.vetech.ai.search.rest.assembler;

import cn.vetech.ai.search.fccapi.api.debug.SearchDebugResponse;
import cn.vetech.ai.search.server.service.dto.NerEntityDto;
import cn.vetech.ai.search.server.service.dto.SearchQueryDto;
import cn.vetech.ai.search.server.service.vo.SearchDebugVo;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 调试链路 VO 到契约的字段搬运。 */
@Component
public class SearchDebugAssembler {

    private final SearchAssembler searchAssembler;

    public SearchDebugAssembler(SearchAssembler searchAssembler) {
        this.searchAssembler = searchAssembler;
    }

    public SearchDebugResponse toResponse(SearchDebugVo vo, SearchQueryDto query,
                                          boolean includeEsDsl) {
        SearchDebugResponse response = new SearchDebugResponse();
        response.setResult(searchAssembler.toResponse(vo.getResult(), query, toDegrade(vo)));
        response.setNer(toNer(vo.getNer()));
        response.setQuery(toQuery(vo.getQuery()));
        response.setEs(toEs(vo.getEs(), includeEsDsl));
        response.setCache(toCache(vo.getCache()));
        response.setTiming(toTiming(vo.getTiming()));
        return response;
    }

    /** 调试链路的降级信息已经收在 VO 上，这里还原成 DegradeContext 供搜索出参复用。 */
    private cn.vetech.ai.search.server.service.DegradeContext toDegrade(SearchDebugVo vo) {
        cn.vetech.ai.search.server.service.DegradeContext degrade =
                new cn.vetech.ai.search.server.service.DegradeContext();
        for (String reason : vo.getDegradeReasons()) {
            degrade.add(reason);
        }
        return degrade;
    }

    private SearchDebugResponse.NerStage toNer(SearchDebugVo.NerStage vo) {
        SearchDebugResponse.NerStage stage = new SearchDebugResponse.NerStage();
        List<SearchDebugResponse.NerStage.Entity> entities =
                new ArrayList<SearchDebugResponse.NerStage.Entity>();
        for (NerEntityDto dto : vo.getEntities()) {
            SearchDebugResponse.NerStage.Entity entity = new SearchDebugResponse.NerStage.Entity();
            entity.setText(dto.getText());
            entity.setLabel(dto.getLabel());
            entity.setStart(dto.getStart());
            entity.setEnd(dto.getEnd());
            entity.setConfidence(dto.getConfidence());
            entity.setNormalizedText(dto.getNormalizedText());
            entity.setNormalizedId(dto.getNormalizedId());
            entities.add(entity);
        }
        stage.setEntities(entities);
        stage.setProvider(vo.getProvider());
        stage.setMode(vo.getMode());
        stage.setModelVersion(vo.getModelVersion());
        stage.setModelReady(vo.isModelReady());
        stage.setCostMs(vo.getCostMs());
        return stage;
    }

    private SearchDebugResponse.QueryStage toQuery(SearchDebugVo.QueryStage vo) {
        SearchDebugResponse.QueryStage stage = new SearchDebugResponse.QueryStage();
        stage.setRewrittenQuery(vo.getRewrittenQuery());
        stage.setSynonyms(vo.getSynonyms());
        stage.setAnalyzedTokens(vo.getAnalyzedTokens());
        stage.setCostMs(vo.getCostMs());
        return stage;
    }

    private SearchDebugResponse.EsStage toEs(SearchDebugVo.EsStage vo, boolean includeEsDsl) {
        SearchDebugResponse.EsStage stage = new SearchDebugResponse.EsStage();
        stage.setMustClauses(vo.getMustClauses());
        stage.setShouldClauses(vo.getShouldClauses());
        stage.setMustNotClauses(vo.getMustNotClauses());
        // 条件描述始终返回，原始 DSL 体积大，只在显式索取时给
        stage.setDsl(includeEsDsl ? vo.getDsl() : null);
        stage.setTotalHits(vo.getTotalHits());
        stage.setCostMs(vo.getCostMs());
        return stage;
    }

    private SearchDebugResponse.CacheStage toCache(SearchDebugVo.CacheStage vo) {
        SearchDebugResponse.CacheStage stage = new SearchDebugResponse.CacheStage();
        stage.setStatus(vo.getStatus());
        stage.setKey(vo.getKey());
        stage.setLookupMs(vo.getLookupMs());
        return stage;
    }

    private SearchDebugResponse.TimingStage toTiming(SearchDebugVo.TimingStage vo) {
        SearchDebugResponse.TimingStage stage = new SearchDebugResponse.TimingStage();
        stage.setTotalMs(vo.getTotalMs());
        stage.setCacheMs(vo.getCacheMs());
        stage.setNerMs(vo.getNerMs());
        stage.setQueryMs(vo.getQueryMs());
        stage.setEsMs(vo.getEsMs());
        return stage;
    }
}
