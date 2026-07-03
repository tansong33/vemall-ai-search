package cn.vetech.aimall.service.recall;

import cn.vetech.aimall.model.dto.IntentResult;
import cn.vetech.aimall.model.dto.ScoredProduct;

import java.util.List;

/**
 * 召回通道接口（可插拔扩展点 #4）。
 * 当前实现：语义召回、关键词召回。
 * 扩展示例：以图搜图通道(图像向量)、协同过滤通道(用户行为)、运营位通道(人工置顶) ——
 * 实现本接口并注册为 Spring Bean 即自动纳入混合召回，无需改动编排代码。
 */
public interface RecallChannel {

    String name();

    List<ScoredProduct> recall(String rawQuery, IntentResult intent);
}
