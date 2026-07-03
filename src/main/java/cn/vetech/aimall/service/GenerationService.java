package cn.vetech.aimall.service;

import cn.vetech.aimall.llm.LlmClient;
import cn.vetech.aimall.model.dto.IntentResult;
import cn.vetech.aimall.model.dto.ProductCard;
import cn.vetech.aimall.model.dto.ScoredProduct;
import cn.vetech.aimall.model.entity.Product;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 生成层：把 意图 + 精排后的真实商品 交给 LLM 生成导购话术。
 *
 * 铁律：prompt 中明确"只能基于给定商品列表推荐，禁止编造"，这是 RAG 防幻觉的关键。
 * mock/降级模式：模板话术，保证链路永不中断。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GenerationService {

    private final LlmClient llmClient;

    private static final String GEN_SYSTEM_PROMPT =
            "你是企业福利商城的专业导购。根据用户需求和给定的候选商品列表，生成一段简洁友好的中文推荐话术：" +
            "先一句话复述理解到的需求，再逐个说明推荐理由(结合价格/属性/场景)，最后如有澄清问题则礼貌反问。" +
            "严格要求：只能推荐给定列表中的商品，严禁编造列表之外的任何商品、价格或参数；不要使用markdown。";

    public String generateReply(String rawQuery, IntentResult intent, List<ScoredProduct> topProducts) {
        // 需要澄清且完全无结果：直接反问
        if (topProducts.isEmpty()) {
            if (!intent.getClarifications().isEmpty()) {
                return "为了帮您推荐更合适的商品，想再确认一下：" + String.join("；", intent.getClarifications());
            }
            return "抱歉，商城里暂时没有完全符合这些条件的商品。可以放宽预算或换个类目试试，我再帮您找找。";
        }

        if (llmClient.isReal()) {
            try {
                String productContext = topProducts.stream()
                        .map(sp -> {
                            Product p = sp.getProduct();
                            return String.format("- [%d] %s | 类目:%s | 价格:%s元 | 属性:%s | 场景:%s | 卖点:%s",
                                    p.getId(), p.getTitle(), p.getCategory(), p.getPrice(),
                                    p.getAttrs(), p.getSceneTags(), p.getDescription());
                        })
                        .collect(Collectors.joining("\n"));
                String userPrompt = "用户需求: " + rawQuery
                        + "\n结构化意图: " + intent
                        + "\n候选商品列表(只能从中推荐):\n" + productContext;
                String reply = llmClient.chat(GEN_SYSTEM_PROMPT, userPrompt);
                if (reply != null && !reply.trim().isEmpty()) {
                    return reply.trim();
                }
                log.warn("LLM 生成为空，降级到模板话术");
            } catch (Exception e) {
                log.warn("LLM 生成异常，降级到模板话术: {}", e.getMessage());
            }
        }
        return templateReply(intent, topProducts);
    }

    /** 模板话术（mock 主路径 / 真实模式降级路径） */
    private String templateReply(IntentResult intent, List<ScoredProduct> top) {
        StringBuilder sb = new StringBuilder();
        sb.append("根据您的需求");
        if (!intent.getScenes().isEmpty()) sb.append("（").append(String.join("、", intent.getScenes())).append("场景");
        if (intent.getBudgetMax() != null) {
            sb.append(intent.getScenes().isEmpty() ? "（" : "，").append("预算").append(intent.getBudgetMax()).append("元以内");
        }
        if (!intent.getScenes().isEmpty() || intent.getBudgetMax() != null) sb.append("）");
        sb.append("，为您精选了 ").append(top.size()).append(" 款商品：\n");
        int i = 1;
        for (ScoredProduct sp : top) {
            Product p = sp.getProduct();
            sb.append(i++).append(". ").append(p.getTitle())
              .append("（").append(p.getPrice()).append("元）—— ")
              .append(shortReason(p, intent)).append("\n");
        }
        if (!intent.getClarifications().isEmpty()) {
            sb.append("另外想确认：").append(String.join("；", intent.getClarifications()));
        }
        return sb.toString();
    }

    /** 单品推荐理由（模板版）：优先引用场景标签的匹配点 */
    public String shortReason(Product p, IntentResult intent) {
        for (String scene : intent.getScenes()) {
            if (p.getSceneTags() != null && p.getSceneTags().contains(scene)) {
                return "契合「" + scene + "」场景，" + firstSentence(p.getDescription());
            }
        }
        return firstSentence(p.getDescription());
    }

    private String firstSentence(String desc) {
        if (desc == null || desc.isEmpty()) return "综合性价比之选。";
        int idx = desc.indexOf('，');
        int idx2 = desc.indexOf('。');
        int cut = idx > 0 ? idx : (idx2 > 0 ? idx2 : Math.min(30, desc.length()));
        return desc.substring(0, Math.min(cut + 1, desc.length()));
    }

    public ProductCard toCard(ScoredProduct sp, IntentResult intent) {
        Product p = sp.getProduct();
        ProductCard c = new ProductCard();
        c.setId(p.getId());
        c.setTitle(p.getTitle());
        c.setCategory(p.getCategory());
        c.setPrice(p.getPrice());
        c.setImageUrl(p.getImageUrl());
        c.setSceneTags(p.getSceneTags());
        c.setPointsEligible(p.getPointsEligible());
        c.setStock(p.getStock());
        c.setScore(Math.round(sp.getFinalScore() * 1000) / 1000.0);
        c.setReason(shortReason(p, intent));
        return c;
    }
}
