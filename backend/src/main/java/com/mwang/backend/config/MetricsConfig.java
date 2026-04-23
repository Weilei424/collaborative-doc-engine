package com.mwang.backend.config;

import com.mwang.backend.repositories.DocumentOperationRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

@Configuration
public class MetricsConfig {

    private static final Set<String> TIMED_OPERATIONS = Set.of(
            "lockAcquisition", "loadDocument", "loadInterveningOps",
            "otTransformLoop", "perOpJsonParse", "treeApply",
            "persistOperation", "publishRedis", "publishKafka");

    @Bean
    MeterRegistryCustomizer<MeterRegistry> histogramCustomizer() {
        return registry -> registry.config().meterFilter(new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                if (TIMED_OPERATIONS.contains(id.getName())) {
                    return DistributionStatisticConfig.builder()
                            .percentiles(0.5, 0.95, 0.99)
                            .build()
                            .merge(config);
                }
                return config;
            }
        });
    }

    @Bean
    MeterBinder outboxGauges(DocumentOperationRepository repo) {
        return registry -> {
            Gauge.builder("outbox.pending", repo, DocumentOperationRepository::countPending)
                 .description("Unpublished, non-poison outbox rows")
                 .register(registry);
            Gauge.builder("outbox.poison", repo, DocumentOperationRepository::countPoison)
                 .description("Poison outbox rows requiring operator attention")
                 .register(registry);
            registry.counter("redis.circuit_open");
        };
    }
}
