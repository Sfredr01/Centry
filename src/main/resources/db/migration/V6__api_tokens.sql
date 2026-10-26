-- Per-user API tokens for phone shortcuts. Only the SHA-256 hex of a token is stored: tokens are 256-bit
-- random, so a fast hash is enough (and bcrypt on every request would be slow and pointless here).
CREATE TABLE api_token (
    id           INTEGER PRIMARY KEY,
    user_id      INTEGER NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name         TEXT NOT NULL,
    token_hash   TEXT NOT NULL UNIQUE,
    created_at   TEXT NOT NULL,
    last_used_at TEXT
) STRICT;

CREATE INDEX idx_api_token_user ON api_token (user_id);
