package com.mwang.backend.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Immutable;
import org.hibernate.proxy.HibernateProxy;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Immutable
@AllArgsConstructor
@NoArgsConstructor
@Getter
@ToString
@Builder
@Table(name = "document_operations", uniqueConstraints = {
        @UniqueConstraint(name = "uk_document_operations_document_operation", columnNames = {"document_id", "operation_id"}),
        @UniqueConstraint(name = "uk_document_operations_document_server_version", columnNames = {"document_id", "server_version"})
})
public class DocumentOperation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false, updatable = false)
    @ToString.Exclude
    private Document document;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_user_id", nullable = false, updatable = false)
    @ToString.Exclude
    private User actor;

    @Column(name = "operation_id", nullable = false, updatable = false)
    private UUID operationId;

    @NotBlank
    @Column(name = "client_session_id", nullable = false, length = 255, updatable = false)
    private String clientSessionId;

    @Column(name = "base_version", nullable = false, updatable = false)
    private Long baseVersion;

    @Column(name = "server_version", nullable = false, updatable = false)
    private Long serverVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false, length = 50, updatable = false)
    private DocumentOperationType operationType;

    @NotBlank
    @Column(nullable = false, columnDefinition = "TEXT", updatable = false)
    private String payload;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_to_kafka_at", insertable = false, updatable = false)
    private Instant publishedToKafkaAt;

    @Column(name = "kafka_publish_attempts", insertable = false, updatable = false)
    private int kafkaPublishAttempts;

    @Column(name = "kafka_last_error", insertable = false, updatable = false)
    private String kafkaLastError;

    @Column(name = "kafka_poison_at", insertable = false, updatable = false)
    private Instant kafkaPoisonAt;

    @Column(name = "next_attempt_at", insertable = false, updatable = false)
    private Instant nextAttemptAt;

    @Override
    public final boolean equals(Object o) {
        if (this == o) return true;
        if (o == null) return false;
        Class<?> oEffectiveClass = o instanceof HibernateProxy ? ((HibernateProxy) o).getHibernateLazyInitializer().getPersistentClass() : o.getClass();
        Class<?> thisEffectiveClass = this instanceof HibernateProxy ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass() : this.getClass();
        if (thisEffectiveClass != oEffectiveClass) return false;
        DocumentOperation that = (DocumentOperation) o;
        return getId() != null && Objects.equals(getId(), that.getId());
    }

    @Override
    public final int hashCode() {
        return this instanceof HibernateProxy ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass().hashCode() : getClass().hashCode();
    }
}
