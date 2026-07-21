package cn.vetech.aimall.service.recall;

import cn.vetech.aimall.model.dto.IntentResult;
import cn.vetech.aimall.service.DbSearchResult;

/** 商品召回端口。ES、MySQL 或未来向量通道都不得绕过这个边界。 */
public interface RecallChannel {
    String name();

    boolean isReady();

    DbSearchResult recall(IntentResult intent);
}
