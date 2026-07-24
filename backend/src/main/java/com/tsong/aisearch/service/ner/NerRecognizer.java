package com.tsong.aisearch.service.ner;

import com.tsong.aisearch.model.dto.NerEntity;

import java.util.List;

public interface NerRecognizer {

    List<NerEntity> recognize(String query);

    String provider();

    String modelVersion();
}
