package cn.vetech.aimall.controller;

import cn.vetech.aimall.model.dto.SuggestionResponse;
import cn.vetech.aimall.service.suggestion.SearchSuggestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SuggestionController {
    private final SearchSuggestionService suggestionService;

    @GetMapping("/suggestions")
    public SuggestionResponse suggestions(@RequestParam("q") String query,
                                          @RequestParam(value = "tenantCode", required = false) String tenantCode,
                                          @RequestParam(value = "channelCode", required = false) String channelCode,
                                          @RequestParam(value = "limit", required = false) Integer limit) {
        if (query != null && query.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "联想关键词最长 100 个字符");
        }
        return suggestionService.suggest(query, tenantCode, channelCode, limit);
    }
}
