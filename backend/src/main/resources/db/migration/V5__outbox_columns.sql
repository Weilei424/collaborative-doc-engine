ALTER TABLE document_operations
    ADD COLUMN published_to_kafka_at  TIMESTAMPTZ,
    ADD COLUMN kafka_publish_attempts INT         NOT NULL DEFAULT 0,
    ADD COLUMN kafka_last_error        TEXT,
    ADD COLUMN kafka_poison_at         TIMESTAMPTZ,
    ADD COLUMN next_attempt_at         TIMESTAMPTZ;

CREATE INDEX idx_document_operations_outbox_pending
    ON document_operations (next_attempt_at ASC)
    WHERE published_to_kafka_at IS NULL AND kafka_poison_at IS NULL;
