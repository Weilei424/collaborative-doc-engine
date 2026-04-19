package com.mwang.backend.config;

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
                            .percentilesHistogram(true)
                            .build()
                            .merge(config);
                }
                return config;
            }
        });
    }

    @Bean
    MeterBinder placeholderMetrics() {
        return registry -> {
            registry.counter("outbox.pending");
            registry.counter("outbox.poison");
            registry.counter("redis.circuit_open");
        };
    }
}
