package cn.vetech.ai.search.server.dao;

import cn.vetech.ai.search.server.service.vo.EsAnalyzeVo;

/**
 * Elasticsearch 文本分析数据访问接口。
 */
public interface TextAnalyzeDao {

    EsAnalyzeVo analyze(String text, String analyzer);
}
