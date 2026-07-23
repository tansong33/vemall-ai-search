package cn.vetech.aimall.model.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class SuggestionResponse {
    private String query;
    private boolean enabled;
    private List<SuggestionItem> items = new ArrayList<>();
    private List<String> sources = new ArrayList<>();
    private long tookMs;
}
