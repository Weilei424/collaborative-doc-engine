ALTER TABLE documents
    ADD COLUMN current_version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE document_operations (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL,
    actor_user_id UUID NOT NULL,
    operation_id UUID NOT NULL,
    client_session_id VARCHAR(255) NOT NULL,
    base_version BIGINT NOT NULL,
    server_version BIGINT NOT NULL,
    operation_type VARCHAR(50) NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE,
    FOREIGN KEY (actor_user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uk_document_operations_document_operation UNIQUE (document_id, operation_id),
    CONSTRAINT uk_document_operations_document_server_version UNIQUE (document_id, server_version)
);

CREATE INDEX idx_document_operations_document_id ON document_operations(document_id);
CREATE INDEX idx_document_operations_actor_user_id ON document_operations(actor_user_id);
CREATE INDEX idx_document_operations_document_server_version ON document_operations(document_id, server_version);
CREATE INDEX idx_document_operations_created_at ON document_operations(created_at);
