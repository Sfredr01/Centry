package com.centry.model;

import java.time.LocalDateTime;

/** A household member as the API shows it. The password hash is deliberately not part of this type. */
public record User(long id, String username, String displayName, Role role, LocalDateTime createdAt) {
}
