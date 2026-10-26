package com.centry.web;

import com.centry.entity.ExpenseEntity;

/**
 * A dashboard row.
 *
 * @param spender  display name of who spent it, or null when the expense is unattributed
 * @param editable whether the viewer may edit or delete it
 */
record RecentExpense(ExpenseEntity expense, String spender, boolean editable) {
}
