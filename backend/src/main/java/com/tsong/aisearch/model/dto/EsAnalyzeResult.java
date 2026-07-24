package com.tsong.aisearch.model.dto;

import java.util.ArrayList;
import java.util.List;

public class EsAnalyzeResult {

    private String analyzer;
    private List<TokenInfo> tokens = new ArrayList<>();
    private long costMs;

    public String getAnalyzer() { return analyzer; }
    public void setAnalyzer(String analyzer) { this.analyzer = analyzer; }
    public List<TokenInfo> getTokens() { return tokens; }
    public void setTokens(List<TokenInfo> tokens) { this.tokens = tokens; }
    public long getCostMs() { return costMs; }
    public void setCostMs(long costMs) { this.costMs = costMs; }

    public static class TokenInfo {
        private String term;
        private int startOffset;
        private int endOffset;
        private int position;
        private String type;

        public String getTerm() { return term; }
        public void setTerm(String term) { this.term = term; }
        public int getStartOffset() { return startOffset; }
        public void setStartOffset(int startOffset) { this.startOffset = startOffset; }
        public int getEndOffset() { return endOffset; }
        public void setEndOffset(int endOffset) { this.endOffset = endOffset; }
        public int getPosition() { return position; }
        public void setPosition(int position) { this.position = position; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
    }
}
