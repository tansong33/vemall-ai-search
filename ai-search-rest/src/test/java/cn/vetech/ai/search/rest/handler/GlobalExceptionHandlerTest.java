package cn.vetech.ai.search.rest.handler;

import cn.vetech.ai.search.rest.assembler.SearchAssembler;
import cn.vetech.ai.search.rest.controller.SearchController;
import cn.vetech.ai.search.server.dao.SearchDataAccessException;
import cn.vetech.ai.search.server.service.DegradeContext;
import cn.vetech.ai.search.server.service.SearchService;
import cn.vetech.ai.search.server.service.dto.SearchQueryDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 异常出口的 HTTP 状态契约。
 *
 * <p>盯的是同一类回归：{@code @ExceptionHandler(Exception.class)} 兜底会静默吃掉
 * Spring MVC 自己抛的标准异常，把本该 4xx 的情况变成 500。线上实测过
 * {@code GET /api/search} 返回 500 —— 客户端据此重试，实际重试多少次都没用。</p>
 */
class GlobalExceptionHandlerTest {

    private SearchService searchService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        searchService = mock(SearchService.class);
        SearchAssembler assembler = mock(SearchAssembler.class);
        when(assembler.toDto(any())).thenReturn(new SearchQueryDto());
        mockMvc = MockMvcBuilders
                .standaloneSetup(new SearchController(searchService, assembler))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("方法用错回 405，不是 500")
    void getOnPostOnlyEndpointReturns405() throws Exception {
        mockMvc.perform(get("/api/search"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("Content-Type 不对回 415，不是 500")
    void wrongContentTypeReturns415() throws Exception {
        mockMvc.perform(post("/api/search")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("query=手机"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    @DisplayName("请求体不是合法 JSON 回 400")
    void malformedJsonReturns400() throws Exception {
        mockMvc.perform(post("/api/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    @DisplayName("query 为空回 400 并带上校验信息")
    void blankQueryReturns400() throws Exception {
        mockMvc.perform(post("/api/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value("query 不能为空"));
    }

    @Test
    @DisplayName("ES 不可用回 503，与 500 区分开")
    void dataAccessFailureReturns503() throws Exception {
        when(searchService.search(any(SearchQueryDto.class), any(DegradeContext.class)))
                .thenThrow(new SearchDataAccessException("es down"));
        mockMvc.perform(post("/api/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"手机\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SEARCH_UNAVAILABLE"));
    }

    @Test
    @DisplayName("未预期异常回 500，且不把内部细节写进响应")
    void unexpectedFailureReturns500WithoutLeakingDetail() throws Exception {
        when(searchService.search(any(SearchQueryDto.class), any(DegradeContext.class)))
                .thenThrow(new IllegalStateException("connect to es-prod-01.internal:9200 refused"));
        String body = mockMvc.perform(post("/api/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"手机\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("系统繁忙，请稍后重试"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("es-prod-01"),
                "500 响应体不能泄漏内部主机名：" + body);
    }
}
