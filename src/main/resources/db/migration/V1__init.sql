-- Money is stored as INTEGER cents; timestamps as ISO-8601 local date-time TEXT.

CREATE TABLE expense (
    id             INTEGER PRIMARY KEY,
    transaction_id TEXT    NOT NULL UNIQUE,
    merchant       TEXT,
    amount_cents   INTEGER NOT NULL CHECK (amount_cents > 0),
    category       TEXT,
    occurred_at    TEXT    NOT NULL
) STRICT;

CREATE INDEX idx_expense_category_occurred_at ON expense (category, occurred_at);

CREATE TABLE budget_alert (
    id                   INTEGER PRIMARY KEY,
    category             TEXT,
    current_spend_cents  INTEGER,
    monthly_limit_cents  INTEGER,
    warning_amount_cents INTEGER,
    status               TEXT,
    message              TEXT,
    occurred_at          TEXT NOT NULL
) STRICT;

CREATE INDEX idx_budget_alert_occurred_at ON budget_alert (occurred_at);

CREATE TABLE subscription_alert (
    id                    INTEGER PRIMARY KEY,
    merchant              TEXT,
    previous_amount_cents INTEGER,
    current_amount_cents  INTEGER,
    anomaly_type          TEXT,
    message               TEXT,
    occurred_at           TEXT NOT NULL
) STRICT;

CREATE INDEX idx_subscription_alert_occurred_at ON subscription_alert (occurred_at);
