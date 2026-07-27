package cn.vetech.ai.search.server.service;

import cn.vetech.ai.search.server.service.vo.SearchItemVo;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 同一 SPU 只保留得分最高的一个 SKU，保持原有顺序。
 *
 * <p>spuId 为空的条目不参与去重，原样保留。</p>
 */
@Component
public class SpuDeduplicator {

    public List<SearchItemVo> deduplicate(List<SearchItemVo> items) {
        if (items == null || items.isEmpty()) {
            return new ArrayList<SearchItemVo>();
        }
        Map<String, Integer> bestPosition = new HashMap<String, Integer>();
        List<SearchItemVo> result = new ArrayList<SearchItemVo>();
        for (SearchItemVo item : items) {
            if (item == null) {
                continue;
            }
            String spuId = item.getSpuId();
            if (!StringUtils.hasText(spuId)) {
                result.add(item);
                continue;
            }
            Integer position = bestPosition.get(spuId);
            if (position == null) {
                bestPosition.put(spuId, result.size());
                result.add(item);
            } else if (score(item) > score(result.get(position))) {
                result.set(position, item);
            }
        }
        return result;
    }

    private static float score(SearchItemVo item) {
        Float score = item == null ? null : item.getScore();
        return score == null ? 0f : score;
    }
}
