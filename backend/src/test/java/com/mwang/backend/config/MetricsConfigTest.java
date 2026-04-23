package com.mwang.backend.config;

import com.mwang.backend.repositories.DocumentOperationRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MetricsConfigTest {

    @Test
    void outboxGauges_registersGaugesAndCircuitBreakerCounter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DocumentOperationRepository repo = mock(DocumentOperationRepository.class);

        new MetricsConfig().outboxGauges(repo).bindTo(registry);

        Gauge outboxPending = registry.find("outbox.pending").gauge();
        Gauge outboxPoison = registry.find("outbox.poison").gauge();
        Counter redisCircuitOpen = registry.find("redis.circuit_open").counter();

        assertThat(outboxPending).isNotNull();
        assertThat(outboxPoison).isNotNull();
        assertThat(redisCircuitOpen).isNotNull();
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
                .as("lockAcquisition must expose p50/p95/p99 quantile gauges in the Prometheus scrape")
                .contains("lockAcquisition_seconds{quantile=\"0.5\"");
        assertThat(scrape).contains("lockAcquisition_seconds{quantile=\"0.95\"");
        assertThat(scrape).contains("lockAcquisition_seconds{quantile=\"0.99\"");
    }

    @Test
    void prometheusScrapeSurface_allNineTimersAndConfigMetricsPresent() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        MetricsConfig config = new MetricsConfig();
        config.histogramCustomizer().customize(registry);
        config.outboxGauges(mock(DocumentOperationRepository.class)).bindTo(registry);

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
                    .as(name + " must expose p95 quantile gauge in the Prometheus scrape")
                    .contains(name + "_seconds{quantile=\"0.95\"");
        }

        // outbox metrics are now gauges (no _total suffix)
        assertThat(scrape).as("outbox.pending gauge must appear").contains("outbox_pending");
        assertThat(scrape).as("outbox.poison gauge must appear").contains("outbox_poison");
        assertThat(scrape).as("redis.circuit_open counter must appear").contains("redis_circuit_open_total");
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
                .doesNotContain("someUnknownTimer_seconds{quantile=");
    }
}
