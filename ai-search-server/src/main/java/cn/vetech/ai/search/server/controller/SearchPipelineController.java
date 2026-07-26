package cn.vetech.ai.search.server.controller;

import cn.vetech.ai.search.server.model.dto.EsAnalyzeResult;
import cn.vetech.ai.search.server.model.dto.NerResult;
import cn.vetech.ai.search.server.model.dto.PipelineRequest;
import cn.vetech.ai.search.server.model.dto.SearchPipelineResponse;
import cn.vetech.ai.search.server.service.SearchPipelineService;
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
