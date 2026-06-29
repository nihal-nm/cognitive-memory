# GitHub Actions Setup

This document describes the CI/CD workflows and required configuration.

## Workflows

### Reusable Workflow (`.github/workflows/docker-build-test.yml`)

Shared logic called by both CI and CD. Not triggered directly.

- **Inputs**:
  - `push` (boolean) — whether to push the image to Quay.io after a successful health check
  - `quay_repo` (string) — Quay.io repository path (default: `rigazilla/cognitive-memory`)
- **Jobs**:
  - Build application with Maven
  - Build Docker image
  - Test container health check (`/q/health/live`)
  - Optionally push image to Quay.io

### CI Workflow (`.github/workflows/ci.yml`)
- **Trigger**: Push to `main` branch, pull requests targeting `main`
- **Jobs**:
  - Build application with Maven
  - Run unit tests
  - Call reusable workflow with `push: false` (build + health check only, no registry push)

### CD Workflow (`.github/workflows/cd.yml`)
- **Trigger**: Push to `main` branch
- **Jobs**:
  - Call reusable workflow with `push: true` (build + health check + push to Quay.io)

## Required Configuration

### Environment Variables

The CD workflow uses the following environment variable (configured in the workflow file):

#### QUAY_REPO
- **Location**: `.github/workflows/cd.yml` (env section)
- **Value**: `rigazilla/cognitive-memory`
- **Purpose**: Specifies the Quay.io repository path for the Docker image

### Required Secrets

To enable the CD workflow, you need to configure the following secrets in your GitHub repository:

#### Setting up Quay.io Secrets

1. Go to your GitHub repository
2. Navigate to **Settings** → **Secrets and variables** → **Actions**
3. Click **New repository secret**
4. Add the following secrets:

**QUAY_USERNAME**
- **Name**: `QUAY_USERNAME`
- **Value**: Your Quay.io username (used for authentication only)

**QUAY_TOKEN**
- **Name**: `QUAY_TOKEN`
- **Value**: Your Quay.io robot account token or personal access token

### Creating a Quay.io Robot Account (Recommended)

1. Log in to [Quay.io](https://quay.io)
2. Go to your organization or user settings
3. Navigate to **Robot Accounts**
4. Click **Create Robot Account**
5. Give it a name (e.g., `github_actions`)
6. Grant it **Write** permissions to the repository
7. Copy the generated token and use it as `QUAY_TOKEN`

## Docker Image Tags

The CD workflow creates the following tags:
- `main` - Latest build from main branch
- `main-<git-sha>` - Specific commit SHA
- `cd-latest` - Alias for the latest main branch build

## Image Location

After successful CD run, the image will be available at:
```
quay.io/rigazilla/cognitive-memory:latest
```

The repository path is configured via the `QUAY_REPO` environment variable in the workflow file.

## Health Check

The CD workflow verifies the container is healthy by:
1. Starting the container
2. Waiting up to 60 seconds
3. Checking the `/q/health/ready` endpoint
4. Only pushing to registry if health check passes

## Local Testing

To test the Docker image locally:

```bash
cd cognition-processor-quarkus
./mvnw clean package -DskipTests
docker build -f src/main/docker/Dockerfile.jvm -t cognition-processor-quarkus:test .
docker run -p 8090:8090 \
  -e MEMORY_SERVICE_GRPC_HOST=localhost \
  -e MEMORY_SERVICE_GRPC_PORT=8082 \
  cognition-processor-quarkus:test
```

Check health:
```bash
curl http://localhost:8090/q/health/ready
```
