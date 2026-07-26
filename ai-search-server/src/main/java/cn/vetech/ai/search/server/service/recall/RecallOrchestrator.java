package cn.vetech.ai.search.server.service.recall;

import cn.vetech.ai.search.server.model.dto.ModelResult;
import cn.vetech.ai.search.server.model.dto.NerEntity;
import cn.vetech.ai.search.server.model.dto.SearchResult;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class RecallOrchestrator {

    private final List<RecallChannel> channels;

    public RecallOrchestrator(List<RecallChannel> channels) {
        this.channels = channels;
    }

    public SearchResult recall(String query, ModelResult modelResult, List<NerEntity> nerEntities, String sort,
                               Map<String, Object> filters) {
        for (RecallChannel channel : channels) {
            if ("elasticsearch".equals(channel.name()) && channel.isReady()) {
                return channel.recall(query, modelResult, nerEntities, sort, filters);
            }
        }
        throw new IllegalStateException("No Elasticsearch recall channel is ready");
    }
}
