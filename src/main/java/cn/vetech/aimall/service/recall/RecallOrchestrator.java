package cn.vetech.aimall.service.recall;

import cn.vetech.aimall.config.AiMallProperties;
import cn.vetech.aimall.model.dto.IntentResult;
import cn.vetech.aimall.service.DbSearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/** 按配置选择召回通道；精确 ID 固定走 MySQL，ES 未就绪或异常时自动回退。 */
@Slf4j
@Service
public class RecallOrchestrator {

    private final List<RecallChannel> channels;
    private final AiMallProperties properties;

    public RecallOrchestrator(List<RecallChannel> channels, AiMallProperties properties) {
        this.channels = channels;
        this.properties = properties;
    }

    public DbSearchResult recall(IntentResult intent) {
        RecallChannel mysql = channel("mysql");
        if (intent.getProductId() != null) return mysql.recall(intent);

        String backend = properties.getSearch().getBackend();
        backend = backend == null ? "mysql" : backend.trim().toLowerCase(Locale.ROOT);
        if ("elasticsearch".equals(backend) || "auto".equals(backend)) {
            RecallChannel elasticsearch = readyChannel("elasticsearch");
            if (elasticsearch != null) {
                try {
                    return elasticsearch.recall(intent);
                } catch (RuntimeException e) {
                    log.warn("ES 召回失败，回退 MySQL: {}", e.getMessage());
                }
            }
            DbSearchResult fallback = mysql.recall(intent);
            fallback.setRoute("MYSQL_FALLBACK/" + fallback.getRoute());
            return fallback;
        }
        return mysql.recall(intent);
    }

    private RecallChannel channel(String name) {
        for (RecallChannel channel : channels) {
            if (name.equals(channel.name())) return channel;
        }
        throw new IllegalStateException("missing recall channel: " + name);
    }

    private RecallChannel readyChannel(String name) {
        for (RecallChannel channel : channels) {
            if (name.equals(channel.name()) && channel.isReady()) return channel;
        }
        return null;
    }
}
