-- Who spent the money and who entered it. Both are nullable: rows from before this migration and rows that
-- arrive over Kafka have no known user (NULL = unattributed). spent_by is the household member the expense is
-- attributed to; created_by is the logged-in user who recorded it and is what gates edit and delete.
ALTER TABLE expense ADD COLUMN spent_by   INTEGER REFERENCES users (id);
ALTER TABLE expense ADD COLUMN created_by INTEGER REFERENCES users (id);

-- Serves the dashboard's per-member month totals and recent list.
CREATE INDEX idx_expense_spent_by_occurred_at ON expense (spent_by, occurred_at);
