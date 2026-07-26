package cn.vetech.ai.search.server.service.ner;

import cn.vetech.ai.search.server.model.dto.NerEntity;

import java.util.List;

public interface NerRecognizer {

    List<NerEntity> recognize(String query);

    String provider();

    String modelVersion();
}
