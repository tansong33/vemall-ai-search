package cn.vetech.ai.search.server.service.ner;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import cn.vetech.ai.search.server.service.dto.NerEntityDto;
import cn.vetech.ai.search.server.config.NerProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * ONNX NER 模型客户端
 *
 * 加载 ONNX 格式的 NER 模型，执行 BERT WordPiece 分词 + 模型推理 + BIOES 解码，
 * 将模型输出的 logits 转换为带置信度的 NerEntityDto 列表。
 *
 */
@Component
public class OnnxNerModelClient implements NerModelClient {

    private static final Logger log = LoggerFactory.getLogger(OnnxNerModelClient.class);

    private final NerProperties properties;
    private final ObjectMapper objectMapper;
    private final RaNerLabelMapper labelMapper;
    private OrtEnvironment environment;
    private OrtSession session;
    private BertWordPieceTokenizer tokenizer;
    private CrfViterbiDecoder crfDecoder;
    private List<String> labels = Collections.<String>emptyList();
    private Set<String> inputNames = Collections.<String>emptySet();
    private volatile String unavailableReason = "disabled";

    public OnnxNerModelClient(NerProperties properties, ObjectMapper objectMapper,
                              RaNerLabelMapper labelMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.labelMapper = labelMapper;
    }

    @PostConstruct
    public void initialize() {
        NerProperties.Model config = properties.getModel();
        if (!config.isEnabled()) {
            unavailableReason = "disabled by configuration";
            return;
        }
        Path model = Paths.get(config.getModelPath());
        Path vocabulary = Paths.get(config.getVocabularyPath());
        Path labelFile = Paths.get(config.getLabelsPath());
        Path crfFile = Paths.get(config.getCrfPath());
        if (!Files.isRegularFile(model) || !Files.isRegularFile(vocabulary)
                || !Files.isRegularFile(labelFile)) {
            unavailableReason = "model artifacts are incomplete";
            log.warn("ONNX NER is enabled but artifacts are missing: "
                            + "model={}, vocab={}, labels={}",
                    model, vocabulary, labelFile);
            return;
        }
        try {
            tokenizer = new BertWordPieceTokenizer(vocabulary, config.getMaxLength(),
                    config.isCharacterLevel());
            labels = loadLabels(labelFile);
            if (Files.isRegularFile(crfFile)) {
                crfDecoder = CrfViterbiDecoder.fromJson(
                        objectMapper.readTree(crfFile.toFile()));
                if (crfDecoder.size() != labels.size()) {
                    throw new IllegalArgumentException("CRF label count differs from labels file: "
                            + crfDecoder.size() + " != " + labels.size());
                }
            } else {
                // Token-classification checkpoints intentionally have no CRF artifact.
                // decodeLogits already implements argmax + confidence threshold + BIO repair.
                crfDecoder = null;
                log.info("CRF artifact not configured/found; using token argmax decoding: {}",
                        crfFile);
            }
            environment = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            session = environment.createSession(model.toString(), options);
            inputNames = new HashSet<String>(session.getInputNames());
            unavailableReason = null;
            log.info("ONNX NER loaded: version={}, inputs={}, outputs={}, labels={}",
                    modelVersion(), inputNames, session.getOutputNames(), labels.size());
        } catch (Exception | LinkageError e) {
            // Native ONNX Runtime may be absent, blocked by OS policy, or built for a
            // different architecture. Model startup must still degrade to dictionary
            // mode instead of aborting the whole Spring application.
            unavailableReason = e.getMessage();
            closeSession();
            log.error("Failed to initialize ONNX NER; dictionary fallback remains active", e);
        }
    }

    @Override
    public NerModelOutput predict(String query) {
        if (!isReady()) return new NerModelOutput(modelVersion(), 0, Collections.<NerEntityDto>emptyList());
        long started = System.nanoTime();
        NerProperties.Model config = properties.getModel();
        BertWordPieceTokenizer.Encoding encoding = tokenizer.encode(query);
        List<OnnxTensor> tensors = new ArrayList<OnnxTensor>();
        try {
            Map<String, OnnxTensor> inputs = new HashMap<String, OnnxTensor>();
            addInput(inputs, tensors, config.getInputIdsName(), encoding.getInputIds());
            addInput(inputs, tensors, config.getAttentionMaskName(), encoding.getAttentionMask());
            if (inputNames.contains(config.getTokenTypeIdsName())) {
                addInput(inputs, tensors, config.getTokenTypeIdsName(), encoding.getTokenTypeIds());
            }
            if (inputNames.contains(config.getLabelMaskName())) {
                addInput(inputs, tensors, config.getLabelMaskName(), encoding.getLabelMask());
            }
            if (inputNames.contains(config.getOffsetMappingName())) {
                addInput(inputs, tensors, config.getOffsetMappingName(),
                        encoding.getOffsetMapping());
            }
            OrtSession.Result result = session.run(inputs);
            try {
                OnnxValue output = output(result, config.getOutputName());
                Object value = output.getValue();
                List<NerEntityDto> entities;
                if (value instanceof float[][][]) {
                    entities = decodeLogits(query, (float[][][]) value,
                            encoding.getSpans(), config.getConfidenceThreshold());
                } else if (value instanceof long[][]) {
                    entities = decodePredictions(query, (long[][]) value,
                            encoding.getSpans());
                } else {
                    throw new IllegalStateException(
                            "Expected RaNER predictions long[batch][sequence] or "
                                    + "logits float[batch][sequence][labels] but got "
                                    + value.getClass().getName());
                }
                return new NerModelOutput(modelVersion(), elapsedMs(started), entities);
            } finally {
                closeQuietly(result);
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

    private void addInput(Map<String, OnnxTensor> inputs, List<OnnxTensor> tensors,
                          String name, long[][][] values) throws Exception {
        if (!inputNames.contains(name)) return;
        OnnxTensor tensor = OnnxTensor.createTensor(environment, values);
        tensors.add(tensor);
        inputs.put(name, tensor);
    }

    private void addInput(Map<String, OnnxTensor> inputs, List<OnnxTensor> tensors,
                          String name, boolean[][] values) throws Exception {
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

    private List<NerEntityDto> decodeLogits(String query, float[][][] batchLogits,
                                         List<BertWordPieceTokenizer.TokenSpan> spans,
                                         double threshold) {
        if (batchLogits.length == 0) return Collections.<NerEntityDto>emptyList();
        float[][] logits = batchLogits[0];
        if (crfDecoder != null) {
            return decodeCrfLogits(query, logits, spans);
        }
        List<TagPrediction> tags = new ArrayList<TagPrediction>(logits.length);
        for (float[] tokenLogits : logits) {
            Prediction prediction = prediction(tokenLogits);
            tags.add(new TagPrediction(prediction.label, prediction.confidence));
        }
        return decodeTags(query, tags, spans, threshold);
    }

    private List<NerEntityDto> decodeCrfLogits(
            String query, float[][] logits,
            List<BertWordPieceTokenizer.TokenSpan> spans) {
        int length = Math.min(logits.length, spans.size());
        List<Integer> tokenPositions = new ArrayList<Integer>();
        for (int position = 0; position < length; position++) {
            if (!spans.get(position).isSpecial()) tokenPositions.add(position);
        }
        if (tokenPositions.isEmpty()) return Collections.<NerEntityDto>emptyList();

        float[][] emissions = new float[tokenPositions.size()][];
        for (int index = 0; index < tokenPositions.size(); index++) {
            emissions[index] = logits[tokenPositions.get(index)];
        }
        int[] predictions = crfDecoder.decode(emissions);
        List<TagPrediction> tags = new ArrayList<TagPrediction>(logits.length);
        for (int position = 0; position < logits.length; position++) {
            tags.add(new TagPrediction("O", null));
        }
        for (int index = 0; index < predictions.length; index++) {
            int prediction = predictions[index];
            String label = prediction >= 0 && prediction < labels.size()
                    ? labels.get(prediction) : "O";
            tags.set(tokenPositions.get(index), new TagPrediction(label, null));
        }
        return decodeTags(query, tags, spans, 0);
    }

    private List<NerEntityDto> decodePredictions(String query, long[][] batchPredictions,
                                              List<BertWordPieceTokenizer.TokenSpan> spans) {
        if (batchPredictions.length == 0) return Collections.<NerEntityDto>emptyList();
        long[] predictions = batchPredictions[0];
        List<TagPrediction> tags = new ArrayList<TagPrediction>(predictions.length);
        for (long prediction : predictions) {
            String label = prediction >= 0 && prediction < labels.size()
                    ? labels.get((int) prediction) : "O";
            tags.add(new TagPrediction(label, null));
        }
        return decodeTags(query, tags, spans, 0);
    }

    private List<NerEntityDto> decodeTags(String query, List<TagPrediction> tags,
                                       List<BertWordPieceTokenizer.TokenSpan> spans,
                                       double threshold) {
        List<NerEntityDto> result = new ArrayList<NerEntityDto>();
        Accumulator current = null;
        int length = Math.min(tags.size(), spans.size());
        for (int i = 0; i < length; i++) {
            BertWordPieceTokenizer.TokenSpan span = spans.get(i);
            if (span.isSpecial()) {
                current = finish(query, result, current, threshold);
                continue;
            }
            TagPrediction prediction = tags.get(i);
            String rawLabel = prediction.label;
            if ("O".equalsIgnoreCase(rawLabel) || rawLabel.isEmpty()) {
                current = finish(query, result, current, threshold);
                continue;
            }
            String prefix = "";
            String rawType = rawLabel;
            int separator = rawLabel.indexOf('-');
            if (separator > 0) {
                prefix = rawLabel.substring(0, separator).toUpperCase(Locale.ROOT);
                rawType = rawLabel.substring(separator + 1);
            }
            String type = labelMapper.map(rawType);

            boolean begins = "B".equals(prefix) || "S".equals(prefix)
                    || current == null || !current.rawLabel.equals(rawType)
                    || span.getStart() > current.end;
            if (begins) {
                current = finish(query, result, current, threshold);
                current = new Accumulator(type, rawType, span.getStart(), span.getEnd(),
                        prediction.confidence);
            } else {
                current.end = Math.max(current.end, span.getEnd());
                if (prediction.confidence != null) {
                    current.confidenceSum += prediction.confidence;
                }
                current.tokenCount++;
            }
            if ("S".equals(prefix) || "E".equals(prefix)) {
                current = finish(query, result, current, threshold);
            }
        }
        finish(query, result, current, threshold);
        Collections.sort(result, new Comparator<NerEntityDto>() {
            @Override
            public int compare(NerEntityDto a, NerEntityDto b) {
                return Integer.compare(a.getStart(), b.getStart());
            }
        });
        return result;
    }

    private Accumulator finish(String query, List<NerEntityDto> target, Accumulator value, double threshold) {
        if (value == null) return null;
        Double confidence = value.hasConfidence
                ? value.confidenceSum / Math.max(1, value.tokenCount) : null;
        if ((confidence == null || confidence >= threshold)
                && value.start >= 0 && value.end <= query.length()
                && value.start < value.end) {
            NerEntityDto entity = new NerEntityDto(query.substring(value.start, value.end), value.label,
                    value.start, value.end, "raner-onnx", confidence);
            entity.setRawLabel(value.rawLabel);
            target.add(entity);
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
        List<String> result = new ArrayList<String>();
        if (source.isArray()) {
            for (JsonNode node : source) result.add(node.asText());
        } else if (source.isObject()) {
            List<Map.Entry<Integer, String>> indexed = new ArrayList<Map.Entry<Integer, String>>();
            Iterator<Map.Entry<String, JsonNode>> fields = source.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                indexed.add(new AbstractMap.SimpleEntry<Integer, String>(
                        Integer.parseInt(field.getKey()), field.getValue().asText()));
            }
            Collections.sort(indexed, new Comparator<Map.Entry<Integer, String>>() {
                @Override
                public int compare(Map.Entry<Integer, String> a, Map.Entry<Integer, String> b) {
                    return Integer.compare(a.getKey(), b.getKey());
                }
            });
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
        return "raner-onnx";
    }

    @Override
    public String modelVersion() {
        return properties.getModel().getVersion();
    }

    @Override
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
        crfDecoder = null;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try { closeable.close(); } catch (Exception ignored) { }
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

    private static final class TagPrediction {
        private final String label;
        private final Double confidence;

        private TagPrediction(String label, Double confidence) {
            this.label = label;
            this.confidence = confidence;
        }
    }

    private static final class Accumulator {
        private final String label;
        private final String rawLabel;
        private final int start;
        private int end;
        private double confidenceSum;
        private int tokenCount = 1;
        private final boolean hasConfidence;

        private Accumulator(String label, String rawLabel, int start, int end,
                            Double confidence) {
            this.label = label;
            this.rawLabel = rawLabel;
            this.start = start;
            this.end = end;
            this.hasConfidence = confidence != null;
            this.confidenceSum = confidence == null ? 0 : confidence;
        }
    }
}
