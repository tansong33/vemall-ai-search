package cn.vetech.aimall.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 模型输出的字符级实体；start 包含、end 不包含，与 Python 切片约定一致。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NerEntity {
    private int start;
    private int end;
    private String text;
    private String label;
    private double confidence;
    private String source;
}
