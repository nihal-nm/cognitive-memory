# PR Check Framework

## Current State

Issue [#21](https://github.com/rigazilla/cognitive-memory/issues/21) tracks automated PR validation. The following checks are implemented and run in CI on every pull request via `.github/workflows/quarkus-build-and-test.yml`.

### Build & Test

- **Build**: `./mvnw -B clean package -DskipTests` (ubuntu + macOS matrix)
- **Unit tests**: `./mvnw -B test` (ubuntu + macOS matrix)
- **Docker health check**: builds and starts the container, verifies `/q/health/live` (amd64 + arm64)

### Code Quality (Linux only)

#### Checkstyle

- **Plugin**: `maven-checkstyle-plugin` 3.6.0 with Checkstyle 10.21.4
- **Config**: `checkstyle.xml` in project root
- **Rules**: line length 120, no unused imports, no star imports, braces on all control structures, consistent `}` placement, trailing newline
- **CI**: generates report (`checkstyle:checkstyle`), annotates PR via [`motlin/checkstyle-results@v1`](https://github.com/motlin/checkstyle-results), fails build on violations (`checkstyle:check`)
- **Run locally**: `./mvnw -B checkstyle:check`

#### SpotBugs

- **Plugin**: `spotbugs-maven-plugin` 4.10.3.0
- **Config**: `spotbugs-exclude.xml` in project root
- **Exclusions**: CDI/Quarkus false positives (EI_EXPOSE_REP, SING_SINGLETON_IMPLEMENTS_SERIALIZABLE, SE_BAD_FIELD, MS_EXPOSE_REP, REC_CATCH_EXCEPTION, THROWS_METHOD_THROWS_RUNTIMEEXCEPTION, VA_FORMAT_STRING_USES_NEWLINE, RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE) and generated protobuf code
- **Scope**: only analyzes `io.github.rigazilla.*` (excludes generated protobuf classes)
- **CI**: generates report (`spotbugs:spotbugs`), annotates PR via local composite action [`.github/actions/spotbugs-reporter`](.github/actions/spotbugs-reporter), fails build on violations (`spotbugs:check`)
- **Run locally**: `./mvnw -B spotbugs:check`

#### Code Coverage (JaCoCo)

- **Plugin**: `jacoco-maven-plugin` 0.8.13
- **Goals**: `prepare-agent` (instruments classes), `report` (generates HTML/XML after test phase)
- **CI**: generates report (`jacoco:report`), publishes summary to GitHub workflow summary page via [`Madrapps/jacoco-report@v1.7.2`](https://github.com/Madrapps/jacoco-report) with `comment-type: summary`
- **Thresholds**: target 80% line / 70% branch (not enforced yet — no tests exist)
- **Run locally**: `./mvnw -B test jacoco:report`, then open `target/site/jacoco/index.html`

### GitHub Annotations

Checkstyle and SpotBugs violations appear as inline annotations on the PR diff using native `::error`/`::warning` workflow commands. This works for all PRs including those from forks (no special permissions required).

- **Checkstyle**: via `motlin/checkstyle-results@v1` (parses checkstyle XML, emits `::error`/`::warning`)
- **SpotBugs**: via `.github/actions/spotbugs-reporter` (local composite action with Python script, same approach — designed to be extractable as a standalone action)

## Remaining Work

### Not Yet Implemented

The following items from issue #21 are not yet done:

- [ ] **Unit tests**: no `src/test/java` directory exists yet — tests need to be written before coverage thresholds can be enforced
- [ ] **JaCoCo threshold enforcement**: add `jacoco:check` goal with `minimum 0.80` line / `0.70` branch once test coverage is sufficient
- [ ] **Code formatting**: Google Java Format or similar auto-formatter (issue #21 mentions this)

### Future Considerations

These are ideas from the original task document, not committed work:

- **Integration tests**: `*IT.java` pattern with failsafe plugin (already configured in pom.xml but `skipITs=true`)
- **OWASP dependency check**: vulnerability scanning for dependencies
- **ArchUnit**: architecture compliance tests (package dependencies, layer separation)
- **Pre-commit hooks**: local formatting/check enforcement before commit

## File Locations

```
cognition-processor-quarkus/
├── checkstyle.xml                          # Checkstyle rules
├── spotbugs-exclude.xml                    # SpotBugs exclusion filters
├── pom.xml                                 # Plugin configuration
.github/
├── workflows/
│   ├── ci.yml                              # PR trigger (calls reusable workflows)
│   ├── cd.yml                              # Push-to-main trigger
│   ├── quarkus-build-and-test.yml          # Build, test, quality checks
│   └── docker-build-test.yml               # Docker build + health check
├── actions/
│   └── spotbugs-reporter/                  # Local composite action for SpotBugs annotations
│       ├── action.yml
│       └── spotbugs_reporter.py
```
