package com.tsong.aisearch.controller;

import com.tsong.aisearch.model.dto.EsAnalyzeResult;
import com.tsong.aisearch.model.dto.NerResult;
import com.tsong.aisearch.model.dto.PipelineRequest;
import com.tsong.aisearch.model.dto.SearchPipelineResponse;
import com.tsong.aisearch.service.SearchPipelineService;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/search")
@CrossOrigin(origins = "*")
public class SearchPipelineController {

    private final SearchPipelineService pipeline;

    public SearchPipelineController(SearchPipelineService pipeline) {
        this.pipeline = pipeline;
    }

    @PostMapping(value = "/pipeline", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SearchPipelineResponse pipeline(@Valid @RequestBody PipelineRequest request) {
        return pipeline.execute(request);
    }

    @PostMapping(value = "/ner", consumes = MediaType.APPLICATION_JSON_VALUE)
    public NerResult ner(@RequestBody Map<String, String> request) {
        return pipeline.recognize(request.getOrDefault("query", ""));
    }

    @PostMapping(value = "/analyze", consumes = MediaType.APPLICATION_JSON_VALUE)
    public EsAnalyzeResult analyze(@RequestBody Map<String, String> request) {
        return pipeline.analyze(request.getOrDefault("text", ""),
                request.getOrDefault("analyzer", "ik_max_word"));
    }
}
