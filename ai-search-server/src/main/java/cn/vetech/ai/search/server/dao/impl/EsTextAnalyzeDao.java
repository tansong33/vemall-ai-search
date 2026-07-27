package cn.vetech.ai.search.server.dao.impl;

import cn.vetech.ai.search.server.dao.TextAnalyzeDao;
import cn.vetech.ai.search.server.service.vo.EsAnalyzeVo;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.AnalyzeRequest;
import org.elasticsearch.client.indices.AnalyzeResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * 使用 Elasticsearch analyze API 获取分词结果。
 */
@Repository
public class EsTextAnalyzeDao implements TextAnalyzeDao {

    private static final Logger log = LoggerFactory.getLogger(EsTextAnalyzeDao.class);

    private final RestHighLevelClient client;

    public EsTextAnalyzeDao(RestHighLevelClient client) {
        this.client = client;
    }

    @Override
    public EsAnalyzeVo analyze(String text, String analyzer) {
        long started = System.nanoTime();
        EsAnalyzeVo result = new EsAnalyzeVo();
        result.setAnalyzer(analyzer);
        try {
            AnalyzeRequest request = AnalyzeRequest.withGlobalAnalyzer(analyzer, text);
            AnalyzeResponse response = client.indices().analyze(request, RequestOptions.DEFAULT);
            List<EsAnalyzeVo.TokenInfo> tokens = new ArrayList<EsAnalyzeVo.TokenInfo>();
            for (AnalyzeResponse.AnalyzeToken token : response.getTokens()) {
                EsAnalyzeVo.TokenInfo item = new EsAnalyzeVo.TokenInfo();
                item.setTerm(token.getTerm());
                item.setStartOffset(token.getStartOffset());
                item.setEndOffset(token.getEndOffset());
                item.setPosition(token.getPosition());
                item.setType(token.getType());
                tokens.add(item);
            }
            result.setTokens(tokens);
        } catch (Exception e) {
            log.error("Elasticsearch analyze failed: analyzer={}, text={}", analyzer, text, e);
            result.setTokens(new ArrayList<EsAnalyzeVo.TokenInfo>());
        }
        result.setCostMs((System.nanoTime() - started) / 1_000_000);
        return result;
    }
}
