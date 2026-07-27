package cn.vetech.ai.search.server.config;

import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.message.BasicHeader;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.client.RestHighLevelClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 创建并配置 Elasticsearch 高级 REST 客户端。
 */
@Configuration
public class EsConfig {

    @Bean(destroyMethod = "close")
    public RestHighLevelClient restHighLevelClient(SearchProperties properties) {
        SearchProperties.Elasticsearch config = properties.getElasticsearch();
        RestClientBuilder builder = RestClient.builder(
                new HttpHost(config.getHost(), config.getPort(), config.getScheme()));

        builder.setDefaultHeaders(new Header[]{
            new BasicHeader("Accept", "application/vnd.elasticsearch+json;compatible-with=7"),
            new BasicHeader("Content-Type", "application/vnd.elasticsearch+json;compatible-with=7")
        });

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
