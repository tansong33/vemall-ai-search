package cn.vetech.aimall.service.recall;

import cn.vetech.aimall.config.AiMallProperties;
import cn.vetech.aimall.model.dto.IntentResult;
import cn.vetech.aimall.model.entity.Product;
import cn.vetech.aimall.service.DbSearchResult;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecallOrchestratorTest {

    @Test
    void fallsBackToMysqlWhenElasticsearchIsNotReady() {
        AiMallProperties properties = new AiMallProperties();
        properties.getSearch().setBackend("elasticsearch");
        RecallChannel mysql = channel("mysql", true,
                new DbSearchResult(Collections.<Product>emptyList(), "FULLTEXT"));
        RecallChannel elasticsearch = channel("elasticsearch", false, null);

        DbSearchResult result = new RecallOrchestrator(
                Arrays.asList(mysql, elasticsearch), properties).recall(new IntentResult());

        assertThat(result.getRoute()).isEqualTo("MYSQL_FALLBACK/FULLTEXT");
        verify(elasticsearch, never()).recall(org.mockito.ArgumentMatchers.any(IntentResult.class));
    }

    @Test
    void exactIdAlwaysUsesMysql() {
        AiMallProperties properties = new AiMallProperties();
        properties.getSearch().setBackend("elasticsearch");
        RecallChannel mysql = channel("mysql", true,
                new DbSearchResult(Collections.<Product>emptyList(), "EXACT_ID"));
        RecallChannel elasticsearch = channel("elasticsearch", true,
                new DbSearchResult(Collections.<Product>emptyList(), "ES"));
        IntentResult intent = new IntentResult();
        intent.setProductId("123");

        DbSearchResult result = new RecallOrchestrator(
                Arrays.asList(mysql, elasticsearch), properties).recall(intent);

        assertThat(result.getRoute()).isEqualTo("EXACT_ID");
        verify(elasticsearch, never()).recall(intent);
    }

    private RecallChannel channel(String name, boolean ready, DbSearchResult result) {
        RecallChannel channel = mock(RecallChannel.class);
        when(channel.name()).thenReturn(name);
        when(channel.isReady()).thenReturn(ready);
        if (result != null) when(channel.recall(org.mockito.ArgumentMatchers.any(IntentResult.class))).thenReturn(result);
        return channel;
    }
}
