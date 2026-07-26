package cn.vetech.ai.search.server.repository.elasticsearch;

import cn.vetech.ai.search.server.model.dto.EsAnalyzeResult;
import cn.vetech.ai.search.server.repository.TextAnalysisRepository;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.AnalyzeRequest;
import org.elasticsearch.client.indices.AnalyzeResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

@Repository
public class ElasticsearchTextAnalysisRepository implements TextAnalysisRepository {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchTextAnalysisRepository.class);
    private final RestHighLevelClient client;

    public ElasticsearchTextAnalysisRepository(RestHighLevelClient client) {
        this.client = client;
    }

    @Override
    public EsAnalyzeResult analyze(String text, String analyzer) {
        long started = System.nanoTime();
        EsAnalyzeResult result = new EsAnalyzeResult();
        result.setAnalyzer(analyzer);
        try {
            AnalyzeRequest request = AnalyzeRequest.withGlobalAnalyzer(analyzer, text);
            AnalyzeResponse response = client.indices().analyze(request, RequestOptions.DEFAULT);
            List<EsAnalyzeResult.TokenInfo> tokens = new ArrayList<>();
            for (AnalyzeResponse.AnalyzeToken token : response.getTokens()) {
                EsAnalyzeResult.TokenInfo item = new EsAnalyzeResult.TokenInfo();
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
            result.setTokens(new ArrayList<EsAnalyzeResult.TokenInfo>());
        }
        result.setCostMs((System.nanoTime() - started) / 1_000_000);
        return result;
    }
}
