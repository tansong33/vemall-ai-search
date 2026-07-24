package com.aisearch.ner;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 进程内 ONNX 推理，不需要 Python 服务。
 *
 * <p>加载 {@code export_onnx.py} 产出的 bundle 目录：model.onnx + tokenizer.json +
 * labels.json + ner_manifest.json。所有解码参数（tau_accept、max_length）都从 manifest
 * 读，**不要在 Java 里写死** —— 换模型时只换目录，不改代码。
 *
 * <p>线程安全：OrtSession 本身线程安全；HuggingFaceTokenizer 也是。可以做成单例 Bean。
 */
public class OnnxNerService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OnnxNerService.class);

    private final OrtEnvironment env;
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;
    private final BioDecoder decoder;
    private final int maxLength;
    private final String modelVersion;

    public OnnxNerService(Path bundleDir) throws Exception {
        this(bundleDir, "model.onnx", 1);
    }

    public OnnxNerService(Path bundleDir, String modelFile, int intraOpThreads) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode labels = mapper.readTree(Files.readString(bundleDir.resolve("labels.json")));
        JsonNode manifest = mapper.readTree(Files.readString(bundleDir.resolve("ner_manifest.json")));

        List<String> tagList = new ArrayList<>();
        labels.get("tags").forEach(n -> tagList.add(n.asText()));
        double tauAccept = manifest.path("decode").path("tau_accept").asDouble(0.60);
        this.maxLength = manifest.path("max_length").asInt(64);
        this.modelVersion = manifest.path("model_version").asText("unknown");
        this.decoder = new BioDecoder(tagList.toArray(new String[0]), tauAccept);

        this.tokenizer = HuggingFaceTokenizer.builder()
                .optTokenizerPath(bundleDir.resolve("tokenizer.json"))
                .optAddSpecialTokens(true)
                .optTruncation(true)
                .optMaxLength(maxLength)
                .build();

        this.env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(intraOpThreads);
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        this.session = env.createSession(bundleDir.resolve(modelFile).toString(), opts);

        log.info("NER ONNX loaded: version={} tags={} maxLength={} tauAccept={}",
                modelVersion, tagList.size(), maxLength, tauAccept);
    }

    public String getModelVersion() {
        return modelVersion;
    }

    /** 单条查询。offset 指向传入的原始 query。 */
    public List<NerEntity> recognize(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        // 归一化只为了让输入分布和训练一致；它是等长的，所以 offset 仍然指向原串。
        String normalized = TextNormalizer.normalize(query);
        Encoding enc = tokenizer.encode(normalized);

        long[] ids = enc.getIds();
        long[] attentionMask = enc.getAttentionMask();
        long[] specialMask = enc.getSpecialTokenMask();
        var charSpans = enc.getCharTokenSpans();

        int n = ids.length;
        int[] tokenStart = new int[n];
        int[] tokenEnd = new int[n];
        boolean[] isSpecial = new boolean[n];
        for (int i = 0; i < n; i++) {
            isSpecial[i] = specialMask[i] == 1L;
            var span = (charSpans != null && i < charSpans.length) ? charSpans[i] : null;
            tokenStart[i] = span == null ? 0 : span.getStart();
            tokenEnd[i] = span == null ? 0 : span.getEnd();
        }

        try (OnnxTensor idTensor = OnnxTensor.createTensor(env, new long[][]{ids});
             OnnxTensor maskTensor = OnnxTensor.createTensor(env, new long[][]{attentionMask});
             OrtSession.Result result = session.run(
                     java.util.Map.of("input_ids", idTensor, "attention_mask", maskTensor))) {

            long[][] tagIds = (long[][]) result.get(1).getValue();       // tag_ids
            float[][] confidence = (float[][]) result.get(2).getValue(); // confidence
            return decoder.decode(query, tagIds[0], confidence[0], tokenStart, tokenEnd, isSpecial);

        } catch (Exception e) {
            log.warn("ONNX inference failed for query={}, returning empty. cause={}",
                    query, e.toString());
            return List.of();   // 调用方据此回退到现有词典 NER
        }
    }

    @Override
    @PreDestroy
    public void close() throws Exception {
        if (session != null) session.close();
        if (tokenizer != null) tokenizer.close();
    }
}
