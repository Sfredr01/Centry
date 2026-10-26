package com.centry.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/v1/tokens}: a label so the owner can tell their tokens apart. */
public record TokenRequest(@NotBlank @Size(max = 60) String name) {
}
