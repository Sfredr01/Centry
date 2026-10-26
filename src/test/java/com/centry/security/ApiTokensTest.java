package com.centry.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiTokensTest {

    @Test
    void generatesPrefixedUrlSafeTokensOf256Bits() {
        String token = ApiTokens.generate();

        assertThat(token).matches("centry_[A-Za-z0-9_-]{43}");
        assertThat(ApiTokens.isWellFormed(token)).isTrue();
        assertThat(ApiTokens.generate()).isNotEqualTo(token);
    }

    @Test
    void hashIsSha256Hex() {
        assertThat(ApiTokens.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void unknownAlgorithmIsAProgrammingError() {
        assertThatThrownBy(() -> ApiTokens.digest("NOPE-256", "x")).isInstanceOf(IllegalStateException.class);
    }
}
