package cn.vetech.ai.search.server.service.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 商品搜索的聚合维度。
 */
@Data
public class FacetsVo {

    private List<Bucket> brands = new ArrayList<>();
    private List<Bucket> categories = new ArrayList<>();

    /**
     * 单个聚合桶。
     */
    @Data
    public static class Bucket {

        private String key;
        private long count;

        public Bucket() {
        }

        public Bucket(String key, long count) {
            this.key = key;
            this.count = count;
        }
    }
}
