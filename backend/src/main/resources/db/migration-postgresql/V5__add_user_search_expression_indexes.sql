-- Replace plain indexes with expression indexes to support case-insensitive prefix search
-- (LOWER(col) LIKE 'prefix%' cannot use a plain B-tree index in PostgreSQL)
DROP INDEX IF EXISTS idx_users_username;
DROP INDEX IF EXISTS idx_users_email;

CREATE INDEX IF NOT EXISTS idx_users_username_lower ON users (LOWER(username));
CREATE INDEX IF NOT EXISTS idx_users_email_lower ON users (LOWER(email));
