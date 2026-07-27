package cn.vetech.ai.search.server.controller;

import cn.vetech.ai.search.server.config.NerProperties;
import cn.vetech.ai.search.server.service.ner.NerRecognizer;
import cn.vetech.ai.search.server.service.ner.OnnxNerModelClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/ner")
public class NerAdminController {

    private final NerRecognizer recognizer;
    private final OnnxNerModelClient model;
    private final NerProperties properties;

    public NerAdminController(NerRecognizer recognizer, OnnxNerModelClient model,
                              NerProperties properties) {
        this.recognizer = recognizer;
        this.model = model;
        this.properties = properties;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", properties.getMode());
        result.put("provider", recognizer.provider());
        result.put("modelReady", model.isReady());
        result.put("modelVersion", recognizer.modelVersion());
        result.put("modelUnavailableReason", model.unavailableReason());
        result.put("pythonRuntimeRequired", false);
        return result;
    }
}
