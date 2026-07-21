package cn.vetech.aimall.controller;

import cn.vetech.aimall.service.ner.HybridIntentRecognizer;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** NER 配置与制品就绪状态；生产环境应由网关限制为内部管理接口。 */
@RestController
@RequestMapping("/api/admin/ner")
@RequiredArgsConstructor
public class NerAdminController {

    private final HybridIntentRecognizer recognizer;

    @GetMapping("/status")
    public Map<String, Object> status() {
        return recognizer.status();
    }
}
