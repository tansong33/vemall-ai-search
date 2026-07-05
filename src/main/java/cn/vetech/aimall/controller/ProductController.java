package cn.vetech.aimall.controller;

import cn.vetech.aimall.mapper.ProductMapper;
import cn.vetech.aimall.model.entity.Product;
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

    private final ProductMapper productMapper;
    private final VectorIndexService vectorIndexService;

    /** 商品列表（调试/前端商品墙用） */
    @GetMapping("/products")
    public List<Product> list() {
        return productMapper.selectList(null);
    }

    /**
     * 增量重建向量索引：商品导入/上下架/改描述后调用。
     * 默认只处理变化的商品；force=true 全量重嵌（改了 toEmbeddingText 拼接逻辑时用）。
     */
    @PostMapping("/admin/reindex")
    public Map<String, Object> reindex(@RequestParam(defaultValue = "false") boolean force) {
        VectorIndexService.SyncResult r = vectorIndexService.rebuild(force);
        Map<String, Object> resp = new HashMap<>();
        resp.put("embedded", r.embedded);
        resp.put("skipped", r.skipped);
        resp.put("removed", r.removed);
        resp.put("failed", r.failed);
        resp.put("message", force ? "已强制全量重嵌" : "增量同步完成");
        return resp;
    }
}
