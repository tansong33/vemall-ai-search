package com.tsong.aisearch.repository;

import com.tsong.aisearch.model.dto.EsAnalyzeResult;

public interface TextAnalysisRepository {

    EsAnalyzeResult analyze(String text, String analyzer);
}
