-- Household members. A NULL password_hash is a member who cannot log in (e.g. a child) but can still be
-- referenced later for attribution. username is case-insensitive unique.
CREATE TABLE users (
    id            INTEGER PRIMARY KEY,
    username      TEXT NOT NULL UNIQUE COLLATE NOCASE,
    display_name  TEXT NOT NULL,
    password_hash TEXT,
    role          TEXT NOT NULL CHECK (role IN ('ADMIN', 'MEMBER')),
    created_at    TEXT NOT NULL
) STRICT;

-- Spring Security's JdbcTokenRepositoryImpl schema for persistent remember-me tokens. The reference DDL
-- uses a TIMESTAMP column, which STRICT tables reject; the sqlite-jdbc driver stores setTimestamp values
-- as epoch-millis INTEGER by default and getTimestamp reads them back, so INTEGER round-trips.
CREATE TABLE persistent_logins (
    username  TEXT    NOT NULL,
    series    TEXT    PRIMARY KEY,
    token     TEXT    NOT NULL,
    last_used INTEGER NOT NULL
) STRICT;
