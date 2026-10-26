package com.centry.model;

import java.time.LocalDateTime;

/** An API token as listed to its owner. Neither the token nor its hash is part of this type. */
public record ApiToken(long id, String name, LocalDateTime createdAt, LocalDateTime lastUsedAt) {
}
