package cn.vetech.aimall.controller;

import cn.vetech.aimall.model.dto.RecommendRequest;
import cn.vetech.aimall.model.dto.RecommendResponse;
import cn.vetech.aimall.service.RecommendPipeline;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class RecommendController {

    private final RecommendPipeline pipeline;

    /** POST /api/recommend  { "query": "夏天降暑的员工福利，预算50以内" } */
    @PostMapping(value = "/recommend", consumes = MediaType.APPLICATION_JSON_VALUE)
    public RecommendResponse recommend(@RequestBody RecommendRequest request) {
        return pipeline.recommend(request);
    }
}
