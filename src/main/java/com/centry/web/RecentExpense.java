package com.centry.web;

import com.centry.entity.ExpenseEntity;

/**
 * A dashboard row.
 *
 * @param spender display name of who spent it, or null when the expense is unattributed
 */
record RecentExpense(ExpenseEntity expense, String spender) {
}
