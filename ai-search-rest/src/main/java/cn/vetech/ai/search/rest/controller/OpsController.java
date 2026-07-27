package cn.vetech.ai.search.rest.controller;

import cn.vetech.ai.search.fccapi.ApiResponse;
import cn.vetech.ai.search.rest.filter.RequestIdFilter;
import cn.vetech.ai.search.server.service.cache.CacheWarmupService;
import cn.vetech.ai.search.server.service.vo.CacheWarmupVo;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运维操作入口。
 *
 * <p>与 /api/debug 分开：预热是会改变线上缓存状态的真实操作，不是只读的调试查询。</p>
 */
@RestController
@RequestMapping("/api/ops")
public class OpsController {

    private final CacheWarmupService warmupService;

    public OpsController(CacheWarmupService warmupService) {
        this.warmupService = warmupService;
    }

    @PostMapping("/cache/warmup")
    public ApiResponse<CacheWarmupVo> warmup() {
        return ApiResponse.ok(RequestIdFilter.current(), warmupService.warmup());
    }
}
