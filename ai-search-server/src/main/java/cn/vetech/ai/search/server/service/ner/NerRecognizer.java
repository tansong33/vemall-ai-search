package cn.vetech.ai.search.server.service.ner;

import cn.vetech.ai.search.server.service.dto.NerEntityDto;

import java.util.List;

/** 查询词实体识别。 */
public interface NerRecognizer {

    List<NerEntityDto> recognize(String query);

    /** 实际生效的识别方式，如 dictionary / onnx+dictionary。 */
    String provider();

    String modelVersion();
}
