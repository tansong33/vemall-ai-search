package cn.vetech.ai.search.server.service.ner;

import cn.vetech.ai.search.server.config.NerProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RaNerLabelMapperTest {

    @Test
    void mapsFineGrainedChineseLabelsAndKeepsInternalLabels() {
        RaNerLabelMapper mapper = new RaNerLabelMapper(new NerProperties());
        mapper.initialize();

        assertThat(mapper.map("B-品牌")).isEqualTo("BRAND");
        assertThat(mapper.map("E-材质_面料")).isEqualTo("MATERIAL");
        assertThat(mapper.map("S-产品_核心产品")).isEqualTo("CATEGORY");
        assertThat(mapper.map("MODEL")).isEqualTo("MODEL");
    }
}
