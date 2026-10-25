-- Delivery tracking: NULL means "not yet delivered to every configured notifier".
ALTER TABLE budget_alert ADD COLUMN notified_at TEXT;

-- Alerts created before this migration were already notified (or deliberately lost); never re-send them.
UPDATE budget_alert SET notified_at = occurred_at;

-- The retry sweeper only looks at undelivered alerts, so keep that lookup cheap.
CREATE INDEX idx_budget_alert_unsent ON budget_alert (occurred_at)
    WHERE notified_at IS NULL AND period IS NOT NULL;
