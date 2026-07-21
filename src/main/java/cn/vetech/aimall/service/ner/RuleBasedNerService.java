package cn.vetech.aimall.service.ner;

import cn.vetech.aimall.model.dto.IntentResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 毫秒级在线 NER：词典抽类目/品牌/场景，正则抽价格、商品 ID、容量和 B 端布尔条件。
 * 它是在线默认实现；未来蒸馏出的 BERT/ONNX 模型可实现相同输出协议后替换本类。
 */
@Service
@RequiredArgsConstructor
public class RuleBasedNerService implements IntentRecognizer {

    private static final Pattern EXACT_ID = Pattern.compile(
            "(?:商品(?:id|编号|货号)|id)\\s*[:：#]?\\s*(\\d{1,18})", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRICE_RANGE = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*(?:元|块)?\\s*(?:-|~|～|到|至)\\s*(\\d+(?:\\.\\d+)?)\\s*(?:元|块)?");
    private static final Pattern PRICE_MAX_PREFIX = Pattern.compile(
            "(?:预算|单价|每人|人均|价格)\\s*(?:是|为|在)?\\s*(\\d+(?:\\.\\d+)?)\\s*(?:元|块)?");
    private static final Pattern PRICE_MAX_SUFFIX = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*(?:元|块)?\\s*(?:以内|以下|之内|内|封顶|最多|不超过)");
    private static final Pattern PRICE_MIN_SUFFIX = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*(?:元|块)?\\s*(?:以上|起|至少|不低于)");
    private static final Pattern CAPACITY = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(ml|毫升|l|升)", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUANTITY = Pattern.compile("(\\d+)\\s*(?:人份|份|件|个|人)");
    private static final Pattern SPACE = Pattern.compile("\\s+");

    private static final Map<String, String> CATEGORY_ALIASES = new LinkedHashMap<>();
    private static final Map<String, String> SCENE_ALIASES = new LinkedHashMap<>();
    private static final List<String> COLORS = Arrays.asList(
            "深空灰", "藏青色", "藏青", "磨砂灰", "香槟金", "冰蓝色", "暖白色",
            "黑色", "白色", "红色", "蓝色", "绿色", "黄色", "橙色", "灰色", "粉色", "透明");
    private static final List<String> MATERIALS = Arrays.asList(
            "316不锈钢", "304不锈钢", "不锈钢", "tritan", "陶瓷", "玻璃", "纯钛", "棉麻", "实木", "硅胶");

    static {
        CATEGORY_ALIASES.put("保温杯", "水杯");
        CATEGORY_ALIASES.put("运动水壶", "水杯");
        CATEGORY_ALIASES.put("马克杯", "水杯");
        CATEGORY_ALIASES.put("杯子", "水杯");
        CATEGORY_ALIASES.put("小风扇", "小家电");
        CATEGORY_ALIASES.put("加湿器", "小家电");
        CATEGORY_ALIASES.put("空调被", "家纺");
        CATEGORY_ALIASES.put("凉席", "家纺");
        CATEGORY_ALIASES.put("按摩仪", "按摩健康");
        CATEGORY_ALIASES.put("筋膜枪", "按摩健康");
        CATEGORY_ALIASES.put("耳机", "数码");
        CATEGORY_ALIASES.put("充电宝", "数码");
        CATEGORY_ALIASES.put("口罩", "劳保用品");
        CATEGORY_ALIASES.put("手套", "劳保用品");
        CATEGORY_ALIASES.put("零食", "食品");
        CATEGORY_ALIASES.put("粽子", "食品");

        SCENE_ALIASES.put("员工福利", "员工福利");
        SCENE_ALIASES.put("新员工", "新人入职");
        SCENE_ALIASES.put("入职", "新人入职");
        SCENE_ALIASES.put("中秋", "中秋");
        SCENE_ALIASES.put("端午", "端午");
        SCENE_ALIASES.put("春节", "春节");
        SCENE_ALIASES.put("过年", "春节");
        SCENE_ALIASES.put("送礼", "送礼");
        SCENE_ALIASES.put("礼品", "送礼");
        SCENE_ALIASES.put("商务", "商务");
        SCENE_ALIASES.put("办公室", "办公");
        SCENE_ALIASES.put("办公", "办公");
        SCENE_ALIASES.put("通勤", "通勤");
        SCENE_ALIASES.put("出差", "差旅");
        SCENE_ALIASES.put("差旅", "差旅");
        SCENE_ALIASES.put("户外", "户外");
        SCENE_ALIASES.put("运动", "运动");
        SCENE_ALIASES.put("团建", "团建");
        SCENE_ALIASES.put("防晒", "防晒");
        SCENE_ALIASES.put("降暑", "降暑");
        SCENE_ALIASES.put("清凉", "降暑");
        SCENE_ALIASES.put("夏天", "夏季");
        SCENE_ALIASES.put("夏季", "夏季");
        SCENE_ALIASES.put("冬天", "冬季");
        SCENE_ALIASES.put("冬季", "冬季");
        SCENE_ALIASES.put("居家", "居家");
        SCENE_ALIASES.put("健康", "健康");
        SCENE_ALIASES.put("亲子", "亲子");
        SCENE_ALIASES.put("劳保", "劳保");
    }

    private final EntityDictionaryService dictionaryService;

    @Override
    public IntentResult extract(String rawQuery) {
        String query = normalize(rawQuery);
        IntentResult result = new IntentResult();

        Matcher idMatcher = EXACT_ID.matcher(query);
        if (idMatcher.find()) {
            try {
                result.setProductId(Long.valueOf(idMatcher.group(1)));
                result.setRoute("EXACT_ID");
            } catch (NumberFormatException ignored) {
                // 超过 long 的编号继续走普通搜索。
            }
        }

        String category = matchAlias(query, CATEGORY_ALIASES);
        if (category == null) category = dictionaryService.matchCategory(query);
        result.setCategory(category);
        result.setBrand(dictionaryService.matchBrand(query));

        extractPrices(query, result);
        extractScenes(query, result);
        extractAttributes(query, result);

        if (category != null) result.getKeywords().add(category);
        if (result.getBrand() != null) result.getKeywords().add(result.getBrand());
        result.getKeywords().addAll(result.getScenes());

        String searchText = appendCanonicalTerms(cleanSearchText(query), result.getKeywords());
        result.setSearchText(searchText);
        if (!StringUtils.hasText(searchText) && result.getProductId() == null
                && category == null && result.getBrand() == null) {
            result.getClarifications().add("请补充商品类目、品牌或用途");
        }
        return result;
    }

    public static String normalize(String raw) {
        if (raw == null) return "";
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .trim().toLowerCase(Locale.ROOT);
        return SPACE.matcher(normalized).replaceAll(" ");
    }

    private void extractPrices(String query, IntentResult result) {
        Matcher range = PRICE_RANGE.matcher(query);
        if (range.find()) {
            BigDecimal a = decimal(range.group(1));
            BigDecimal b = decimal(range.group(2));
            if (a != null && b != null) {
                result.setBudgetMin(a.min(b));
                result.setBudgetMax(a.max(b));
                return;
            }
        }
        Matcher maxSuffix = PRICE_MAX_SUFFIX.matcher(query);
        if (maxSuffix.find()) result.setBudgetMax(decimal(maxSuffix.group(1)));
        if (result.getBudgetMax() == null) {
            Matcher maxPrefix = PRICE_MAX_PREFIX.matcher(query);
            if (maxPrefix.find()) result.setBudgetMax(decimal(maxPrefix.group(1)));
        }
        Matcher minSuffix = PRICE_MIN_SUFFIX.matcher(query);
        if (minSuffix.find()) result.setBudgetMin(decimal(minSuffix.group(1)));
    }

    private void extractScenes(String query, IntentResult result) {
        for (Map.Entry<String, String> entry : SCENE_ALIASES.entrySet()) {
            if (query.contains(entry.getKey()) && !result.getScenes().contains(entry.getValue())) {
                result.getScenes().add(entry.getValue());
            }
        }
    }

    private void extractAttributes(String query, IntentResult result) {
        Matcher capacity = CAPACITY.matcher(query);
        if (capacity.find()) {
            result.getAttributes().put("容量", capacity.group(1) + capacity.group(2).toLowerCase(Locale.ROOT));
        }
        Matcher quantity = QUANTITY.matcher(query);
        if (quantity.find()) result.getAttributes().put("采购数量", quantity.group(1));
        putFirstContained(query, result, "颜色", COLORS);
        putFirstContained(query, result, "材质", MATERIALS);
        putBoolean(query, result, "可开专票", "开发票", "开专票", "专票", "发票");
        putBoolean(query, result, "可定制Logo", "定制logo", "定制 Logo", "印logo", "定制标志");
        if (query.contains("积分购买") || query.contains("福利积分")) {
            result.getAttributes().put("积分购买", "true");
        }
        if (query.contains("现货") || query.contains("马上发货")) {
            result.getAttributes().put("现货", "true");
        }
    }

    private void putBoolean(String query, IntentResult result, String key, String... aliases) {
        for (String alias : aliases) {
            if (query.contains(alias.toLowerCase(Locale.ROOT))) {
                result.getAttributes().put(key, "true");
                return;
            }
        }
    }

    private void putFirstContained(String query, IntentResult result, String key, List<String> values) {
        for (String value : values) {
            if (query.contains(value.toLowerCase(Locale.ROOT))) {
                result.getAttributes().put(key, value);
                return;
            }
        }
    }

    private String cleanSearchText(String query) {
        String cleaned = EXACT_ID.matcher(query).replaceAll(" ");
        cleaned = PRICE_RANGE.matcher(cleaned).replaceAll(" ");
        cleaned = PRICE_MAX_SUFFIX.matcher(cleaned).replaceAll(" ");
        cleaned = PRICE_MAX_PREFIX.matcher(cleaned).replaceAll(" ");
        cleaned = PRICE_MIN_SUFFIX.matcher(cleaned).replaceAll(" ");
        cleaned = cleaned.replaceAll("想要|我想买|帮我找|帮我搜|请推荐|推荐一下|有没有|适合|预算|左右", " ");
        cleaned = cleaned.replaceAll("要?现货|开发票|开专票|专票|发票|可?定制\\s*logo|印\\s*logo", " ");
        cleaned = cleaned.replaceAll("[，。！？,.!?;；:：]+", " ");
        return SPACE.matcher(cleaned).replaceAll(" ").trim();
    }

    private String appendCanonicalTerms(String searchText, List<String> canonicalTerms) {
        StringBuilder result = new StringBuilder(searchText == null ? "" : searchText);
        for (String term : canonicalTerms) {
            if (!StringUtils.hasText(term)) continue;
            String current = result.toString();
            if (!current.contains(term)) {
                if (result.length() > 0) result.append(' ');
                result.append(term);
            }
        }
        return result.toString().trim();
    }

    private String matchAlias(String query, Map<String, String> aliases) {
        String bestAlias = null;
        String value = null;
        for (Map.Entry<String, String> entry : aliases.entrySet()) {
            if (query.contains(entry.getKey())
                    && (bestAlias == null || entry.getKey().length() > bestAlias.length())) {
                bestAlias = entry.getKey();
                value = entry.getValue();
            }
        }
        return value;
    }

    private BigDecimal decimal(String value) {
        try {
            return new BigDecimal(value);
        } catch (Exception e) {
            return null;
        }
    }
}
