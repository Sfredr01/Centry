# Contributing to Centry

Thanks for helping. Centry is small on purpose, so changes should be small too.

## Ground rules

- **Test first.** Write a failing test, see it fail, make it pass, then refactor. Commit the test with the change
  that makes it pass, in the same PR.
- **Coverage is a gate.** `./gradlew check` must pass, and it requires 100% line and branch coverage. Do not
  lower the threshold or exclude code to get past it.
- **YAGNI and KISS.** Build what the current requirement needs. Don't add configuration, abstractions, or
  dependencies for hypothetical futures. Justify any new dependency in the PR.
- **One small PR per change.** Work on a branch off `main`. Keep unrelated cleanup out of the PR.

## Workflow

1. Create a branch: `git switch -c <short-topic> main`.
2. Add the failing test and run it: `./gradlew test --tests '<ClassName>'`.
3. Make it pass with the smallest change. Refactor while the tests stay green.
4. Run the full gate: `./gradlew check`.
5. For changes to the image, compose files, or scripts, run the smoke test: `scripts/smoke.sh`. It needs Docker
   and curl, and it builds the image locally.
6. Open a PR against `main`. CI runs the same gate on every PR.

## Commit messages

- Use an imperative subject line, for example `Send unknown-spender expenses to the DLT`.
- Keep the subject under about 72 characters. Explain why in the body when the reason is not obvious.

## Where things are

- `docs/ROADMAP.md` holds the milestones and the feature spec. Check it before starting larger work, and link the
  milestone your PR belongs to.
- `docker-compose.yml` is the default stack. `docker-compose.kafka.yml` and `docker-compose.smoke.yml` are
  overrides.
- `scripts/` holds the smoke test and the upgrade scripts. They are POSIX shell, pass `shellcheck`, and use only
  `curl` and `docker` beyond core tools.

## Reporting bugs and security issues

Open an issue for ordinary bugs. For security problems, follow [SECURITY.md](SECURITY.md) and do not open a
public issue.
