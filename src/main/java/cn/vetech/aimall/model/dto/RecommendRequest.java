package cn.vetech.aimall.model.dto;

import lombok.Data;

/** 推荐接口入参 */
@Data
public class RecommendRequest {

    /** 会话 id（预留：多轮对话上下文与个性化的挂载点） */
    private String sessionId;

    /** 生产调用必须传；demo 可为空。参与 SQL/ES 过滤和缓存隔离。 */
    private String tenantCode;

    /** 商城渠道编码。参与 SQL/ES 过滤和缓存隔离。 */
    private String channelCode;

    /** 用户文字输入 */
    private String query;

}
