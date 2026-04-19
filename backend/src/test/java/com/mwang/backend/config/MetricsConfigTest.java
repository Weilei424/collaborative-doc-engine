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
    void histogramCustomizer_enablesBucketsForKnownTimerNames() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        new MetricsConfig().histogramCustomizer().customize(registry);

        Timer.builder("lockAcquisition")
                .register(registry)
                .record(Duration.ofMillis(10));

        String scrape = registry.scrape();
        assertThat(scrape)
                .as("lockAcquisition must expose histogram buckets in the Prometheus scrape")
                .contains("lockAcquisition_seconds_bucket");
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
                "lockAcquisition", "loadDocument", "loadInterveningOps",
                "otTransformLoop", "perOpJsonParse", "treeApply",
                "persistOperation", "publishRedis", "publishKafka"}) {
            assertThat(scrape)
                    .as(name + " must expose histogram buckets in the Prometheus scrape")
                    .contains(name + "_seconds_bucket");
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
                .as("someUnknownTimer (not in TIMED_OPERATIONS) must NOT produce histogram buckets")
                .doesNotContain("someUnknownTimer_seconds_bucket");
    }
}
