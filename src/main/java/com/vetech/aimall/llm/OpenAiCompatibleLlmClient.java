package com.vetech.aimall.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vetech.aimall.config.AiMallProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容协议实现（/v1/chat/completions）。
 * 通义千问(百炼兼容模式)、豆包(方舟)、DeepSeek、智谱、Moonshot、GPT 等均支持该协议，
 * 因此这一个实现即可覆盖绝大多数厂商 —— 换厂商只需改 base-url / api-key / 模型名。
 */
@Slf4j
public class OpenAiCompatibleLlmClient implements LlmClient {

    private final AiMallProperties.Llm cfg;
    private final RestTemplate restTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenAiCompatibleLlmClient(AiMallProperties.Llm cfg, RestTemplate restTemplate) {
        this.cfg = cfg;
        this.restTemplate = restTemplate;
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(msg("system", systemPrompt));
        messages.add(msg("user", userPrompt));
        return call(cfg.getChatModel(), messages);
    }

    @Override
    public String chatWithImage(String systemPrompt, String userPrompt, String imageBase64, String mimeType) {
        if (imageBase64 == null || imageBase64.isEmpty()) {
            return chat(systemPrompt, userPrompt);
        }
        // OpenAI vision 消息格式：content 为数组，包含 text 与 image_url(data URI)
        List<Object> content = new ArrayList<>();
        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("type", "text");
        textPart.put("text", userPrompt);
        content.add(textPart);

        Map<String, Object> imgUrl = new LinkedHashMap<>();
        String mime = mimeType == null ? "image/jpeg" : mimeType;
        imgUrl.put("url", "data:" + mime + ";base64," + imageBase64);
        Map<String, Object> imgPart = new LinkedHashMap<>();
        imgPart.put("type", "image_url");
        imgPart.put("image_url", imgUrl);
        content.add(imgPart);

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(msg("system", systemPrompt));
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("role", "user");
        user.put("content", content);
        messages.add(user);

        return call(cfg.getVisionModel(), messages);
    }

    private Map<String, Object> msg(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    private String call(String model, List<Map<String, Object>> messages) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", messages);
            body.put("temperature", 0.2);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(cfg.getApiKey());

            String url = cfg.getBaseUrl() + "/chat/completions";
            String resp = restTemplate.postForObject(url, new HttpEntity<>(body, headers), String.class);
            JsonNode root = mapper.readTree(resp);
            return root.path("choices").path(0).path("message").path("content").asText("");
        } catch (Exception e) {
            log.error("LLM call failed: {}", e.getMessage());
            // 失败降级：返回空串，上层各自兜底（意图层退回规则抽取、生成层退回模板话术）
            return "";
        }
    }

    @Override
    public boolean isReal() {
        return true;
    }
}
