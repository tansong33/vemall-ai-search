package cn.vetech.ai.search.server.service.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 热搜词缓存预热结果。 */
@Data
public class CacheWarmupVo {

    private int total;
    private int succeeded;
    private int failed;
    private long costMs;
    private List<String> queries = new ArrayList<>();
}
