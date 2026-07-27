package cn.vetech.ai.search.rest.controller;

import cn.vetech.ai.search.fccapi.ApiResponse;
import cn.vetech.ai.search.fccapi.api.debug.SearchDebugApi;
import cn.vetech.ai.search.fccapi.api.debug.SearchDebugRequest;
import cn.vetech.ai.search.fccapi.api.debug.SearchDebugResponse;
import cn.vetech.ai.search.rest.assembler.SearchAssembler;
import cn.vetech.ai.search.rest.assembler.SearchDebugAssembler;
import cn.vetech.ai.search.rest.filter.RequestIdFilter;
import cn.vetech.ai.search.server.service.SearchDebugService;
import cn.vetech.ai.search.server.service.dto.SearchQueryDto;
import cn.vetech.ai.search.server.service.vo.SearchDebugVo;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

/** 搜索链路可视化。仅供内网调试页使用。 */
@RestController
@RequestMapping("/api/debug")
public class SearchDebugController implements SearchDebugApi {

    private final SearchDebugService debugService;
    private final SearchAssembler searchAssembler;
    private final SearchDebugAssembler debugAssembler;

    public SearchDebugController(SearchDebugService debugService,
                                 SearchAssembler searchAssembler,
                                 SearchDebugAssembler debugAssembler) {
        this.debugService = debugService;
        this.searchAssembler = searchAssembler;
        this.debugAssembler = debugAssembler;
    }

    @Override
    @PostMapping("/pipeline")
    public ApiResponse<SearchDebugResponse> pipeline(@Valid @RequestBody SearchDebugRequest request) {
        SearchQueryDto query = searchAssembler.toDto(request);
        SearchDebugVo vo = debugService.pipeline(query);
        return ApiResponse.ok(RequestIdFilter.current(),
                debugAssembler.toResponse(vo, query, request.isIncludeEsDsl()));
    }
}
