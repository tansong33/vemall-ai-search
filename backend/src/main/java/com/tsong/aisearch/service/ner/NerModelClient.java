package com.tsong.aisearch.service.ner;

public interface NerModelClient {

    NerModelOutput predict(String query);

    boolean isReady();

    String provider();

    String modelVersion();
}
