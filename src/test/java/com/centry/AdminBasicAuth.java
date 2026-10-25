package com.centry;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Sends every MockMvc request as the bootstrap admin over HTTP Basic, so tests written before M7 keep their
 * requests and assertions unchanged. Basic is real authentication here (and needs no CSRF token).
 */
@TestConfiguration
public class AdminBasicAuth {

    public static final String USERNAME = "admin";
    public static final String PASSWORD = "test-admin-password";

    @Bean
    MockMvcBuilderCustomizer adminBasicByDefault() {
        return builder -> builder.defaultRequest(get("/").with(httpBasic(USERNAME, PASSWORD)));
    }
}
