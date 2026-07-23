package cn.vetech.aimall.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 一条联想候选。matchedText 可能是别名，text 始终是最终填入搜索框的规范名称。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SuggestionItem {
    private String text;
    private String type;
    private String entityId;
    private String matchedText;
    /** EXACT / PREFIX / SUFFIX / INFIX。 */
    private String matchPosition;
    private double score;
    private String source;
}
