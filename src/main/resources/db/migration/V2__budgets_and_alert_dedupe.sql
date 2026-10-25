-- Budgets are data, not config. category_key is the normalized key: trim + lower-case (see Keys.of).
CREATE TABLE budget (
    category_key        TEXT    PRIMARY KEY,
    monthly_limit_cents INTEGER NOT NULL CHECK (monthly_limit_cents > 0),
    warn_pct            INTEGER NOT NULL DEFAULT 80 CHECK (warn_pct BETWEEN 1 AND 99)
) STRICT;

-- Spend per category and month is a query over expense. These expression indexes serve lookups by the
-- normalized key. SQLite's lower() only folds ASCII (Java's toLowerCase also folds other scripts), and its
-- trim() only strips spaces (Java's trim() strips all chars <= U+0020): keys that differ only in
-- non-ASCII case or in tabs/newlines do not match.
CREATE INDEX idx_expense_category_key_occurred_at ON expense (lower(trim(category)), occurred_at);

-- Previous charge of a merchant for the subscription check: latest id per normalized merchant.
CREATE INDEX idx_expense_merchant_key_id ON expense (lower(trim(merchant)), id);

-- At most one alert per category, month and level. Rows written before this migration have no period.
ALTER TABLE budget_alert ADD COLUMN period TEXT;

CREATE UNIQUE INDEX ux_budget_alert_dedupe ON budget_alert (lower(trim(category)), period, status)
    WHERE period IS NOT NULL;
