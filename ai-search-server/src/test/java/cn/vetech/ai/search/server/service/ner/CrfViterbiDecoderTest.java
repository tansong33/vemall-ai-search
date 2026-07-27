package cn.vetech.ai.search.server.service.ner;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CrfViterbiDecoderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void usesTransitionScoresInsteadOfPerTokenArgmax() throws Exception {
        CrfViterbiDecoder decoder = CrfViterbiDecoder.fromJson(objectMapper.readTree(
                "{\"startTransitions\":[0,0],\"endTransitions\":[0,0],"
                        + "\"transitions\":[[10,0],[0,10]]}"));

        int[] path = decoder.decode(new float[][]{
                {5.0f, 0.0f},
                {0.0f, 4.0f}
        });

        assertThat(path).containsExactly(0, 0);
    }

    @Test
    void appliesStartAndEndTransitions() throws Exception {
        CrfViterbiDecoder decoder = CrfViterbiDecoder.fromJson(objectMapper.readTree(
                "{\"startTransitions\":[0,8],\"endTransitions\":[7,0],"
                        + "\"transitions\":[[0,0],[0,0]]}"));

        int[] path = decoder.decode(new float[][]{
                {2.0f, 2.0f},
                {2.0f, 2.0f}
        });

        assertThat(path).containsExactly(1, 0);
    }
}
