-- One row per monthly digest, keyed by the month it summarises ('YYYY-MM'). Inserting the row is what claims
-- the right to send it, so a restart or a retry never sends a second digest. notified_at stays NULL until every
-- configured notifier has accepted it; the retry sweep looks only at those rows.
CREATE TABLE digest (
    period      TEXT PRIMARY KEY,
    created_at  TEXT NOT NULL,
    notified_at TEXT
) STRICT;
