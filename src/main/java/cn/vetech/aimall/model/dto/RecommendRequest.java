package cn.vetech.aimall.model.dto;

import lombok.Data;

/** 推荐接口入参 */
@Data
public class RecommendRequest {

    /** 会话 id（预留：多轮对话上下文与个性化的挂载点） */
    private String sessionId;

    /** 用户文字输入 */
    private String query;

    /** 可选：图片 base64（不含 data: 前缀），有图时走 VLM 图片理解 */
    private String imageBase64;

    /** 可选：图片 MIME，例如 image/jpeg */
    private String imageMimeType;
}
