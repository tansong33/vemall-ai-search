package cn.vetech.aimall.service.suggestion;

import cn.vetech.aimall.config.AiMallProperties;
import cn.vetech.aimall.model.dto.SuggestionItem;
import cn.vetech.aimall.model.dto.SuggestionResponse;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchSuggestionServiceTest {
    @Test
    void isolatesFailingSourceAndDeduplicatesByEntity() {
        SuggestionSource failing = new FixedSource("broken", true, null, true);
        SuggestionSource first = new FixedSource("dictionary", true,
                Collections.singletonList(item("保温杯", "C1", 0.85)), false);
        SuggestionSource better = new FixedSource("hot-query", true,
                Arrays.asList(item("保温杯", "C1", 0.98), item("保温壶", "C2", 0.90)), false);
        SearchSuggestionService service = new SearchSuggestionService(
                Arrays.asList(failing, first, better), new AiMallProperties());

        SuggestionResponse response = service.suggest("保温", null, null, 10);

        assertThat(response.getItems()).extracting(SuggestionItem::getText)
                .containsExactly("保温杯", "保温壶");
        assertThat(response.getItems().get(0).getScore()).isEqualTo(0.98);
        assertThat(response.getSources()).containsExactly("dictionary", "hot-query");
    }

    private SuggestionItem item(String text, String id, double score) {
        return new SuggestionItem(text, "CATEGORY", id, text, "PREFIX", score, "test");
    }

    private static final class FixedSource implements SuggestionSource {
        private final String name;
        private final boolean ready;
        private final List<SuggestionItem> items;
        private final boolean fail;

        private FixedSource(String name, boolean ready, List<SuggestionItem> items, boolean fail) {
            this.name = name;
            this.ready = ready;
            this.items = items;
            this.fail = fail;
        }

        @Override public String name() { return name; }
        @Override public boolean isReady() { return ready; }
        @Override public List<SuggestionItem> suggest(String query, String tenant, String channel, int limit) {
            if (fail) throw new IllegalStateException("boom");
            return items;
        }
    }
}
