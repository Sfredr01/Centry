package com.centry.security;

import com.centry.entity.ExpenseEntity;
import com.centry.repository.UserRepository;
import org.springframework.security.core.Authentication;

/**
 * The logged-in user as the household sees them.
 *
 * @param userId the {@code users.id} behind the login name, or null when the name is not a stored user
 * @param admin  whether the login has the ADMIN role
 */
public record Actor(Long userId, boolean admin) {

    private static final String ADMIN_AUTHORITY = "ROLE_ADMIN";

    public static Actor of(Authentication authentication, UserRepository users) {
        Long userId = users.idByUsername(authentication.getName()).stream().boxed().findFirst().orElse(null);
        boolean admin = authentication.getAuthorities().stream()
                .anyMatch(authority -> ADMIN_AUTHORITY.equals(authority.getAuthority()));
        return new Actor(userId, admin);
    }

    /** Admins may change any expense; others only the ones they created. Rows with no creator are admin-only. */
    public boolean canModify(ExpenseEntity expense) {
        return admin || (userId != null && userId.equals(expense.createdBy()));
    }
}
