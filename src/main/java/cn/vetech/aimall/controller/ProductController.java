package cn.vetech.aimall.controller;

import cn.vetech.aimall.mapper.ProductMapper;
import cn.vetech.aimall.model.entity.Product;
import cn.vetech.aimall.service.ner.EntityDictionaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ProductController {

    private final ProductMapper productMapper;
    private final EntityDictionaryService dictionaryService;

    /** 调试商品墙也必须分页，禁止一次把 500 万商品读进 JVM。 */
    @GetMapping("/products")
    public List<Product> list(@RequestParam(defaultValue = "0") long afterId,
                               @RequestParam(defaultValue = "20") long size) {
        long safeAfterId = Math.max(0, afterId);
        int safeSize = (int) Math.max(1, Math.min(size, 100));
        return productMapper.listAfterId(safeAfterId, safeSize);
    }

    /** 商品批量导入或品牌/类目变更后，可立即刷新在线 NER 词典。 */
    @PostMapping("/admin/ner-dictionary/refresh")
    public Map<String, String> refreshDictionary() {
        dictionaryService.refresh();
        return Collections.singletonMap("message", "NER 词典刷新完成");
    }
}
