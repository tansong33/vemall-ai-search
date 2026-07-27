package cn.vetech.ai.search.rest.controller;

import cn.vetech.ai.search.fccapi.ApiResponse;
import cn.vetech.ai.search.fccapi.api.search.SearchApi;
import cn.vetech.ai.search.fccapi.api.search.SearchRequest;
import cn.vetech.ai.search.fccapi.api.search.SearchResponse;
import cn.vetech.ai.search.rest.assembler.SearchAssembler;
import cn.vetech.ai.search.rest.filter.RequestIdFilter;
import cn.vetech.ai.search.server.service.DegradeContext;
import cn.vetech.ai.search.server.service.SearchService;
import cn.vetech.ai.search.server.service.dto.SearchQueryDto;
import cn.vetech.ai.search.server.service.vo.SearchResultVo;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

/** 唯一的生产搜索入口。异常一律交给 GlobalExceptionHandler，控制器不 catch。 */
@RestController
@RequestMapping("/api/search")
public class SearchController implements SearchApi {

    private final SearchService searchService;
    private final SearchAssembler assembler;

    public SearchController(SearchService searchService, SearchAssembler assembler) {
        this.searchService = searchService;
        this.assembler = assembler;
    }

    @Override
    @PostMapping
    public ApiResponse<SearchResponse> search(@Valid @RequestBody SearchRequest request) {
        SearchQueryDto query = assembler.toDto(request);
        DegradeContext degrade = new DegradeContext();
        SearchResultVo result = searchService.search(query, degrade);
        return ApiResponse.ok(RequestIdFilter.current(),
                assembler.toResponse(result, query, degrade));
    }
}
