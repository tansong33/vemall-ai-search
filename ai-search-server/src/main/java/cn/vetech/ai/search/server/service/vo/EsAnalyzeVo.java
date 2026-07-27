package cn.vetech.ai.search.server.service.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** ES _analyze 的分词结果，调试链路展示用。 */
@Data
public class EsAnalyzeVo {

    private String analyzer;
    private List<TokenInfo> tokens = new ArrayList<>();
    private long costMs;

    @Data
    public static class TokenInfo {
        private String term;
        private int startOffset;
        private int endOffset;
        private int position;
        private String type;
    }
}
