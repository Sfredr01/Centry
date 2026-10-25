package com.centry.dto;

import com.centry.model.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/v1/users}. */
public record UserRequest(

        @NotNull @Pattern(regexp = "^[a-zA-Z0-9._-]{3,40}$", message = "must be 3-40 letters, digits, '.', '_' or '-'")
        String username,

        @NotBlank @Size(max = 80) String displayName,

        // Optional: no password means a member who cannot log in. 72 is bcrypt's input limit.
        @Size(min = 12, max = 72) String password,

        @NotNull Role role) {
}
