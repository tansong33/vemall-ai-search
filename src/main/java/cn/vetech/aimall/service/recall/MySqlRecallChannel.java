package cn.vetech.aimall.service.recall;

import cn.vetech.aimall.model.dto.IntentResult;
import cn.vetech.aimall.service.DbSearchResult;
import cn.vetech.aimall.service.ProductSearchService;
import org.springframework.stereotype.Component;

/** 当前可工作的数据库召回，也是 ES 故障时的有界兜底。 */
@Component
public class MySqlRecallChannel implements RecallChannel {

    private final ProductSearchService productSearchService;

    public MySqlRecallChannel(ProductSearchService productSearchService) {
        this.productSearchService = productSearchService;
    }

    @Override public String name() { return "mysql"; }

    @Override public boolean isReady() { return true; }

    @Override public DbSearchResult recall(IntentResult intent) {
        return productSearchService.search(intent);
    }
}
