package com.centry.controller;

import com.centry.dto.UserRequest;
import com.centry.model.User;
import com.centry.repository.UserRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/** Household members. ADMIN only (see SecurityConfig). */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    @PostMapping
    public ResponseEntity<User> create(@Valid @RequestBody UserRequest request) {
        String hash = request.password() == null ? null : passwordEncoder.encode(request.password());
        User created = users.insert(request.username(), request.displayName(), hash, request.role(),
                LocalDateTime.now(clock));
        return ResponseEntity.created(URI.create("/api/v1/users/" + created.id())).body(created);
    }

    @GetMapping
    public List<User> list() {
        return users.findAll();
    }
}
