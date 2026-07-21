package cn.vetech.aimall.model.dto;

import lombok.Data;

/** 推荐接口入参 */
@Data
public class RecommendRequest {

    /** 会话 id（预留：多轮对话上下文与个性化的挂载点） */
    private String sessionId;

    /** 用户文字输入 */
    private String query;

}
