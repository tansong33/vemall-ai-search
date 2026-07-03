package cn.vetech.aimall.controller;

import cn.vetech.aimall.model.entity.Product;
import cn.vetech.aimall.repository.ProductRepository;
import cn.vetech.aimall.service.VectorIndexService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ProductController {

    private final ProductRepository productRepository;
    private final VectorIndexService vectorIndexService;

    /** 商品列表（调试/前端商品墙用） */
    @GetMapping("/products")
    public List<Product> list() {
        return productRepository.findAll();
    }

    /** 重建向量索引：商品上下架/改描述后调用 */
    @PostMapping("/admin/reindex")
    public Map<String, Object> reindex() {
        int count = vectorIndexService.rebuild();
        Map<String, Object> resp = new HashMap<>();
        resp.put("indexed", count);
        resp.put("message", "向量索引重建完成");
        return resp;
    }
}
