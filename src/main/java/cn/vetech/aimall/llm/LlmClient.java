package cn.vetech.aimall.llm;

/**
 * 大模型统一接口 —— 系统唯一的 LLM 出口（可插拔扩展点 #1）。
 *
 * 想换厂商/模型：新增一个实现类并在 yml 里把 aimall.llm.provider 切过去即可，
 * 上层的意图理解、话术生成代码一行都不用改。
 */
public interface LlmClient {

    /** 纯文本对话 */
    String chat(String systemPrompt, String userPrompt);

    /**
     * 图文对话（VLM）。
     * @param imageBase64 图片 base64（不含 data: 前缀），null 时等价于 chat()
     * @param mimeType    如 image/jpeg
     */
    String chatWithImage(String systemPrompt, String userPrompt, String imageBase64, String mimeType);

    /** 当前实现是否具备真实模型能力（mock 返回 false，用于日志与降级提示） */
    boolean isReal();
}
