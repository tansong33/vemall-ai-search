package cn.vetech.ai.search.server.controller;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SystemStatusControllerTest {

    @Test
    void reportsCoreDependenciesAsUp() {
        Map<String, Object> result = new SystemStatusController(
                () -> "cluster reachable",
                () -> "PONG"
        ).status();

        assertThat(result.get("status")).isEqualTo("UP");
        Map<?, ?> services = (Map<?, ?>) result.get("services");
        assertThat(((Map<?, ?>) services.get("backend")).get("status")).isEqualTo("UP");
        assertThat(((Map<?, ?>) services.get("elasticsearch")).get("status")).isEqualTo("UP");
        assertThat(((Map<?, ?>) services.get("redis")).get("status")).isEqualTo("UP");
    }

    @Test
    void reportsADegradedSystemWhenAProbeFails() {
        Map<String, Object> result = new SystemStatusController(
                () -> {
                    throw new IllegalStateException("offline");
                },
                () -> "PONG"
        ).status();

        assertThat(result.get("status")).isEqualTo("DEGRADED");
        Map<?, ?> services = (Map<?, ?>) result.get("services");
        assertThat(((Map<?, ?>) services.get("elasticsearch")).get("status")).isEqualTo("DOWN");
        assertThat(((Map<?, ?>) services.get("redis")).get("status")).isEqualTo("UP");
    }
}
