package cn.vetech.ai.search.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 绑定 NER 的词典、模型与实体标准化配置。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ai-search.ner")
public class NerProperties {

    private String mode = "hybrid";
    private String dictionaryPath = "classpath:ner_dict.txt";
    private String overlayDictionaryPath = "classpath:ner/ner_dict_overrides.tsv";
    private Model model = new Model();
    private Normalization normalization = new Normalization();

    @Data
    public static class Model {
        private boolean enabled;
        private String modelPath = "/app/models/ner/model.onnx";
        private String vocabularyPath = "/app/models/ner/vocab.txt";
        private String labelsPath = "/app/models/ner/config.json";
        private String crfPath = "/app/models/ner/crf.json";
        private String labelMappingPath = "classpath:ner/raner-label-mapping.tsv";
        private int maxLength = 64;
        private double confidenceThreshold = 0.75;
        private String version = "none";
        private String inputIdsName = "input_ids";
        private String attentionMaskName = "attention_mask";
        private String tokenTypeIdsName = "token_type_ids";
        private String labelMaskName = "label_mask";
        private String offsetMappingName = "offset_mapping";
        private String outputName = "logits";
        private boolean characterLevel = true;
    }

    @Data
    public static class Normalization {
        private boolean enabled = true;
        private String dictionaryPath = "classpath:ner/entity-normalization.tsv";
        private String version = "normalization-v1";
    }
}
