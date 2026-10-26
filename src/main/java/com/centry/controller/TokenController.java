package com.centry.controller;

import com.centry.dto.TokenRequest;
import com.centry.model.ApiToken;
import com.centry.repository.ApiTokenRepository;
import com.centry.repository.UserRepository;
import com.centry.security.ApiTokens;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The caller's own API tokens. Bearer tokens never reach here (see BearerTokenFilter), so minting needs a password.
 */
@RestController
@RequestMapping("/api/v1/tokens")
public class TokenController {

    /** The only response that ever carries the plain token. */
    public record Issued(long id, String name, String token) {
    }

    private final ApiTokenRepository tokens;
    private final UserRepository users;
    private final Clock clock;

    public TokenController(ApiTokenRepository tokens, UserRepository users, Clock clock) {
        this.tokens = tokens;
        this.users = users;
        this.clock = clock;
    }

    @PostMapping
    public ResponseEntity<Issued> issue(@Valid @RequestBody TokenRequest request, Authentication caller) {
        String token = ApiTokens.generate();
        long id = tokens.insert(userId(caller), request.name(), ApiTokens.hash(token), LocalDateTime.now(clock));
        return ResponseEntity.created(URI.create("/api/v1/tokens/" + id))
                .body(new Issued(id, request.name(), token));
    }

    @GetMapping
    public List<ApiToken> list(Authentication caller) {
        return tokens.findByUser(userId(caller));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@PathVariable long id, Authentication caller) {
        // Someone else's token and an unknown id look the same, so existence is not revealed.
        if (!tokens.delete(id, userId(caller))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Token " + id + " not found");
        }
        return ResponseEntity.noContent().build();
    }

    private long userId(Authentication caller) {
        return users.idByUsername(caller.getName()).orElseThrow();
    }
}
