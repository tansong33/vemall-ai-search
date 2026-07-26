package cn.vetech.ai.search.server.repository;

import cn.vetech.ai.search.server.model.dto.EsAnalyzeResult;

public interface TextAnalysisRepository {

    EsAnalyzeResult analyze(String text, String analyzer);
}
