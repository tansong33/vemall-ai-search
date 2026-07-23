package cn.vetech.aimall.mapper;

import cn.vetech.aimall.model.search.SearchCriteria;
import org.springframework.util.StringUtils;

/**
 * cdsgoods 的 MySQL 8 有界召回。
 *
 * <p>先在 pro_spu 上用 ngram FULLTEXT/结构化索引生成小候选集，再关联 SKU 和库存并用窗口函数
 * 选出每个 SPU 最合适的可售 SKU。在线查询不会关联 800 万行的详情描述表。</p>
 */
public class CdsgoodsProductSearchSqlProvider {

    private static final String MATCH =
            "MATCH(p.pro_name, p.pro_suggestion, p.query_keywords, p.brand_name) " +
            "AGAINST(#{searchText} IN NATURAL LANGUAGE MODE)";

    public String search(SearchCriteria criteria) {
        return productQuery(criteria, true, false);
    }

    public String searchStructured(SearchCriteria criteria) {
        return productQuery(criteria, false, false);
    }

    public String findExact(SearchCriteria criteria) {
        return productQuery(criteria, false, true);
    }

    public String listAfterId(SearchCriteria criteria) {
        StringBuilder sql = new StringBuilder();
        appendCandidateCte(sql, criteria, false, false, true);
        appendSkuCteAndSelect(sql, criteria, false);
        return sql.toString();
    }

    public String selectCategories(SearchCriteria criteria) {
        StringBuilder sql = new StringBuilder(
                "select c.id, c.name, c.keywords as aliases from pro_platform_class c " +
                "where c.is_deleted = #{notDeletedValue} and c.is_displayed = #{categoryDisplayedValue} ");
        if (StringUtils.hasText(criteria.getTenantCode())) sql.append("and c.tn_code = #{tenantCode} ");
        if (StringUtils.hasText(criteria.getChannelCode())) sql.append("and c.channel_code = #{channelCode} ");
        sql.append("order by c.class_level desc, c.id asc");
        return sql.toString();
    }

    public String selectBrands(SearchCriteria criteria) {
        StringBuilder sql = new StringBuilder(
                "select b.id, coalesce(nullif(b.cn_name, ''), b.en_name) as name, " +
                "concat_ws(',', b.en_name, b.aliases) as aliases " +
                "from pro_platform_brand b where b.is_enabled = #{brandEnabledValue} ");
        if (StringUtils.hasText(criteria.getTenantCode())) sql.append("and b.tn_code = #{tenantCode} ");
        sql.append("order by b.id asc");
        return sql.toString();
    }

    private String productQuery(SearchCriteria criteria, boolean withFulltext, boolean exact) {
        StringBuilder sql = new StringBuilder();
        appendCandidateCte(sql, criteria, withFulltext, exact, false);
        appendSkuCteAndSelect(sql, criteria, exact);
        return sql.toString();
    }

    private void appendCandidateCte(StringBuilder sql, SearchCriteria c, boolean withFulltext,
                                    boolean exact, boolean cursorPage) {
        sql.append("with candidate_spu as (")
           .append("select p.id as spu_id, p.pro_name as title, p.class_id as category_id, ")
           .append("pc.name as category, p.brand_id, ")
           .append("coalesce(nullif(pb.cn_name, ''), p.brand_name) as brand, ")
           .append("p.main_pic_url as image_url, p.search_weight, p.min_purchase_num, p.channel_code, p.tn_code, ")
           .append("coalesce(pd.avg_score, 0) as rating, coalesce(pd.sell_num, 0) as sales_count, ")
           .append(withFulltext ? MATCH + " as search_score " : "0 as search_score ")
           .append("from pro_spu p ")
           .append("left join pro_platform_class pc on pc.id = p.class_id ")
           .append("left join pro_platform_brand pb on pb.id = p.brand_id ")
           .append("left join pro_spu_detail pd on pd.pro_spu_id = p.id ")
           .append("where p.is_deleted = #{notDeletedValue} ")
           .append("and p.on_state = #{spuOnState} and p.audit_state = #{spuAuditState} ")
           .append("and (p.class_id is null or p.class_id = '' or ")
           .append("(pc.is_deleted = #{notDeletedValue} and pc.is_displayed = #{categoryDisplayedValue})) ")
           .append("and (p.brand_id is null or p.brand_id = '' or pb.is_enabled = #{brandEnabledValue}) ");

        if (StringUtils.hasText(c.getTenantCode())) sql.append("and p.tn_code = #{tenantCode} ");
        if (StringUtils.hasText(c.getChannelCode())) sql.append("and p.channel_code = #{channelCode} ");
        if (c.getRequestedQuantity() != null) {
            sql.append("and (p.min_purchase_num is null or p.min_purchase_num <= #{requestedQuantity}) ");
        }
        if (cursorPage && StringUtils.hasText(c.getAfterId())) sql.append("and p.id > #{afterId} ");
        if (StringUtils.hasText(c.getCategoryId())) sql.append("and p.class_id = #{categoryId} ");
        else if (StringUtils.hasText(c.getCategory())) sql.append("and pc.name = #{category} ");
        if (StringUtils.hasText(c.getBrandId())) sql.append("and p.brand_id = #{brandId} ");
        else if (StringUtils.hasText(c.getBrand())) {
            sql.append("and (p.brand_name = #{brand} or pb.cn_name = #{brand} or pb.en_name = #{brand}) ");
        }
        if (exact) {
            sql.append("and (p.id = #{productCode} or exists (")
               .append("select 1 from pro_sku exact_sku where exact_sku.spu_id = p.id ")
               .append("and (exact_sku.id = #{productCode} or exact_sku.bar_code = #{productCode} ")
               .append("or exact_sku.supplier_sku_id = #{productCode}))) ");
        }
        if (withFulltext) sql.append("and ").append(MATCH).append(" > 0 ");

        if (exact) sql.append("order by case when p.id = #{productCode} then 0 else 1 end, p.id asc limit 1" );
        else if (withFulltext) sql.append("order by search_score desc, p.search_weight desc, p.id asc limit #{spuCandidateLimit}");
        else sql.append("order by p.search_weight desc, p.id asc limit #{spuCandidateLimit}");
        sql.append("), ");
    }

    private void appendSkuCteAndSelect(StringBuilder sql, SearchCriteria c, boolean exact) {
        sql.append("ranked_sku as (")
           .append("select cs.*, sk.id as sku_id, sk.pro_name as sku_title, sk.sales_price as price, ")
           .append("sk.attr_json as attrs, sk.spec_json as specs, sk.bar_code, ")
           .append("greatest(coalesce(st.total_stock_num, 0) - coalesce(st.locked_stock_num, 0), 0) as available_stock, ")
           .append("min(sk.sales_price) over(partition by cs.spu_id) as min_price, ")
           .append("max(sk.sales_price) over(partition by cs.spu_id) as max_price, ")
           .append("row_number() over(partition by cs.spu_id order by ");
        if (exact) {
            sql.append("case when sk.id = #{productCode} or sk.bar_code = #{productCode} ")
               .append("or sk.supplier_sku_id = #{productCode} then 0 else 1 end, ");
        }
        sql.append("sk.sort_val desc, ")
           .append("greatest(coalesce(st.total_stock_num, 0) - coalesce(st.locked_stock_num, 0), 0) desc, ")
           .append("sk.sales_price asc, sk.id asc) as sku_rank ")
           .append("from candidate_spu cs join pro_sku sk on sk.spu_id = cs.spu_id ")
           .append("left join pro_sku_stock st on st.sku_id = sk.id ")
           .append("where sk.is_deleted = #{notDeletedValue} and sk.on_state = #{skuOnState} ")
           .append("and greatest(coalesce(st.total_stock_num, 0) - coalesce(st.locked_stock_num, 0), 0) > 0 ");
        if (c.getRequestedQuantity() != null) {
            sql.append("and greatest(coalesce(st.total_stock_num, 0) - coalesce(st.locked_stock_num, 0), 0) ")
               .append(">= #{requestedQuantity} ");
        }
        if (StringUtils.hasText(c.getTenantCode())) {
            sql.append("and sk.tn_code = #{tenantCode} and (st.tn_code is null or st.tn_code = #{tenantCode}) ");
        }
        if (c.getPriceMin() != null) sql.append("and sk.sales_price >= #{priceMin} ");
        if (c.getPriceMax() != null) sql.append("and sk.sales_price <= #{priceMax} ");
        sql.append(") select spu_id as id, sku_id, title, category, category_id, brand, brand_id, ")
           .append("price, min_price, max_price, min_purchase_num, attrs, specs, null as scene_tags, available_stock as stock, ")
           .append("false as points_eligible, (coalesce(search_weight, 0) > 0) as featured, search_weight, ")
           .append("sales_count, rating, bar_code, tn_code as tenant_code, channel_code, image_url, ")
           .append("null as description, search_score from ranked_sku where sku_rank = 1 ")
           .append("order by search_score desc, search_weight desc, sales_count desc, spu_id asc limit #{limit}");
    }
}
