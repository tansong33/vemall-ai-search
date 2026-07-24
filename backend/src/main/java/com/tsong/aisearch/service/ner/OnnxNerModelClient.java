package com.tsong.aisearch.service.ner;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tsong.aisearch.config.AiSearchProperties;
import com.tsong.aisearch.model.dto.NerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class OnnxNerModelClient implements NerModelClient {

    private static final Logger log = LoggerFactory.getLogger(OnnxNerModelClient.class);

    private final AiSearchProperties properties;
    private final ObjectMapper objectMapper;
    private OrtEnvironment environment;
    private OrtSession session;
    private BertWordPieceTokenizer tokenizer;
    private List<String> labels = Collections.emptyList();
    private Set<String> inputNames = Collections.emptySet();
    private volatile String unavailableReason = "disabled";

    public OnnxNerModelClient(AiSearchProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void initialize() {
        AiSearchProperties.Model config = properties.getNer().getModel();
        if (!config.isEnabled()) {
            unavailableReason = "disabled by configuration";
            return;
        }
        Path model = Paths.get(config.getModelPath());
        Path vocabulary = Paths.get(config.getVocabularyPath());
        Path labelFile = Paths.get(config.getLabelsPath());
        if (!Files.isRegularFile(model) || !Files.isRegularFile(vocabulary)
                || !Files.isRegularFile(labelFile)) {
            unavailableReason = "model artifacts are incomplete";
            log.warn("ONNX NER is enabled but artifacts are missing: model={}, vocab={}, labels={}",
                    model, vocabulary, labelFile);
            return;
        }
        try {
            tokenizer = new BertWordPieceTokenizer(vocabulary, config.getMaxLength());
            labels = loadLabels(labelFile);
            environment = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            session = environment.createSession(model.toString(), options);
            inputNames = new HashSet<>(session.getInputNames());
            unavailableReason = null;
            log.info("ONNX NER loaded: version={}, inputs={}, outputs={}, labels={}",
                    modelVersion(), inputNames, session.getOutputNames(), labels.size());
        } catch (Exception e) {
            unavailableReason = e.getMessage();
            closeSession();
            log.error("Failed to initialize ONNX NER; dictionary fallback remains active", e);
        }
    }

    @Override
    public NerModelOutput predict(String query) {
        if (!isReady()) return new NerModelOutput(modelVersion(), 0, Collections.<NerEntity>emptyList());
        long started = System.nanoTime();
        AiSearchProperties.Model config = properties.getNer().getModel();
        BertWordPieceTokenizer.Encoding encoding = tokenizer.encode(query);
        List<OnnxTensor> tensors = new ArrayList<>();
        try {
            Map<String, OnnxTensor> inputs = new HashMap<>();
            addInput(inputs, tensors, config.getInputIdsName(), encoding.getInputIds());
            addInput(inputs, tensors, config.getAttentionMaskName(), encoding.getAttentionMask());
            if (inputNames.contains(config.getTokenTypeIdsName())) {
                addInput(inputs, tensors, config.getTokenTypeIdsName(), encoding.getTokenTypeIds());
            }
            try (OrtSession.Result result = session.run(inputs)) {
                OnnxValue output = output(result, config.getOutputName());
                Object value = output.getValue();
                if (!(value instanceof float[][][])) {
                    throw new IllegalStateException("Expected float[batch][sequence][labels] logits but got "
                            + value.getClass().getName());
                }
                List<NerEntity> entities = decode(query, (float[][][]) value,
                        encoding.getSpans(), config.getConfidenceThreshold());
                return new NerModelOutput(modelVersion(), elapsedMs(started), entities);
            }
        } catch (Exception e) {
            throw new IllegalStateException("ONNX NER inference failed", e);
        } finally {
            for (OnnxTensor tensor : tensors) {
                try {
                    tensor.close();
                } catch (Exception ignored) {
                    // Best-effort native buffer cleanup.
                }
            }
        }
    }

    private void addInput(Map<String, OnnxTensor> inputs, List<OnnxTensor> tensors,
                          String name, long[][] values) throws Exception {
        if (!inputNames.contains(name)) return;
        OnnxTensor tensor = OnnxTensor.createTensor(environment, values);
        tensors.add(tensor);
        inputs.put(name, tensor);
    }

    private OnnxValue output(OrtSession.Result result, String configuredName) {
        Optional<OnnxValue> configured = result.get(configuredName);
        if (configured.isPresent()) return configured.get();
        if (result.size() == 0) throw new IllegalStateException("ONNX model returned no outputs");
        return result.get(0);
    }

    private List<NerEntity> decode(String query, float[][][] batchLogits,
                                   List<BertWordPieceTokenizer.TokenSpan> spans,
                                   double threshold) {
        if (batchLogits.length == 0) return Collections.emptyList();
        float[][] logits = batchLogits[0];
        List<NerEntity> result = new ArrayList<>();
        Accumulator current = null;
        int length = Math.min(logits.length, spans.size());
        for (int i = 0; i < length; i++) {
            BertWordPieceTokenizer.TokenSpan span = spans.get(i);
            if (span.isSpecial()) {
                current = finish(query, result, current, threshold);
                continue;
            }
            Prediction prediction = prediction(logits[i]);
            String rawLabel = prediction.label;
            if ("O".equalsIgnoreCase(rawLabel) || rawLabel.isEmpty()) {
                current = finish(query, result, current, threshold);
                continue;
            }
            String prefix = "";
            String type = rawLabel;
            int separator = rawLabel.indexOf('-');
            if (separator > 0) {
                prefix = rawLabel.substring(0, separator).toUpperCase();
                type = rawLabel.substring(separator + 1).toUpperCase();
            } else {
                type = type.toUpperCase();
            }

            boolean begins = "B".equals(prefix) || "S".equals(prefix)
                    || current == null || !current.label.equals(type)
                    || span.getStart() > current.end;
            if (begins) {
                current = finish(query, result, current, threshold);
                current = new Accumulator(type, span.getStart(), span.getEnd(), prediction.confidence);
            } else {
                current.end = Math.max(current.end, span.getEnd());
                current.confidenceSum += prediction.confidence;
                current.tokenCount++;
            }
            if ("S".equals(prefix) || "E".equals(prefix)) {
                current = finish(query, result, current, threshold);
            }
        }
        finish(query, result, current, threshold);
        result.sort(Comparator.comparingInt(NerEntity::getStart));
        return result;
    }

    private Accumulator finish(String query, List<NerEntity> target, Accumulator value, double threshold) {
        if (value == null) return null;
        double confidence = value.confidenceSum / Math.max(1, value.tokenCount);
        if (confidence >= threshold && value.start >= 0 && value.end <= query.length()
                && value.start < value.end) {
            target.add(new NerEntity(query.substring(value.start, value.end), value.label,
                    value.start, value.end, "onnx", confidence));
        }
        return null;
    }

    private Prediction prediction(float[] logits) {
        if (logits.length == 0) return new Prediction("O", 0);
        int best = 0;
        float max = logits[0];
        for (int i = 1; i < logits.length; i++) {
            if (logits[i] > max) {
                max = logits[i];
                best = i;
            }
        }
        double denominator = 0;
        for (float logit : logits) denominator += Math.exp(logit - max);
        double confidence = denominator == 0 ? 0 : 1.0 / denominator;
        String label = best < labels.size() ? labels.get(best) : "O";
        return new Prediction(label, confidence);
    }

    private List<String> loadLabels(Path path) throws Exception {
        JsonNode root = objectMapper.readTree(path.toFile());
        JsonNode source = root.has("id2label") ? root.get("id2label") : root;
        List<String> result = new ArrayList<>();
        if (source.isArray()) {
            for (JsonNode node : source) result.add(node.asText());
        } else if (source.isObject()) {
            List<Map.Entry<Integer, String>> indexed = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> fields = source.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                indexed.add(new java.util.AbstractMap.SimpleEntry<>(
                        Integer.parseInt(field.getKey()), field.getValue().asText()));
            }
            indexed.sort(Comparator.comparingInt(Map.Entry::getKey));
            for (Map.Entry<Integer, String> entry : indexed) {
                while (result.size() < entry.getKey()) result.add("O");
                result.add(entry.getValue());
            }
        }
        if (result.isEmpty()) throw new IllegalArgumentException("labels.json is empty");
        return Collections.unmodifiableList(result);
    }

    @Override
    public boolean isReady() {
        return session != null && tokenizer != null && !labels.isEmpty();
    }

    @Override
    public String provider() {
        return "onnx";
    }

    @Override
    public String modelVersion() {
        return properties.getNer().getModel().getVersion();
    }

    public String unavailableReason() {
        return unavailableReason;
    }

    @PreDestroy
    public void closeSession() {
        if (session != null) {
            try {
                session.close();
            } catch (Exception e) {
                log.debug("Error closing ONNX session", e);
            } finally {
                session = null;
            }
        }
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private static final class Prediction {
        private final String label;
        private final double confidence;

        private Prediction(String label, double confidence) {
            this.label = label;
            this.confidence = confidence;
        }
    }

    private static final class Accumulator {
        private final String label;
        private final int start;
        private int end;
        private double confidenceSum;
        private int tokenCount = 1;

        private Accumulator(String label, int start, int end, double confidence) {
            this.label = label;
            this.start = start;
            this.end = end;
            this.confidenceSum = confidence;
        }
    }
}
