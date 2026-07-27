package cn.vetech.ai.search.server.dao;

import cn.vetech.ai.search.server.service.dto.SearchQueryDto;
import cn.vetech.ai.search.server.service.vo.SearchResultVo;

/**
 * 商品搜索数据访问接口。
 */
public interface ProductSearchDao {

    /**
     * 按 NER 结果和筛选条件检索商品。
     *
     * @throws SearchDataAccessException Elasticsearch 不可用时抛出，由上层决定降级策略
     */
    SearchResultVo search(SearchQueryDto query);
}
