package cn.vetech.aimall.service;

import cn.vetech.aimall.model.entity.Product;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/** 数据库召回结果及实际采用的级联路由。 */
@Data
@AllArgsConstructor
public class DbSearchResult {
    private List<Product> products;
    private String route;
}
