# 021: GitHub Actions CI/CD Workflows

**Status**: Complete  
**Date**: 2026-06-24

## Overview

Implemented GitHub Actions workflows for Continuous Integration (CI) and Continuous Deployment (CD) to automate building, testing, and publishing Docker images to Quay.io.

## Problem

The project had no automated CI/CD pipeline, requiring manual builds, tests, and Docker image publishing. This created several issues:
- No automated validation of code changes
- Manual Docker image builds prone to inconsistency
- No automated testing before deployment
- Lack of visibility into build/test status

## Solution

Created two GitHub Actions workflows that run on every push to the `main` branch:

### 1. CI Workflow (`.github/workflows/ci.yml`)

**Purpose**: Validate code quality through automated builds and tests

**Steps**:
1. Checkout code
2. Set up JDK 21 with Maven caching
3. Build application with `./mvnw clean package -DskipTests`
4. Run unit tests with `./mvnw test`

**Benefits**:
- Fast feedback on code quality
- Ensures code compiles successfully
- Validates all unit tests pass
- Maven dependency caching speeds up builds

### 2. CD Workflow (`.github/workflows/cd.yml`)

**Purpose**: Build, test, and publish Docker images to Quay.io

**Steps**:
1. Checkout code
2. Set up JDK 21 with Maven caching
3. Build application with Maven
4. Set up Docker Buildx for advanced build features
5. Log in to Quay.io using secrets
6. Extract Docker metadata (tags, labels)
7. Build Docker image with caching
8. Start container for health check validation
9. Wait up to 60 seconds for `/q/health/ready` endpoint
10. Stop test container
11. Push image to Quay.io only if health check passes

**Docker Tags Created**:
- `main` - Latest build from main branch
- `main-<git-sha>` - Specific commit SHA for traceability
- `cd-latest` - Alias for latest main branch build (not marked as default)

**Image Name**: `quay.io/rigazilla/cognitive-memory`

**Benefits**:
- Automated Docker image publishing
- Health check ensures only working images are published
- Multiple tags for flexibility (latest, branch, SHA)
- GitHub Actions cache speeds up Docker builds
- Fail-fast if container doesn't start properly

## Configuration Required

### Environment Variables

The workflow uses the following environment variable (configured in `.github/workflows/cd.yml`):

1. **QUAY_REPO**: `rigazilla/cognitive-memory` - Specifies the Quay.io repository path

### GitHub Secrets

Two secrets must be configured in repository settings:

1. **QUAY_USERNAME**: Quay.io username (used for authentication only)
2. **QUAY_TOKEN**: Quay.io robot account token or personal access token

### Quay.io Robot Account Setup

Recommended approach for security:
1. Create robot account in Quay.io
2. Grant write permissions to repository
3. Use robot token as `QUAY_TOKEN` secret

## Files Created

```
.github/
├── workflows/
│   ├── ci.yml          # CI workflow
│   └── cd.yml          # CD workflow
└── SETUP.md            # Setup documentation
```

## Design Decisions

### Why Separate CI and CD Workflows?

- **Modularity**: Each workflow has a single responsibility
- **Flexibility**: Can trigger independently if needed in future
- **Clarity**: Easier to understand and maintain

### Why Quay.io?

- User preference over Docker Hub or GitHub Container Registry
- Good free tier for open source projects
- Robot accounts for secure CI/CD integration

### Why Health Check Before Push?

- Prevents publishing broken images
- Validates container starts successfully
- Tests critical `/q/health/ready` endpoint
- Provides early failure detection

### Why Run on Main Only?

- Keeps workflow simple initially
- Reduces CI/CD costs
- PR checks can be added later as separate workflow

### Why Maven Wrapper (`./mvnw`)?

- Ensures consistent Maven version across environments
- No need to install Maven on CI runners
- Project-specific Maven configuration

## Testing

The workflows can be tested by:

1. Configuring required secrets in GitHub
2. Pushing changes to main branch
3. Monitoring workflow execution in Actions tab
4. Verifying Docker image appears in Quay.io

## Future Enhancements

Potential improvements documented in TODO:
- Add PR validation workflow
- Add code coverage reporting
- Add dependency security scanning
- Add native image builds
- Add release automation
- Add integration test workflow

## Related Documentation

- `.github/SETUP.md` - Detailed setup instructions
- `cognition-processor-quarkus/TODO/github-ci-workflow-setup.md` - Original TODO with comprehensive implementation plan
- `cognition-processor-quarkus/docker-compose.yml` - Local Docker Compose setup
- `cognition-processor-quarkus/src/main/docker/Dockerfile.jvm` - Dockerfile used by CD workflow

## Impact

- **Developer Experience**: Immediate feedback on code quality
- **Deployment**: Automated, consistent Docker image publishing
- **Quality**: Health checks prevent broken images from being published
- **Traceability**: SHA-based tags link images to specific commits
- **Efficiency**: Caching reduces build times significantly
