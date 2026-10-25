package com.centry.security;

import com.centry.AdminBasicAuth;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AdminBasicAuth.class)
class UserApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM users WHERE username <> 'admin'").update();
    }

    private ResultActions create(String json) throws Exception {
        return mockMvc.perform(post("/api/v1/users").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void adminCreatesMember201WithoutPasswordInBody() throws Exception {
        String body = create("{\"username\":\"Mia.K\",\"displayName\":\"Mia\",\"password\":\"correct horse battery\","
                + "\"role\":\"MEMBER\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("Mia.K"))
                .andExpect(jsonPath("$.displayName").value("Mia"))
                .andExpect(jsonPath("$.role").value("MEMBER"))
                .andExpect(jsonPath("$.createdAt").isString())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        int id = JsonPath.read(body, "$.id");
        assertThat(body).doesNotContain("correct horse").doesNotContain("bcrypt");
        mockMvc.perform(get("/api/v1/users")).andExpect(status().isOk());
        create("{\"username\":\"x-check\",\"displayName\":\"X\",\"role\":\"MEMBER\"}")
                .andExpect(header().string("Location", org.hamcrest.Matchers.matchesPattern("/api/v1/users/\\d+")));
        assertThat(jdbc.sql("SELECT password_hash FROM users WHERE id = ?").param(id).query(String.class).single())
                .startsWith("{bcrypt}");
        // No password: a member who cannot log in.
        assertThat(jdbc.sql("SELECT password_hash FROM users WHERE username = 'x-check'")
                .query(String.class).optional()).isEmpty();
    }

    @Test
    void duplicateUsername409() throws Exception {
        create("{\"username\":\"dupe\",\"displayName\":\"A\",\"role\":\"MEMBER\"}").andExpect(status().isCreated());

        create("{\"username\":\"DUPE\",\"displayName\":\"B\",\"role\":\"ADMIN\"}")
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409));
        assertThat(jdbc.sql("SELECT count(*) FROM users WHERE username = 'dupe'").query(Integer.class).single())
                .isEqualTo(1);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "short username|{\"username\":\"ab\",\"displayName\":\"A\",\"role\":\"MEMBER\"}|username",
            "bad chars|{\"username\":\"a b!\",\"displayName\":\"A\",\"role\":\"MEMBER\"}|username",
            "blank display name|{\"username\":\"okname\",\"displayName\":\"\",\"role\":\"MEMBER\"}|displayName",
            "missing role|{\"username\":\"okname\",\"displayName\":\"A\"}|role",
            "short password|{\"username\":\"okname\",\"displayName\":\"A\",\"password\":\"elevenchars\",\"role\":\"MEMBER\"}|password",
            "empty password|{\"username\":\"okname\",\"displayName\":\"A\",\"password\":\"\",\"role\":\"MEMBER\"}|password",
            // 40 chars but 80 UTF-8 bytes: over bcrypt's 72-byte input limit.
            "password over 72 bytes|{\"username\":\"okname\",\"displayName\":\"A\",\"password\":\"éééééééééééééééééééééééééééééééééééééééé\",\"role\":\"MEMBER\"}|password"})
    void invalid400(String name, String json, String field) throws Exception {
        create(json)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.errors." + field).isString());
        assertThat(jdbc.sql("SELECT count(*) FROM users").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void invalidRole400() throws Exception {
        create("{\"username\":\"okname\",\"displayName\":\"A\",\"role\":\"ROOT\"}")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void listHasNoHashes() throws Exception {
        create("{\"username\":\"pw-user\",\"displayName\":\"P\",\"password\":\"a-long-enough-pw\",\"role\":\"MEMBER\"}")
                .andExpect(status().isCreated());
        create("{\"username\":\"kid\",\"displayName\":\"Kid\",\"role\":\"MEMBER\"}").andExpect(status().isCreated());

        String body = mockMvc.perform(get("/api/v1/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].username").value("admin"))
                .andExpect(jsonPath("$[0].role").value("ADMIN"))
                .andExpect(jsonPath("$[1].username").value("pw-user"))
                .andExpect(jsonPath("$[2].username").value("kid"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("bcrypt").doesNotContain("assword").doesNotContain("hash");
    }
}
