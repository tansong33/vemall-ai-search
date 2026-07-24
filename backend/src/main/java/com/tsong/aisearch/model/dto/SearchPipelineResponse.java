package com.tsong.aisearch.model.dto;

public class SearchPipelineResponse {

    private NerResult nerResult;
    private ModelResult modelResult;
    private EsAnalyzeResult esAnalyzeResult;
    private SearchResult searchResult;
    private Long totalCostMs;

    public NerResult getNerResult() { return nerResult; }
    public void setNerResult(NerResult nerResult) { this.nerResult = nerResult; }
    public ModelResult getModelResult() { return modelResult; }
    public void setModelResult(ModelResult modelResult) { this.modelResult = modelResult; }
    public EsAnalyzeResult getEsAnalyzeResult() { return esAnalyzeResult; }
    public void setEsAnalyzeResult(EsAnalyzeResult esAnalyzeResult) { this.esAnalyzeResult = esAnalyzeResult; }
    public SearchResult getSearchResult() { return searchResult; }
    public void setSearchResult(SearchResult searchResult) { this.searchResult = searchResult; }
    public Long getTotalCostMs() { return totalCostMs; }
    public void setTotalCostMs(Long totalCostMs) { this.totalCostMs = totalCostMs; }

    public long calculateTotalCost() {
        long total = 0;
        if (nerResult != null) total += nerResult.getCostMs();
        if (modelResult != null) total += modelResult.getCostMs();
        if (esAnalyzeResult != null) total += esAnalyzeResult.getCostMs();
        if (searchResult != null) total += searchResult.getCostMs();
        return total;
    }
}
