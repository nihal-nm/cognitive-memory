# TODO: GitHub CI Workflow Setup

**Priority**: MEDIUM  
**Status**: Not started  
**Blocked by**: `unit-test-framework-setup.md` (partially)

## Problem

The project currently has no CI/CD pipeline configured. This means:
- No automated builds on pull requests
- No automated test execution
- No code quality checks
- No build artifact validation
- Contributors don't get immediate feedback on their changes

A CI pipeline ensures code quality, prevents regressions, and gives contributors confidence that their changes work.

## Context

This project is a Quarkus-based Java 21 application that:
- Uses Maven for builds
- Will have unit tests (see `unit-test-framework-setup.md`)
- Will have integration tests requiring external services (see `integration-test-framework-setup.md`)
- Needs to build both JVM and potentially native images
- Requires gRPC code generation from protobuf files

## Goals

1. **Build validation** - Ensure code compiles on every PR
2. **Test automation** - Run unit tests automatically
3. **Code quality** - Check formatting, linting
4. **Dependency security** - Scan for vulnerable dependencies
5. **Integration testing** - Optional workflow for integration tests
6. **Release automation** - Future: automate version tagging and releases

## Tasks

**Working directory**: Repository root (not cognition-processor-quarkus/)

### 1. Create GitHub workflows directory structure

```bash
mkdir -p .github/workflows
```

**Expected**: Directory created at repository root

### 2. Create main CI workflow

**File**: `.github/workflows/ci.yml`

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

jobs:
  build:
    name: Build and Test
    runs-on: ubuntu-latest
    
    steps:
      - name: Checkout code
        uses: actions/checkout@v4
      
      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: maven
      
      - name: Build with Maven
        run: mvn -B clean verify -DskipTests
        working-directory: cognition-processor-quarkus
      
      - name: Run unit tests
        run: mvn -B test
        working-directory: cognition-processor-quarkus
      
      - name: Generate test coverage report
        if: success()
        run: mvn -B jacoco:report
        working-directory: cognition-processor-quarkus
      
      - name: Upload coverage to Codecov
        if: success()
        uses: codecov/codecov-action@v4
        with:
          files: ./cognition-processor-quarkus/target/site/jacoco/jacoco.xml
          flags: unittests
          name: codecov-umbrella
          fail_ci_if_error: false
      
      - name: Archive build artifacts
        if: success()
        uses: actions/upload-artifact@v4
        with:
          name: build-artifacts
          path: |
            cognition-processor-quarkus/target/*.jar
            cognition-processor-quarkus/target/quarkus-app/
          retention-days: 7

  code-quality:
    name: Code Quality Checks
    runs-on: ubuntu-latest
    
    steps:
      - name: Checkout code
        uses: actions/checkout@v4
      
      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: maven
      
      - name: Check code formatting
        run: mvn -B formatter:validate
        working-directory: cognition-processor-quarkus
        continue-on-error: true
      
      - name: Run static analysis
        run: mvn -B compile spotbugs:check
        working-directory: cognition-processor-quarkus
        continue-on-error: true

  dependency-check:
    name: Dependency Security Scan
    runs-on: ubuntu-latest
    
    steps:
      - name: Checkout code
        uses: actions/checkout@v4
      
      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: maven
      
      - name: Check for known vulnerabilities
        run: mvn -B dependency-check:check
        working-directory: cognition-processor-quarkus
        continue-on-error: true
      
      - name: Upload dependency check report
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: dependency-check-report
          path: cognition-processor-quarkus/target/dependency-check-report.html
          retention-days: 7
```

**Expected output**: After pushing this workflow, you should see the "CI" workflow appear in the Actions tab

### 3. Create integration tests workflow (optional)

**File**: `.github/workflows/integration-tests.yml`

```yaml
name: Integration Tests

on:
  workflow_dispatch: # Manual trigger only
  schedule:
    - cron: '0 2 * * *' # Daily at 2 AM UTC

jobs:
  integration-test:
    name: Integration Tests
    runs-on: ubuntu-latest
    timeout-minutes: 30
    
    steps:
      - name: Checkout code
        uses: actions/checkout@v4
      
      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: maven
      
      - name: Start memory-service
        run: |
          cd cognition-processor-quarkus
          docker-compose -f src/test/resources/docker-compose-test.yml up -d memory-service
          # Wait for service to be healthy
          timeout 60 bash -c 'until docker-compose -f src/test/resources/docker-compose-test.yml ps | grep healthy; do sleep 2; done'
      
      - name: Start Ollama (optional)
        run: |
          cd cognition-processor-quarkus
          docker-compose -f src/test/resources/docker-compose-test.yml up -d ollama
          sleep 10
          docker exec $(docker ps -qf "name=ollama") ollama pull llama3.2
        continue-on-error: true
      
      - name: Run integration tests
        run: mvn -B verify -Pintegration-tests
        working-directory: cognition-processor-quarkus
      
      - name: Upload test results
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: integration-test-results
          path: cognition-processor-quarkus/target/failsafe-reports/
          retention-days: 7
      
      - name: Stop services
        if: always()
        run: |
          cd cognition-processor-quarkus
          docker-compose -f src/test/resources/docker-compose-test.yml down -v
```

### 4. Create PR checks workflow

**File**: `.github/workflows/pr-checks.yml`

```yaml
name: PR Checks

on:
  pull_request:
    types: [opened, synchronize, reopened]

jobs:
  pr-metadata:
    name: PR Metadata Checks
    runs-on: ubuntu-latest
    
    steps:
      - name: Check PR title format
        uses: amannn/action-semantic-pull-request@v5
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        with:
          types: |
            feat
            fix
            docs
            style
            refactor
            perf
            test
            build
            ci
            chore
            revert
            improve
          requireScope: false
      
      - name: Check for TODO file (if new feature)
        if: startsWith(github.event.pull_request.title, 'feat:')
        uses: actions/github-script@v7
        with:
          script: |
            const { data: files } = await github.rest.pulls.listFiles({
              owner: context.repo.owner,
              repo: context.repo.repo,
              pull_number: context.issue.number,
            });
            
            const hasTodoFile = files.some(file => 
              file.filename.startsWith('cognition-processor-quarkus/TODO/')
            );
            
            if (!hasTodoFile) {
              core.warning('New feature PR should include a TODO file documenting the work');
            }

  size-check:
    name: PR Size Check
    runs-on: ubuntu-latest
    
    steps:
      - name: Check PR size
        uses: actions/github-script@v7
        with:
          script: |
            const { data: pr } = await github.rest.pulls.get({
              owner: context.repo.owner,
              repo: context.repo.repo,
              pull_number: context.issue.number,
            });
            
            const additions = pr.additions;
            const deletions = pr.deletions;
            const totalChanges = additions + deletions;
            
            if (totalChanges > 1000) {
              core.warning(`PR is large (${totalChanges} lines changed). Consider splitting into smaller PRs.`);
            }
```

### 5. Add code coverage plugin to pom.xml

**File**: `cognition-processor-quarkus/pom.xml`  
Add to the `<build><plugins>` section:

```xml
<plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <version>0.8.11</version>
    <executions>
        <execution>
            <goals>
                <goal>prepare-agent</goal>
            </goals>
        </execution>
        <execution>
            <id>report</id>
            <phase>test</phase>
            <goals>
                <goal>report</goal>
            </goals>
        </execution>
        <execution>
            <id>jacoco-check</id>
            <goals>
                <goal>check</goal>
            </goals>
            <configuration>
                <rules>
                    <rule>
                        <element>PACKAGE</element>
                        <limits>
                            <limit>
                                <counter>LINE</counter>
                                <value>COVEREDRATIO</value>
                                <minimum>0.50</minimum> <!-- Start with 50%, increase over time -->
                            </limit>
                        </limits>
                    </rule>
                </rules>
            </configuration>
        </execution>
    </executions>
</plugin>
```

### 6. Add code formatting plugin (optional)

**File**: `cognition-processor-quarkus/pom.xml`  
Add to the `<build><plugins>` section:

```xml
<plugin>
    <groupId>net.revelc.code.formatter</groupId>
    <artifactId>formatter-maven-plugin</artifactId>
    <version>2.23.0</version>
    <configuration>
        <configFile>${project.basedir}/eclipse-formatter.xml</configFile>
    </configuration>
    <executions>
        <execution>
            <goals>
                <goal>validate</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

### 7. Create dependabot configuration

**File**: `.github/dependabot.yml`

```yaml
version: 2
updates:
  # Maven dependencies
  - package-ecosystem: "maven"
    directory: "/cognition-processor-quarkus"
    schedule:
      interval: "weekly"
    open-pull-requests-limit: 10
    labels:
      - "dependencies"
      - "java"
    groups:
      quarkus:
        patterns:
          - "io.quarkus*"
      grpc:
        patterns:
          - "io.grpc*"
      
  # GitHub Actions
  - package-ecosystem: "github-actions"
    directory: "/"
    schedule:
      interval: "monthly"
    labels:
      - "dependencies"
      - "github-actions"
```

### 8. Create branch protection rules documentation

**File**: `.github/BRANCH_PROTECTION.md`

```markdown
# Branch Protection Rules

Recommended settings for the `main` branch:

## Required Status Checks
- ✅ Build and Test (build job)
- ✅ Code Quality Checks (code-quality job)
- ✅ PR Metadata Checks (pr-metadata job)

## Pull Request Requirements
- ✅ Require branches to be up to date before merging
- ✅ Require at least 1 approval
- ✅ Dismiss stale pull request approvals when new commits are pushed
- ✅ Require review from Code Owners (if CODEOWNERS file exists)

## Restrictions
- ✅ Include administrators (enforce rules for everyone)
- ✅ Restrict pushes (only allow via PR)
- ✅ Allow force pushes: No
- ✅ Allow deletions: No

## Apply These Rules
1. Go to repository Settings → Branches
2. Add rule for `main` branch
3. Enable the above options
```

### 9. Create GitHub Actions badge for README

**File**: `cognition-processor-quarkus/README.md`  
Add near the top of the file:

```markdown
[![CI](https://github.com/rigazilla/cognitive-memory/actions/workflows/ci.yml/badge.svg)](https://github.com/rigazilla/cognitive-memory/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/rigazilla/cognitive-memory/branch/main/graph/badge.svg)](https://codecov.io/gh/rigazilla/cognitive-memory)
```

### 10. Create CODEOWNERS file (optional)

**File**: `.github/CODEOWNERS`

```
# Default owners for everything
* @rigazilla

# Cognition processor specific
/cognition-processor-quarkus/ @rigazilla

# CI/CD workflows
/.github/workflows/ @rigazilla

# Documentation
*.md @rigazilla
```

## Acceptance Criteria

- [ ] `.github/workflows/` directory created
- [ ] `ci.yml` workflow created and validates builds
- [ ] `pr-checks.yml` workflow validates PR metadata
- [ ] JaCoCo plugin added to pom.xml for coverage
- [ ] Dependabot configuration added
- [ ] GitHub Actions badges added to README
- [ ] Branch protection documentation created
- [ ] First PR using the CI pipeline completes successfully
- [ ] CI runs on every PR and reports status

## Testing the Workflow

1. Create the workflow files
2. Commit and push to a feature branch:
   ```bash
   git add .github/
   git commit -m "ci: add GitHub Actions workflows"
   git push origin feature/add-ci
   ```
3. Open a PR against main branch
4. Verify all checks run in the Actions tab
5. Check PR for status indicators

**Expected**: PR shows status checks:
- ✅ Build and Test
- ✅ Code Quality Checks  
- ✅ PR Metadata Checks
- ✅ Dependency Security Scan

## Optional Enhancements

- [ ] Add native image build workflow (Quarkus native compilation)
- [ ] Add container image build and publish
- [ ] Add release automation (semantic versioning, changelog generation)
- [ ] Add performance regression testing
- [ ] Add license compliance checks
- [ ] Set up Codecov for coverage tracking
- [ ] Add OSSF Scorecard for security best practices

## Notes

- Start with basic CI (build + test) and add more checks incrementally
- Keep CI fast (< 10 minutes) for good developer experience
- Use `continue-on-error: true` for non-critical checks initially
- Consider using GitHub Actions cache to speed up Maven builds
- Integration tests should be separate workflow (slower, manual trigger)

## Related

- Prerequisite: `unit-test-framework-setup.md` must be complete for tests to run
- Related: `integration-test-framework-setup.md` for integration test CI
- See `testing.md` for test coverage requirements

## Troubleshooting

### Common Issues

**Issue**: Workflows don't appear in Actions tab  
**Solution**: Ensure .github/workflows/ is at repository root, not in cognition-processor-quarkus/. Commit and push the files.

**Issue**: CI fails with "BUILD FAILURE" but builds locally  
**Solution**: Check working-directory in workflow matches project structure. Verify all dependencies are in pom.xml.

**Issue**: Tests fail in CI but pass locally  
**Solution**: Check src/test/resources/application.properties for hardcoded localhost references. Use environment variables or test profiles.

**Issue**: Coverage report not uploading to Codecov  
**Solution**: Add CODECOV_TOKEN secret in repository Settings → Secrets. Or use tokenless uploads for public repos.

**Issue**: PR checks fail with "semantic-pull-request" errors  
**Solution**: Ensure PR title starts with valid type (feat:, fix:, docs:, etc.). See conventional commits spec.

**Issue**: Docker commands fail in integration test workflow  
**Solution**: Ensure docker-compose-test.yml file exists at the specified path. Check GitHub Actions has docker-compose installed.

## References

- [GitHub Actions Documentation](https://docs.github.com/en/actions)
- [Quarkus CI/CD Guide](https://quarkus.io/guides/continuous-testing)
- [Maven CI/CD Best Practices](https://maven.apache.org/guides/introduction/introduction-to-the-lifecycle.html)
- [Semantic Pull Requests](https://github.com/amannn/action-semantic-pull-request)
- [JaCoCo Maven Plugin](https://www.jacoco.org/jacoco/trunk/doc/maven.html)
- [Conventional Commits](https://www.conventionalcommits.org/)
