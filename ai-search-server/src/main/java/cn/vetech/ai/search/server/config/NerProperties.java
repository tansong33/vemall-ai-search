package cn.vetech.ai.search.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 绑定 NER 的词典、模型与实体标准化配置。
 */
@Configuration
@ConfigurationProperties(prefix = "ai-search.ner")
public class NerProperties {

    private String mode = "hybrid";
    private String dictionaryPath = "classpath:ner_dict.txt";
    private String overlayDictionaryPath = "classpath:ner/ner_dict_overrides.tsv";
    private Model model = new Model();
    private Normalization normalization = new Normalization();

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getDictionaryPath() {
        return dictionaryPath;
    }

    public void setDictionaryPath(String dictionaryPath) {
        this.dictionaryPath = dictionaryPath;
    }

    public String getOverlayDictionaryPath() {
        return overlayDictionaryPath;
    }

    public void setOverlayDictionaryPath(String overlayDictionaryPath) {
        this.overlayDictionaryPath = overlayDictionaryPath;
    }

    public Model getModel() {
        return model;
    }

    public void setModel(Model model) {
        this.model = model;
    }

    public Normalization getNormalization() {
        return normalization;
    }

    public void setNormalization(Normalization normalization) {
        this.normalization = normalization;
    }

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

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getModelPath() {
            return modelPath;
        }

        public void setModelPath(String modelPath) {
            this.modelPath = modelPath;
        }

        public String getVocabularyPath() {
            return vocabularyPath;
        }

        public void setVocabularyPath(String vocabularyPath) {
            this.vocabularyPath = vocabularyPath;
        }

        public String getLabelsPath() {
            return labelsPath;
        }

        public void setLabelsPath(String labelsPath) {
            this.labelsPath = labelsPath;
        }

        public String getCrfPath() {
            return crfPath;
        }

        public void setCrfPath(String crfPath) {
            this.crfPath = crfPath;
        }

        public String getLabelMappingPath() {
            return labelMappingPath;
        }

        public void setLabelMappingPath(String labelMappingPath) {
            this.labelMappingPath = labelMappingPath;
        }

        public int getMaxLength() {
            return maxLength;
        }

        public void setMaxLength(int maxLength) {
            this.maxLength = maxLength;
        }

        public double getConfidenceThreshold() {
            return confidenceThreshold;
        }

        public void setConfidenceThreshold(double confidenceThreshold) {
            this.confidenceThreshold = confidenceThreshold;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public String getInputIdsName() {
            return inputIdsName;
        }

        public void setInputIdsName(String inputIdsName) {
            this.inputIdsName = inputIdsName;
        }

        public String getAttentionMaskName() {
            return attentionMaskName;
        }

        public void setAttentionMaskName(String attentionMaskName) {
            this.attentionMaskName = attentionMaskName;
        }

        public String getTokenTypeIdsName() {
            return tokenTypeIdsName;
        }

        public void setTokenTypeIdsName(String tokenTypeIdsName) {
            this.tokenTypeIdsName = tokenTypeIdsName;
        }

        public String getLabelMaskName() {
            return labelMaskName;
        }

        public void setLabelMaskName(String labelMaskName) {
            this.labelMaskName = labelMaskName;
        }

        public String getOffsetMappingName() {
            return offsetMappingName;
        }

        public void setOffsetMappingName(String offsetMappingName) {
            this.offsetMappingName = offsetMappingName;
        }

        public String getOutputName() {
            return outputName;
        }

        public void setOutputName(String outputName) {
            this.outputName = outputName;
        }

        public boolean isCharacterLevel() {
            return characterLevel;
        }

        public void setCharacterLevel(boolean characterLevel) {
            this.characterLevel = characterLevel;
        }
    }

    public static class Normalization {
        private boolean enabled = true;
        private String dictionaryPath = "classpath:ner/entity-normalization.tsv";
        private String version = "normalization-v1";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getDictionaryPath() {
            return dictionaryPath;
        }

        public void setDictionaryPath(String dictionaryPath) {
            this.dictionaryPath = dictionaryPath;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }
    }
}
