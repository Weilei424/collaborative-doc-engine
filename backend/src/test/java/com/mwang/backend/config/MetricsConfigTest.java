package com.mwang.backend.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsConfigTest {

    @Test
    void placeholderMetrics_registersOutboxAndCircuitBreakerCounters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new MetricsConfig().placeholderMetrics().bindTo(registry);

        Counter outboxPending = registry.find("outbox.pending").counter();
        Counter outboxPoison = registry.find("outbox.poison").counter();
        Counter redisCircuitOpen = registry.find("redis.circuit_open").counter();

        assertThat(outboxPending).isNotNull();
        assertThat(outboxPoison).isNotNull();
        assertThat(redisCircuitOpen).isNotNull();
        assertThat(outboxPending.count()).isEqualTo(0.0);
        assertThat(outboxPoison.count()).isEqualTo(0.0);
        assertThat(redisCircuitOpen.count()).isEqualTo(0.0);
    }

    @Test
    void histogramCustomizer_enablesPercentilesForKnownTimerNames() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        new MetricsConfig().histogramCustomizer().customize(registry);

        Timer.builder("lockAcquisition")
                .register(registry)
                .record(Duration.ofMillis(10));

        String scrape = registry.scrape();
        assertThat(scrape)
                .as("lockAcquisition must expose p50 quantile gauge in text format")
                .contains("lock_acquisition_seconds{quantile=\"0.5\"");
        assertThat(scrape)
                .as("lockAcquisition must expose p95 quantile gauge in text format")
                .contains("lock_acquisition_seconds{quantile=\"0.95\"");
        assertThat(scrape)
                .as("lockAcquisition must expose p99 quantile gauge in text format")
                .contains("lock_acquisition_seconds{quantile=\"0.99\"");
    }

    @Test
    void prometheusScrapeSurface_allNineTimersAndThreeConfigCountersPresent() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        MetricsConfig config = new MetricsConfig();
        config.histogramCustomizer().customize(registry);
        config.placeholderMetrics().bindTo(registry);

        for (String name : new String[]{
                "lockAcquisition", "loadDocument", "loadInterveningOps",
                "otTransformLoop", "perOpJsonParse", "treeApply",
                "persistOperation", "publishRedis", "publishKafka"}) {
            Timer.builder(name).register(registry).record(Duration.ofMillis(1));
        }

        String scrape = registry.scrape();

        for (String name : new String[]{
                "lock_acquisition", "load_document", "load_intervening_ops",
                "ot_transform_loop", "per_op_json_parse", "tree_apply",
                "persist_operation", "publish_redis", "publish_kafka"}) {
            assertThat(scrape)
                    .as(name + " must expose p95 quantile gauge in the Prometheus scrape")
                    .contains(name + "_seconds{quantile=\"0.95\"");
        }

        for (String name : new String[]{"outbox_pending_total", "outbox_poison_total", "redis_circuit_open_total"}) {
            assertThat(scrape)
                    .as(name + " must appear in the Prometheus scrape")
                    .contains(name);
        }
    }

    @Test
    void histogramCustomizer_doesNotEnablePercentilesForUnknownTimerNames() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        new MetricsConfig().histogramCustomizer().customize(registry);

        Timer.builder("someUnknownTimer")
                .register(registry)
                .record(Duration.ofMillis(5));

        String scrape = registry.scrape();
        assertThat(scrape)
                .as("someUnknownTimer (not in TIMED_OPERATIONS) must NOT produce quantile gauges")
                .doesNotContain("some_unknown_timer_seconds{quantile=");
    }
}
