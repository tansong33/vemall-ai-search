package cn.vetech.aimall.llm;

/**
 * 大模型统一接口 —— 系统唯一的 LLM 出口（可插拔扩展点 #1）。
 * 换厂商：新增实现类并在工厂切换（OpenAI 兼容实现已通吃主流厂商，通常只需改 yml 的 base-url/模型名）。
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
}
