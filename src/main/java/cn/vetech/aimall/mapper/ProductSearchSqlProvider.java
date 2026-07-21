package cn.vetech.aimall.mapper;

import cn.vetech.aimall.model.search.SearchCriteria;
import org.springframework.util.StringUtils;

/**
 * MySQL 8 ngram FULLTEXT 召回 SQL。WHERE 条件和 LIMIT 全部参数化；列名是固定常量。
 */
public class ProductSearchSqlProvider {

    private static final String MATCH =
            "MATCH(p.title, p.brand, p.scene_tags, p.description) " +
            "AGAINST(#{searchText} IN NATURAL LANGUAGE MODE)";

    public String search(SearchCriteria c) {
        boolean withText = StringUtils.hasText(c.getSearchText());
        StringBuilder sql = new StringBuilder("select p.*");
        sql.append(withText ? ", " + MATCH + " as search_score " : ", 0 as search_score ");
        sql.append("from product p where p.stock > 0 ");
        appendFilters(sql, c);
        if (withText) {
            sql.append("and ").append(MATCH).append(" > 0 ")
               .append("order by search_score desc, p.featured desc, p.stock desc ");
        } else {
            sql.append("order by p.featured desc, p.stock desc, p.id desc ");
        }
        sql.append("limit #{limit}");
        return sql.toString();
    }

    /** 全文无结果时，仅在已经识别到结构化实体的情况下执行，不做全库兜底扫描。 */
    public String searchStructured(SearchCriteria c) {
        StringBuilder sql = new StringBuilder(
                "select p.*, 0 as search_score from product p where p.stock > 0 ");
        appendFilters(sql, c);
        sql.append("order by p.featured desc, p.stock desc, p.id desc limit #{limit}");
        return sql.toString();
    }

    private void appendFilters(StringBuilder sql, SearchCriteria c) {
        if (StringUtils.hasText(c.getCategory())) sql.append("and p.category = #{category} ");
        if (StringUtils.hasText(c.getBrand())) sql.append("and p.brand = #{brand} ");
        if (c.getPriceMin() != null) sql.append("and p.price >= #{priceMin} ");
        if (c.getPriceMax() != null) sql.append("and p.price <= #{priceMax} ");
    }
}
