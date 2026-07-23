package cn.vetech.aimall.model.index;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.OffsetDateTime;

/** 商品增量同步最小事件契约；消费者收到事件后按 spuId 幂等重建整个 ES 文档。 */
@Data
public class ProductIndexEvent {
    @JsonProperty("event_id") private String eventId;
    @JsonProperty("entity_type") private String entityType;
    @JsonProperty("entity_id") private String entityId;
    @JsonProperty("spu_id") private String spuId;
    @JsonProperty("updated_time") private OffsetDateTime updatedTime;
    private Long version;
    private String operation;
}
