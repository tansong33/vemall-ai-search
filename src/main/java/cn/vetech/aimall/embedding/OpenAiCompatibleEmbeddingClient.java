package cn.vetech.aimall.embedding;

import cn.vetech.aimall.config.AiMallProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** OpenAI 兼容协议 /v1/embeddings 实现（百炼 text-embedding-v3 等均兼容） */
@Slf4j
public class OpenAiCompatibleEmbeddingClient implements EmbeddingClient {

    private final AiMallProperties.Embedding cfg;
    private final RestTemplate restTemplate;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile int dim;

    public OpenAiCompatibleEmbeddingClient(AiMallProperties.Embedding cfg, RestTemplate restTemplate) {
        this.cfg = cfg;
        this.restTemplate = restTemplate;
        this.dim = cfg.getDimension();
    }

    @Override
    public float[] embed(String text) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", cfg.getModel());
            body.put("input", Collections.singletonList(text));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(cfg.getApiKey());

            String resp = restTemplate.postForObject(cfg.getBaseUrl() + "/embeddings",
                    new HttpEntity<>(body, headers), String.class);
            JsonNode arr = mapper.readTree(resp).path("data").path(0).path("embedding");
            float[] v = new float[arr.size()];
            double norm = 0;
            for (int i = 0; i < arr.size(); i++) {
                v[i] = (float) arr.get(i).asDouble();
                norm += v[i] * v[i];
            }
            norm = Math.sqrt(norm);
            if (norm > 1e-9) {
                for (int i = 0; i < v.length; i++) v[i] /= norm;
            }
            this.dim = v.length;
            return v;
        } catch (Exception e) {
            log.error("Embedding call failed: {}", e.getMessage());
            return new float[dim];
        }
    }

    @Override
    public int dimension() {
        return dim;
    }
}
