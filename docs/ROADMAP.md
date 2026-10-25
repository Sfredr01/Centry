# Centry — Staff Review, Remediation Plan & Extension SPEC

## Context
Centry is meant to be an open-source, self-hosted expense and subscription guardian that a family runs on a Raspberry Pi. The owner asked for three things:
- A review of the current approach against YAGNI, KISS, Lean and TDD.
- A remediation plan.
- A phased SPEC for features that make it valuable to a family.

Binding owner decisions (2026-10-25):
- **Keep Kafka, but isolate it** as an optional `kafka` profile that is off by default.
- **Ingestion:** a phone-friendly manual quick-add UI first; receipt/email parsing later.
- **DB:** SQLite file plus Flyway.

Exploration: 3 parallel agents (Sonnet ×2 for code and tests, Haiku for deploy and docs), an Opus design pass, and a manual read of the core files.

---

## 1. Review verdict

### Architecture (YAGNI / KISS / Lean)
| Finding | Evidence | Verdict |
|---|---|---|
| HTTP → Kafka → same JVM. A producer and consumer in one process, plus a broker using about 700MB RAM on the Pi | `ExpenseController` → `ExpenseProducer` → `ExpenseConsumer` | Accidental complexity. Isolate it behind a profile |
| Budget totals and subscription history live in `ConcurrentHashMap`s, a second source of truth beside the DB. Both are lost on restart | `BudgetTrackingService.java:19`, `SubscriptionTrackingService.java:23` | Delete. Derive the values with SQL |
| `YearMonth.now()` instead of the event date, with no `Clock` or zone | `BudgetTrackingService.java:34` | Bug: backdated expenses land in the wrong month, and rollover can't be tested |
| No idempotency: `transactionId` is not unique, and the Kafka retries re-run side effects | `ExpenseEntity`, `KafkaTopicConfig.java:45` | Bug: double counting |
| No `@Transactional`. Partial side effects happen on failure | `ExpenseConsumer.java:62-138` | Bug |
| Alert spam: every expense after the threshold re-alerts and re-webhooks | `ExpenseConsumer.java:84` | Bug |
| The "subscription detector" alerts on any price rise at any merchant (groceries too). `daysBetween` is computed but never used | `SubscriptionTrackingService.java:44-72` | Wrong model. Redesign it |
| `subscription-alerts` topic has no consumer. The DLT recoverer routes every listener's failures to `raw-expenses.DLT`. There is no `ErrorHandlingDeserializer` | `KafkaTopicConfig` | Dead code, and a poison message loops forever |
| Each alert is modelled 3–4 times (Event, Entity, `BudgetStatus`) | `model/`, `entity/` | Duplication. Collapse to one `alert` table |
| `ExpenseConsumer` does 6 jobs in one method | 139 lines | Split it into a use case, a policy and a dispatcher |
| Prometheus counters and exposure with no Prometheus running. `findAll()` labelled "recent". Several repository and service methods are unused | `ExpenseConsumer`, `DashboardController.java:53` | YAGNI. Delete them |
| No input validation (null or negative amounts accepted). The 200 response is sent before persistence | `ExpenseController` | Bug |

### Security
- There is no authentication.
- Actuator uses `show-details: always` and exposes `metrics` and `prometheus`.
- Ports 8080 and 9092 (Kafka, PLAINTEXT) are published to the LAN.
- H2 runs as `sa` with an empty password and `AUTO_SERVER` TCP enabled.
- Watchtower mounts `docker.sock`.

### Deployment (current blockers)
1. The Dockerfile's `COPY build/libs/*.jar` also matches the plain jar, so the build fails.
   - Its base image `eclipse-temurin:23-{jdk,jre}-jammy` also returned "not found" on Docker Hub, so the build may fail before it gets that far.
2. The H2 path `./data` is not on the mounted `/data` volume, so data is lost whenever the container is recreated.
3. The prod compose advertises `localhost:9092`, so the app container can't reach Kafka.
4. The toolchain is Java 23 (non-LTS), but the README says 17.
   - The README also claims Confluent images are x86-only. That is wrong: `cp-kafka:7.4.1` is published for both amd64 and arm64.
5. Gradle builds arm64 under QEMU.
6. SETUP_PI.md contradicts the compose file (service names, image source, YAML indentation).
7. There is no LICENSE, CONTRIBUTING, SECURITY or `.env.example`.

### TDD verdict: **not TDD**
- There are 4 shallow tests:
  - `ExpenseControllerTest` asserts only HTTP 200.
  - `contextLoads` needs Kafka.
- Every feature commit touched production code only.
- The tests, `.github/`, `entity/`, `repository/` and `DashboardController` are **untracked**, so CI on `main` doesn't even contain them.
- CI runs only on push to main. There is no PR gate.

---

## 2. Target architecture

- **Package by feature:** `expense`, `budget`, `alert`, `notify`, `user`, `subscription` (Phase 2), `web` (+`web/api`), `kafka` (`@Profile("kafka")`), `config`.
- **Kafka seam:** `ExpenseService.record(NewExpense)` is the only port. It gets no extra interface, because there is only one implementation.
  - Web calls it directly.
  - Inside the app, events are Spring `ApplicationEvent`s (`ExpenseRecorded`, `AlertRaised`).
  - With the `kafka` profile on:
    - `KafkaExpenseListener` consumes `raw-expenses` and calls `ExpenseService`.
    - `KafkaAlertBridge` (`@TransactionalEventListener`) republishes alerts.
    - Add `ErrorHandlingDeserializer`, and send only non-retryable errors to the DLT.
  - The default profile excludes `KafkaAutoConfiguration`, so no Kafka beans exist.
- **Persistence:**
  - SQLite (xerial), Flyway, Spring `JdbcClient`. JPA and Hibernate are dropped, because SQLite needs a community dialect and the domain is plain aggregates.
  - WAL mode, `busy_timeout=5000`, `foreign_keys=ON`, Hikari pool of 1.
  - DB file at `/data/centry.db`.
- **Money:** store it as an INTEGER `amount_cents`. SQLite NUMERIC is REAL, so it drifts. Each install has one currency.
- **Schema V1:**
  - `users`: `password_hash` can be NULL, for a member who can't log in. `role` is ADMIN or MEMBER.
  - `category`
  - `budget(category_id PK, monthly_limit_cents>0, warn_pct 1..99)`
  - `expense(client_ref UNIQUE, occurred_on DATE, amount_cents>0, merchant, merchant_key, category_id, spent_by, source, created_by)`
  - Indexes: `(category_id, occurred_on)` and `(merchant_key, occurred_on)`.
  - `alert(kind, subject, period, level, message, notified_at, UNIQUE(kind,subject,period,level))`
- **Budget status is a query, not a cache.** A month's `SUM` over an index is sub-millisecond at family scale.
- **Alert dedupe:** in the expense transaction, compute the level, then `INSERT … ON CONFLICT DO NOTHING`. Publish `AlertRaised` only if a row was inserted. This fires at most once per category, level and month.
- **Time:** inject `Clock` and `ZoneId` (`centry.zone`). Store `occurred_on` as a `LocalDate`. Use "now" only for form defaults and to pick the current dashboard month.
- **Auth:**
  - Spring Security form login, remember-me (30d), BCrypt, CSRF.
  - Bootstrap the first admin from `CENTRY_ADMIN_PASSWORD`. Refuse to start if there are no users and that variable is unset.
  - Actuator exposes `health` only, with `show-details: never`.
- **UI:** Thymeleaf, htmx and Pico.css, all vendored. There is no Node toolchain. PWA support is a manifest plus icons, with no service worker in Phase 1. HTTPS comes from Tailscale `serve` or Caddy (documented).
- **Notify:**
  - `Notifier` interface with `NtfyNotifier` and `WebhookNotifier`. Two transports make this a real seam.
  - `AlertDispatcher` uses `@TransactionalEventListener(AFTER_COMMIT)` and sets `notified_at`.
  - A `@Scheduled` sweeper retries unsent alerts that are less than 24h old.
  - Use an injected `RestClient.Builder`.
- **Platform:** Java 21 LTS. Remove Lombok as files are rewritten (use records). Pick the Boot version in M1: 3.3.x is out of OSS support, and 4.x needs a spike.

---

## 3. Remediation milestones
Each milestone is one PR. Red, green, refactor. Tests are committed in the same PR, and CI stays green.

| M | Goal | Tests written FIRST (named) | Change / **DELETE** |
|---|---|---|---|
| **M0** | Stabilize | `CentryApplicationTests.contextLoads` passes with Kafka and Docker down (`@EmbeddedKafka`, temporary) | Commit the untracked sources and tests in logical commits. `.gitignore` gets `data/ build/ bin/`. New `ci.yml` runs `./gradlew test` on `pull_request` and on push. Make `deploy` depend on tests (`needs:`). Gradle: `jar{enabled=false}`, `bootJar{archiveFileName='centry.jar'}`. Move the toolchain and Dockerfile base to Java 21 (`eclipse-temurin:21-jre`). The 23-jammy tag is missing. Owner enables branch protection |
| **M1** | Toolchain and image | `BuildSmoke`: CI runs the built image | Boot bump (spike), README Java version. CI builds the jar on amd64, then buildx copies it into an arm64 JRE image (no QEMU Gradle) |
| **M2** | Isolate Kafka | `DefaultProfileTest.startsWithNoKafkaBeans`, `ExpenseApiTest.postPersistsSynchronously`, `KafkaProfileIngestTest.messageInvokesExpenseService`, `.malformedJsonGoesToDltWithoutRetry` | Move to `kafka/` under the profile. **DELETE:** `ExpenseProducer` from the request path, the `subscription-alerts` topic, `BudgetAlertConsumer`, the blanket DLT recoverer, `@EmbeddedKafka` in contextLoads |
| **M3** | SQLite, Flyway, cents | `MigrationTest.v1AppliesOnEmptyDb`, `.rejectsNonPositiveAmount`, `.rejectsDuplicateClientRef`, `.enforcesForeignKeys`, `ExpenseRepositoryTest.sumsCategoryWithinMonthBounds` | `V1__init.sql`, JdbcClient repositories. Spike flyway-sqlite compatibility first. **DELETE:** H2, JPA, `entity/*`, `model/*`, `dto/ExpenseEvent` (replaced by the `NewExpense` record). H2 data is treated as disposable (16KB dev file) |
| **M4** | Record-expense use case | `ExpenseServiceTest.rejectsNullOrNonPositiveAmount`, `.monthDerivedFromOccurredOnNotClock`, `.duplicateClientRefReturnsExisting`, `.normalizesMerchantKey`, `.rollsBackOnDownstreamFailure`; `ExpenseApiTest.invalidPayload400WithFieldErrors`, `.created201WithLocation`, `.replaySameClientRefReturns200NoDuplicate` | `spring-boot-starter-validation`, `@Transactional`, `ClockConfig` |
| **M5** | Derived budgets and alert dedupe | `BudgetStatusQueryTest.onlyCountsEventMonthInHouseholdZone`; `BudgetAlertPolicyTest.noAlertBelowWarn`, `.warnOnceOnCrossing`, `.exceededOnceAt100`, `.jumpPastBothEmitsExceededOnly`, `.backdatedAlertsItsOwnMonth`, `.newMonthResets`; `AlertRepositoryTest.duplicateInsertIgnored`; `PersistenceTest.stateSurvivesContextRestart` | `GET/PUT /api/v1/budgets/{categoryId}`. **DELETE:** `BudgetTrackingService`, `BudgetConfigProperties`, YAML budgets, `BudgetStatus` |
| **M6** | Notify | `NtfyNotifierTest.postsTitlePriorityHeaders`, `WebhookNotifierTest.discordSlackPayload`, `AlertDispatcherTest.sendsOnlyAfterCommit`, `.rollbackSendsNothing`, `.failureLeavesNotifiedAtNull`, `RetrySweeperTest.resendsUnsentUnder24h` (`MockRestServiceServer`) | New `notify/` package |
| **M7** | Security | `SecurityTest.anonymousPageRedirectsToLogin`, `.anonymousApi401`, `.healthPublicNoDetails`, `.prometheusAndMetricsNotExposed`, `.memberCannotManageUsersOrBudgets`, `.formPostWithoutCsrf403`, `.rememberMeCookieIssued`; `AdminBootstrapTest.createsAdminFromEnv`, `.failsStartWhenNoUsersAndNoEnv` | `SecurityConfig`, `user/`. Existing MockMvc tests get `@WithMockUser` |
| **M8** | UI walking skeleton | `QuickAddPageTest.defaultsTodayFromClockAndHasClientRef`, `.invalidAmountRerendersWithError`, `.htmxPostReturnsFragmentAndResetsForm`; `DashboardPageTest.showsMonthBudgetBarsAndRecent`; `BudgetsPageTest.adminEditsLimit`; `ManifestTest.servesManifest` | Thymeleaf templates, vendored htmx and Pico. **DELETE:** the JSON dashboard endpoints the UI replaces |
| **M9** | Deploy hardening and OSS readiness | `scripts/smoke.sh` (see §6) | Default `docker-compose.yml` is the app only: `/data` volume, `.env`, `-Xmx256m -XX:+UseSerialGC`. Kafka moves to an override `docker-compose.kafka.yml`: advertised `kafka:9092`, retention 168h/256MB, 256m heap, bound to 127.0.0.1. Pin image tags. **DELETE Watchtower.** Add `scripts/upgrade.sh` (backup, then pin tag, then rollback by tag). Add `.env.example`, LICENSE (owner picks MIT or Apache-2.0), CONTRIBUTING (TDD rules), SECURITY.md. Merge README and SETUP_PI into one install path |

---

## 4. Extension SPEC

### Phase 1: MVP (delivered by M3–M8; nothing else)
**F1 Household members and attribution.** "As a parent I see who spent what."
- Given an admin adds a member with no password, then that member can be picked as spender but can't log in.
- Given a member opens quick-add, then the spender defaults to them.
- Given a member filter on the dashboard, then the totals cover only that member.
- Non-goals: per-member budgets, per-category permissions.

**F2 Quick-add PWA, plus edit and delete.**
- Given a phone, when the user enters amount, picks category and taps Save, then the expense is stored within 3 taps after the amount. Merchant is optional.
- Given Save is double-tapped (same `client_ref`), then one row is stored.
- Given the creator or an admin edits or deletes an expense, then the totals update. Alerts already sent stay.
- Non-goals: offline queue, service worker, split transactions, refunds.

**F3 Budgets editable in the UI** (moved out of YAML).
- Given an admin sets Groceries to $600 with a warning at 80%, then the budget applies to the current month immediately.
- Given a category has no budget, then it shows no bar and never alerts.
- Non-goals: limit history, rollover, envelopes.

**F4 Budget alerts over ntfy or webhook.**
- Given spending crosses 80%, then exactly one WARN is sent per month.
- Given spending later crosses 100%, then one EXCEEDED is sent.
- Given ntfy is down and recovers within 24h, then the alert is delivered.
- Non-goals: quiet hours, per-user routing.

### Phase 2
**F5 Subscriptions: registry first, detection second.** Manual entry means sparse data, so the manual registry comes first.
- Data: `subscription(merchant_key UNIQUE, name, cadence MONTHLY|YEARLY, expected_cents, next_due_on, status CANDIDATE|CONFIRMED|IGNORED, reviewed_on)`.
- Detection:
  - Monthly: at least 3 charges with a median interval of 28–35 days.
  - Yearly: at least 2 charges 360–371 days apart.
  - Every amount must be within ±10% of the median.
  - A match becomes a **CANDIDATE**, and the user must confirm it.
- Alerts:
  - `PRICE_CHANGE`: the charge differs from `expected_cents` by more than 2%.
  - `RENEWAL_UPCOMING`: yearly subscriptions, 7 days before the due date.
  - `FORGOTTEN`: in the digest, when `reviewed_on` is more than 6 months old. Shows the annualized cost and a one-tap "still using".
- Acceptance:
  - Given Netflix at $15.49 on 1/3, 2/3 and 3/4, then a candidate is created.
  - Given weekly grocery charges of varying amounts, then no candidate is created.
  - Given a confirmed $15.49 subscription is charged $17.99, then one PRICE_CHANGE is sent.
  - Given a yearly subscription due 4/10, then a push is sent on 4/3.
- Non-goals: cancelling on the user's behalf, weekly cadence.

**F6 Monthly digest.**
- Given it is the 1st at 08:00 in the household zone, then one ntfy push covers last month: category vs budget, top 3 merchants, totals per member, annual subscription total.
- Given the app restarts, then the digest isn't resent (`alert kind=DIGEST` dedupe).
- Non-goals: SMTP, charts in the push.

**F7 CSV export and backup/restore.**
- Given an admin picks a date range, then a CSV downloads with decimal-string amounts.
- Given the nightly job runs, then `VACUUM INTO /data/backups/centry-YYYYMMDD.db` runs and the last 14 backups are kept.
- Given `scripts/restore.sh` runs, then the app starts with the restored data.
- Non-goals: CSV import, cloud sync.

**F8 Per-user API token** for iOS Shortcuts and Tasker: a bearer token on `POST /api/v1/expenses`. Non-goal: OAuth.

### Phase 3 (optional modules, off by default)
**F9 Receipt and email parsing (local-first).**
- `@Profile("mail")` polls a dedicated mailbox over IMAP. Per-sender regex rules are kept in config.
- Each match creates a **DRAFT** expense (`source=email`, `client_ref=Message-ID`) through `ExpenseService`. A user confirms it in the UI.
- Receipt photos: an optional tesseract sidecar under `@Profile("ocr")` with a compose override.
- Acceptance:
  - Given an email from a known sender, then a prefilled draft is created.
  - Given an email that can't be parsed, then it is skipped and logged.
  - Given a duplicate Message-ID, then no new draft is created.
- Non-goals: cloud OCR, default LLM parsing, bank feeds.

**F10 Shared savings goals.** Build only if the family asks for it after Phase 2. It is a `goal` table with manual contributions.

---

## 5. Delegation and parallelism
| Work | Executor | Reviewer | Order |
|---|---|---|---|
| M0 | Haiku (commits, gitignore, CI YAML), Sonnet (hermetic test) | Opus | First, alone |
| M1 → M2 → M3 → M4 → M5 | Sonnet (TDD implementation) | **Opus** for the M2 seam, M3 schema, M5 policy | Sequential. They share `build.gradle` and core files |
| M6 ‖ M7 | Sonnet ×2 in parallel worktrees | Opus (M7 security) | Parallel: `notify/` and `config/SecurityConfig` don't overlap. Merge M6 first |
| M8 | Sonnet | Opus (UX and integration) | After M5 and M7 |
| M9 docs (LICENSE, CONTRIBUTING, unified README) | Haiku | Opus | Parallel any time after M0 |
| M9 compose and scripts | Haiku | Opus | After M3 |
| F6 ‖ F7 ‖ F8 | Sonnet ×3 | — | Parallel. F5 needs an Opus design review first |
| Smoke runs (CI and Pi) | Haiku | — | After M9 |

---

## 6. Verification
- **Per PR:** `./gradlew test` must be green in the CI PR gate. Each milestone's named tests are written and fail first, then pass.
- **Walking skeleton (`scripts/smoke.sh`), run on CI amd64 and on the Pi arm64:**
  1. Start the stack with `docker compose -f docker-compose.yml -f docker-compose.smoke.yml up -d`. The smoke override adds a local ntfy container.
  2. Poll `/actuator/health` until it reports UP. Check that it shows no details and that `/actuator/prometheus` returns 404.
  3. Log in with curl, using a cookie jar and CSRF.
  4. PUT a $100 Groceries budget.
  5. Post $85, then check for exactly 1 WARN in ntfy.
  6. Post $1, then check there is still 1 WARN.
  7. Post $20, then check for 1 EXCEEDED.
  8. Restart the app, post again, then check no duplicate push was sent.
  9. Run `down` and `up`, then check the data persisted.
  10. Measure RSS. It must stay under 400MB.
- **Manual check on the Pi:** log in over Tailscale HTTPS, install the PWA, quick-add an expense, and receive the push.
- **Phase 1 is done** when the smoke test passes on the Pi.

## 7. Risks
- **SQLite write contention:** mitigated by WAL and a pool of 1.
- **SD card corruption:** recommend a USB SSD, run nightly `VACUUM INTO`, and have `upgrade.sh` take a backup before migrating.
- **flyway-sqlite compatibility:** spike it in M3.
- **PWA install needs HTTPS:** document the setup.
- **Boot upgrade:** isolate it in M1.
- **Subscription false positives:** detection only proposes CANDIDATEs.
- **H2 data:** assumed disposable.

## Critical files
- `src/main/java/com/centry/service/ExpenseConsumer.java`: split into `ExpenseService`, `BudgetAlertPolicy` and `AlertDispatcher`.
- `build.gradle`: Java 21, new dependencies, H2, JPA and prometheus removed, bootJar naming.
- `src/main/resources/application.yml`: profiles, actuator lockdown, SQLite, Kafka excluded by default.
- `src/main/java/com/centry/config/KafkaTopicConfig.java`: moves to `kafka/` under the profile.
- `Dockerfile`, `docker-compose.prod.yml`, `.github/workflows/deploy.yml`, `SETUP_PI.md`, `README.md`.
