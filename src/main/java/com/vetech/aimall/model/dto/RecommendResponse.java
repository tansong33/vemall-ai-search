package com.vetech.aimall.model.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 推荐接口出参 */
@Data
public class RecommendResponse {

    /** 面向用户的导购话术（含推荐理由/反问澄清） */
    private String reply;

    /** 推荐商品卡列表（严格来自真实召回结果，杜绝幻觉） */
    private List<ProductCard> products = new ArrayList<>();

    /** 结构化意图（调试与前端展示用） */
    private IntentResult intent;

    /** 是否需要用户补充信息 */
    private boolean needClarification;

    /** 命中缓存标记（观测成本用） */
    private boolean fromCache;
}
