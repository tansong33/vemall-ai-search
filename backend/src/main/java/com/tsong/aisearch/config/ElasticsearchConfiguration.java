package com.tsong.aisearch.config;

import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.client.RestHighLevelClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ElasticsearchConfiguration {

    @Bean(destroyMethod = "close")
    public RestHighLevelClient restHighLevelClient(AiSearchProperties properties) {
        AiSearchProperties.Elasticsearch config = properties.getElasticsearch();
        RestClientBuilder builder = RestClient.builder(
                new HttpHost(config.getHost(), config.getPort(), config.getScheme()));
        builder.setRequestConfigCallback(request -> request
                .setConnectTimeout(config.getConnectTimeoutMs())
                .setSocketTimeout(config.getSocketTimeoutMs())
                .setConnectionRequestTimeout(config.getConnectionRequestTimeoutMs()));
        builder.setHttpClientConfigCallback(client -> client
                .setMaxConnTotal(config.getMaxConnections())
                .setMaxConnPerRoute(config.getMaxConnectionsPerRoute()));
        return new RestHighLevelClient(builder);
    }
}
