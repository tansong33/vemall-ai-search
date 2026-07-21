package cn.vetech.aimall.service.recall;

import cn.vetech.aimall.model.dto.IntentResult;
import cn.vetech.aimall.service.DbSearchResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** ES 接口占位。真实 mapping/字段到位前明确报告未就绪，编排器会回退 MySQL。 */
@Component
@ConditionalOnProperty(name = "aimall.search.elasticsearch-provider", havingValue = "stub", matchIfMissing = true)
public class StubElasticsearchRecallChannel implements RecallChannel {
    @Override public String name() { return "elasticsearch"; }

    @Override public boolean isReady() { return false; }

    @Override public DbSearchResult recall(IntentResult intent) {
        throw new IllegalStateException("Elasticsearch recall is not configured");
    }
}
