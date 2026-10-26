package com.centry.web;

import com.centry.model.Subscription;
import com.centry.model.SubscriptionCadence;
import com.centry.model.SubscriptionStatus;
import com.centry.repository.SubscriptionRepository;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// "Now" is 15 March 2026 (FixedClockConfig).
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
class SubscriptionsPageTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 15);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SubscriptionRepository subscriptions;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM subscription").update();
    }

    private static RequestPostProcessor admin() {
        return user("admin").roles("ADMIN");
    }

    private static RequestPostProcessor mom() {
        return user("mom").roles("MEMBER");
    }

    private MockHttpServletRequestBuilder form(String url, String... params) {
        MockHttpServletRequestBuilder request = post(url).with(admin()).with(csrf());
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return request;
    }

    private Document page(RequestPostProcessor who) throws Exception {
        return Html.of(mockMvc.perform(get("/subscriptions").with(who)).andExpect(status().isOk()));
    }

    private void confirmed(String key, String name, SubscriptionCadence cadence, String amount) {
        subscriptions.upsertConfirmed(key, name, cadence, new BigDecimal(amount), LocalDate.of(2026, 4, 4), TODAY,
                LocalDateTime.of(2026, 3, 1, 0, 0));
    }

    private long candidate(String key, String name) {
        jdbc.sql("INSERT INTO subscription (merchant_key, name, cadence, expected_cents, next_due_on, status, "
                + "created_at) VALUES (?, ?, 'MONTHLY', 1549, '2026-04-04', 'CANDIDATE', '2026-03-01T00:00:00')")
                .params(key, name).update();
        return subscriptions.findByStatus(SubscriptionStatus.CANDIDATE).stream()
                .filter(s -> s.merchantKey().equals(key)).findFirst().orElseThrow().id();
    }

    private Subscription only(SubscriptionStatus status) {
        return subscriptions.findByStatus(status).getFirst();
    }

    @Test
    void adminAddsNetflixMonthlyAsConfirmed() throws Exception {
        mockMvc.perform(form("/subscriptions", "name", " Netflix ", "merchant", "", "cadence", "MONTHLY",
                        "amount", "15.49", "nextDue", "2026-04-04"))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/subscriptions"));

        Subscription s = only(SubscriptionStatus.CONFIRMED);
        assertThat(s.merchantKey()).isEqualTo("netflix");
        assertThat(s.name()).isEqualTo("Netflix");
        assertThat(s.cadence()).isEqualTo(SubscriptionCadence.MONTHLY);
        assertThat(s.expected()).isEqualByComparingTo("15.49");
        assertThat(s.nextDueOn()).isEqualTo(LocalDate.of(2026, 4, 4));
        assertThat(s.reviewedOn()).isEqualTo(TODAY);
        var card = page(admin()).selectFirst("#confirmed article");
        assertThat(card.text()).contains("Netflix").contains("Monthly").contains("15.49").contains("2026-04-04");
    }

    @Test
    void addingWithAMerchantMatchesThatKeyAndTakesOverACandidate() throws Exception {
        long id = candidate("netflix.com", "NETFLIX.COM");

        mockMvc.perform(form("/subscriptions", "name", "Netflix", "merchant", " Netflix.COM ", "cadence", "YEARLY",
                        "amount", "180", "nextDue", "2027-01-03"))
                .andExpect(redirectedUrl("/subscriptions"));

        Subscription s = subscriptions.findById(id).orElseThrow();
        assertThat(s.status()).isEqualTo(SubscriptionStatus.CONFIRMED);
        assertThat(s.name()).isEqualTo("Netflix");
        assertThat(s.cadence()).isEqualTo(SubscriptionCadence.YEARLY);
        assertThat(subscriptions.findByStatus(SubscriptionStatus.CANDIDATE)).isEmpty();
    }

    @Test
    void adminEditsExpectedAndCadence() throws Exception {
        confirmed("netflix", "Netflix", SubscriptionCadence.MONTHLY, "15.49");
        long id = only(SubscriptionStatus.CONFIRMED).id();

        var edit = page(admin()).selectFirst("#confirmed form[method=post][action=/subscriptions/" + id + "]");
        assertThat(edit.select("input[name=amount]").attr("value")).isEqualTo("15.49");
        assertThat(edit.select("input[name=nextDue]").attr("value")).isEqualTo("2026-04-04");
        assertThat(edit.select("select[name=cadence] option[selected]").attr("value")).isEqualTo("MONTHLY");
        assertThat(edit.select("input[name=_csrf]")).hasSize(1);

        mockMvc.perform(form("/subscriptions/" + id, "name", "Netflix 4K", "cadence", "YEARLY", "amount", "179.90",
                        "nextDue", "2027-03-04"))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/subscriptions"));

        Subscription s = subscriptions.findById(id).orElseThrow();
        assertThat(s.name()).isEqualTo("Netflix 4K");
        assertThat(s.merchantKey()).isEqualTo("netflix");
        assertThat(s.cadence()).isEqualTo(SubscriptionCadence.YEARLY);
        assertThat(s.expected()).isEqualByComparingTo("179.90");
        assertThat(s.nextDueOn()).isEqualTo(LocalDate.of(2027, 3, 4));
    }

    @Test
    void editingAnUnknownSubscriptionIs404() throws Exception {
        mockMvc.perform(form("/subscriptions/999", "name", "x", "cadence", "MONTHLY", "amount", "1", "nextDue",
                "2026-04-04")).andExpect(status().isNotFound());
        mockMvc.perform(form("/subscriptions/999/confirm")).andExpect(status().isNotFound());
        mockMvc.perform(form("/subscriptions/999/ignore")).andExpect(status().isNotFound());
    }

    @Test
    void adminConfirmsCandidate() throws Exception {
        long id = candidate("hulu", "Hulu");

        var card = page(admin()).selectFirst("#candidates article");
        assertThat(card.text()).contains("Hulu").contains("15.49");
        assertThat(card.select("form[action=/subscriptions/" + id + "/confirm]")).hasSize(1);
        assertThat(card.select("form[action=/subscriptions/" + id + "/ignore]")).hasSize(1);

        mockMvc.perform(form("/subscriptions/" + id + "/confirm"))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/subscriptions"));

        Subscription s = subscriptions.findById(id).orElseThrow();
        assertThat(s.status()).isEqualTo(SubscriptionStatus.CONFIRMED);
        assertThat(s.reviewedOn()).isEqualTo(TODAY);
    }

    @Test
    void adminIgnoresCandidate() throws Exception {
        long id = candidate("hulu", "Hulu");

        mockMvc.perform(form("/subscriptions/" + id + "/ignore"))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/subscriptions"));

        assertThat(subscriptions.findById(id).orElseThrow().status()).isEqualTo(SubscriptionStatus.IGNORED);
        Document page = page(admin());
        assertThat(page.select("#candidates article")).isEmpty();
        // Ignored ones sit in a closed <details>, and Remove puts them back to "never seen".
        assertThat(page.select("details#ignored article").text()).contains("Hulu");
        assertThat(page.select("details#ignored[open]")).isEmpty();
        assertThat(page.select("details#ignored form[action=/subscriptions/" + id + "/delete]")).hasSize(1);
    }

    @Test
    void adminDeletes() throws Exception {
        confirmed("netflix", "Netflix", SubscriptionCadence.MONTHLY, "15.49");
        long id = only(SubscriptionStatus.CONFIRMED).id();

        mockMvc.perform(form("/subscriptions/" + id + "/delete"))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/subscriptions"));

        assertThat(subscriptions.findById(id)).isEmpty();
        // Deleting what is already gone is not an error.
        mockMvc.perform(form("/subscriptions/" + id + "/delete")).andExpect(redirectedUrl("/subscriptions"));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "text amount|Netflix|MONTHLY|abc|2026-04-04",
            "zero amount|Netflix|MONTHLY|0|2026-04-04",
            "negative amount|Netflix|MONTHLY|-5.00|2026-04-04",
            "sub-cent amount|Netflix|MONTHLY|10.001|2026-04-04",
            "too many digits|Netflix|MONTHLY|1234567890.00|2026-04-04",
            "blank amount|Netflix|MONTHLY||2026-04-04",
            "unknown cadence|Netflix|WEEKLY|10.00|2026-04-04",
            "bad date|Netflix|MONTHLY|10.00|2026-02-30",
            "blank date|Netflix|MONTHLY|10.00|",
            "blank name|   |MONTHLY|10.00|2026-04-04"})
    void invalidInputRerendersWithError(String why, String name, String cadence, String amount, String due)
            throws Exception {
        confirmed("prime", "Prime", SubscriptionCadence.YEARLY, "139.00");
        long id = only(SubscriptionStatus.CONFIRMED).id();

        Document added = Html.of(mockMvc.perform(form("/subscriptions", "name", name, "merchant", "", "cadence",
                        cadence, "amount", amount, "nextDue", due))
                .andExpect(status().isUnprocessableContent()));
        Document edited = Html.of(mockMvc.perform(form("/subscriptions/" + id, "name", name, "cadence", cadence,
                        "amount", amount, "nextDue", due))
                .andExpect(status().isUnprocessableContent()));

        for (Document page : new Document[] {added, edited}) {
            assertThat(page.select("[role=alert]").text()).as(why).isNotBlank();
            assertThat(page.select("#confirmed article")).as(why).hasSize(1);
        }
        assertThat(subscriptions.findByStatus(SubscriptionStatus.CONFIRMED)).hasSize(1);
        assertThat(subscriptions.findById(id).orElseThrow().name()).isEqualTo("Prime");
    }

    @Test
    void whitespaceOnlyMerchantFallsBackToTheName() throws Exception {
        mockMvc.perform(form("/subscriptions", "name", "Hulu", "merchant", "   ", "cadence", "MONTHLY",
                "amount", "7.99", "nextDue", "2026-04-04")).andExpect(redirectedUrl("/subscriptions"));

        assertThat(only(SubscriptionStatus.CONFIRMED).merchantKey()).isEqualTo("hulu");
    }

    @Test
    void listsAnnualTotal() throws Exception {
        assertThat(page(admin()).select("#confirmed").text()).contains("No confirmed subscriptions");
        confirmed("netflix", "Netflix", SubscriptionCadence.MONTHLY, "15.49");
        confirmed("prime", "Prime", SubscriptionCadence.YEARLY, "139.00");
        candidate("hulu", "Hulu");

        // 15.49 * 12 + 139.00; candidates do not count.
        assertThat(page(admin()).selectFirst("#annual-total").text()).contains("324.88");
    }

    @Test
    void memberSeesTheListButNoForms() throws Exception {
        confirmed("netflix", "Netflix", SubscriptionCadence.MONTHLY, "15.49");
        candidate("hulu", "Hulu");

        Document page = page(mom());

        assertThat(page.select("#confirmed article").text()).contains("Netflix").contains("15.49");
        assertThat(page.select("#candidates article").text()).contains("Hulu");
        assertThat(page.select("main input, main select, main button")).isEmpty();
        assertThat(page.select("form").eachAttr("action")).containsExactly("/logout");
    }

    @Test
    void pageNeedsLogin() throws Exception {
        mockMvc.perform(get("/subscriptions").accept("text/html")).andExpect(status().isFound())
                .andExpect(redirectedUrl("/login"));
    }
}
