-- The old price-rise table modelled the wrong thing and never notified anyone.
DROP INDEX idx_subscription_alert_occurred_at;
DROP TABLE subscription_alert;

CREATE TABLE subscription (
    id             INTEGER PRIMARY KEY,
    merchant_key   TEXT    NOT NULL UNIQUE
                   CHECK (merchant_key <> '' AND merchant_key = lower(trim(merchant_key))),
    name           TEXT    NOT NULL CHECK (trim(name) <> ''),
    cadence        TEXT    NOT NULL CHECK (cadence IN ('MONTHLY', 'YEARLY')),
    expected_cents INTEGER NOT NULL CHECK (expected_cents > 0),
    next_due_on    TEXT    NOT NULL CHECK (date(next_due_on) IS next_due_on),
    status         TEXT    NOT NULL CHECK (status IN ('CANDIDATE', 'CONFIRMED', 'IGNORED')),
    reviewed_on    TEXT    CHECK (reviewed_on IS NULL OR date(reviewed_on) IS reviewed_on),
    created_at     TEXT    NOT NULL,
    CHECK (status = 'CANDIDATE' OR reviewed_on IS NOT NULL)
) STRICT;

CREATE TABLE subscription_alert (
    id              INTEGER PRIMARY KEY,
    subscription_id INTEGER NOT NULL REFERENCES subscription (id) ON DELETE CASCADE,
    kind            TEXT    NOT NULL CHECK (kind IN ('PRICE_CHANGE', 'RENEWAL_UPCOMING')),
    dedupe_key      TEXT    NOT NULL,
    title           TEXT    NOT NULL,
    body            TEXT    NOT NULL,
    occurred_at     TEXT    NOT NULL,
    notified_at     TEXT,
    UNIQUE (subscription_id, kind, dedupe_key)
) STRICT;
CREATE INDEX idx_subscription_alert_unsent ON subscription_alert (occurred_at) WHERE notified_at IS NULL;
