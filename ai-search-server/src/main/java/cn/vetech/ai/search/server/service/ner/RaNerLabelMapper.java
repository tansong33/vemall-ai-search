package cn.vetech.ai.search.server.service.ner;

import cn.vetech.ai.search.server.config.NerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 将 RaNER 官方 config.json 中的细粒度中文类型映射为本项目的实体标签。
 *
 * <p>映射表放在 TSV 文件里，直接推理与二次微调复用同一份类型契约。</p>
 */
@Component
public class RaNerLabelMapper {

    private static final Logger log = LoggerFactory.getLogger(RaNerLabelMapper.class);

    private final NerProperties properties;
    private volatile Map<String, String> mappings = Collections.emptyMap();

    public RaNerLabelMapper(NerProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void initialize() {
        String location = properties.getModel().getLabelMappingPath();
        Resource resource = resource(location);
        if (resource == null || !resource.exists()) {
            log.warn("RaNER label mapping does not exist: {}", location);
            return;
        }

        Map<String, String> loaded = new HashMap<String, String>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String value = line.trim();
                if (value.isEmpty() || value.startsWith("#")) {
                    continue;
                }
                String[] parts = value.split("\\t", 2);
                if (parts.length != 2 || !StringUtils.hasText(parts[0])
                        || !StringUtils.hasText(parts[1])) {
                    continue;
                }
                loaded.put(parts[0].trim(), parts[1].trim().toUpperCase(Locale.ROOT));
            }
            mappings = Collections.unmodifiableMap(loaded);
            log.info("RaNER label mapping loaded: {} types", loaded.size());
        } catch (Exception e) {
            log.error("Failed to load RaNER label mapping {}", location, e);
        }
    }

    public String map(String label) {
        String rawType = rawType(label);
        String mapped = mappings.get(rawType);
        if (mapped != null) {
            return mapped;
        }

        // 二次微调后的模型可能直接输出本项目的英文标签
        String upper = rawType.toUpperCase(Locale.ROOT);
        if (upper.matches("[A-Z][A-Z0-9_]*")) {
            return upper;
        }

        // 映射文件缺失或损坏时的保守降级，避免整批实体退化成未知类型
        if (rawType.startsWith("品牌")) return "BRAND";
        if (rawType.startsWith("型号")) return "MODEL";
        if (rawType.startsWith("系列")) return "SERIES";
        if (rawType.startsWith("颜色")) return "COLOR";
        if (rawType.startsWith("材质")) return "MATERIAL";
        if (rawType.startsWith("尺寸规格")) return "SPEC";
        if (rawType.startsWith("功能功效")) return "FUNCTION";
        if (rawType.startsWith("款式") || rawType.startsWith("风格")) return "STYLE";
        if (rawType.startsWith("地点地域")) return "REGION";
        if (rawType.startsWith("人名")) return "PERSON";
        if (rawType.startsWith("组织机构")) return "ORGANIZATION";
        if (rawType.startsWith("产品_核心产品")) return "CATEGORY";
        if (rawType.startsWith("产品_其他")) return "PRODUCT";
        if (rawType.startsWith("产品_修饰产品") || rawType.startsWith("修饰")) return "MODIFIER";
        return "ATTRIBUTE";
    }

    /** 剥掉 BIOES 前缀，返回原始类型名。 */
    public String rawType(String label) {
        if (label == null) {
            return "";
        }
        String value = label.trim();
        if (value.length() > 2 && value.charAt(1) == '-'
                && "BIES".indexOf(Character.toUpperCase(value.charAt(0))) >= 0) {
            return value.substring(2);
        }
        return value;
    }

    private static Resource resource(String location) {
        if (!StringUtils.hasText(location)) {
            return null;
        }
        if (location.startsWith("classpath:")) {
            return new ClassPathResource(location.substring("classpath:".length()));
        }
        return new FileSystemResource(location);
    }
}
