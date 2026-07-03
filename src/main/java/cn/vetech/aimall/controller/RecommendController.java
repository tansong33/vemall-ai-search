package cn.vetech.aimall.controller;

import cn.vetech.aimall.model.dto.RecommendRequest;
import cn.vetech.aimall.model.dto.RecommendResponse;
import cn.vetech.aimall.service.RecommendPipeline;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Base64;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class RecommendController {

    private final RecommendPipeline pipeline;

    /**
     * 推荐主接口（JSON 版）：
     * POST /api/recommend
     * { "query": "夏天降暑的福利，预算50以内", "imageBase64": "...(可选)", "imageMimeType": "image/jpeg" }
     */
    @PostMapping(value = "/recommend", consumes = MediaType.APPLICATION_JSON_VALUE)
    public RecommendResponse recommend(@RequestBody RecommendRequest request) {
        return pipeline.recommend(request);
    }

    /**
     * 推荐主接口（表单+图片上传版，前端页面使用）：
     * POST /api/recommend/upload  multipart: query=..., image=file(可选)
     */
    @PostMapping(value = "/recommend/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public RecommendResponse recommendUpload(@RequestParam(value = "query", required = false) String query,
                                             @RequestParam(value = "image", required = false) MultipartFile image)
            throws Exception {
        RecommendRequest req = new RecommendRequest();
        req.setQuery(query);
        if (image != null && !image.isEmpty()) {
            req.setImageBase64(Base64.getEncoder().encodeToString(image.getBytes()));
            req.setImageMimeType(image.getContentType());
        }
        return pipeline.recommend(req);
    }
}
