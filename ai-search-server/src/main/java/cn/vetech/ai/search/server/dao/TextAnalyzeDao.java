package cn.vetech.ai.search.server.dao;

import cn.vetech.ai.search.server.model.dto.EsAnalyzeResult;

/**
 * Elasticsearch 文本分析数据访问接口。
 */
public interface TextAnalyzeDao {

    EsAnalyzeResult analyze(String text, String analyzer);
}
