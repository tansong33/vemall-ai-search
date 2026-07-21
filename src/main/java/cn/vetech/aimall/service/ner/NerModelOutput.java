package cn.vetech.aimall.service.ner;

import cn.vetech.aimall.model.dto.NerEntity;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** 与具体推理框架无关的模型输出协议。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NerModelOutput {
    private String modelVersion;
    private long inferenceMs;
    private List<NerEntity> entities = new ArrayList<>();
}
