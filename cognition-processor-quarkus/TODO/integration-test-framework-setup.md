# TODO: Integration Test Framework Setup

**Priority**: MEDIUM  
**Status**: Not started  
**Blocked by**: `unit-test-framework-setup.md`

## Problem

While unit tests verify individual components in isolation, integration tests are needed to verify the system works correctly when all components interact together with real external dependencies (memory-service, Ollama).

Currently there is:
- No integration test infrastructure
- No test profiles for integration scenarios
- No Docker Compose setup for test dependencies
- No CI/CD integration test pipeline

## Context

Integration tests should verify:
1. gRPC event streaming from memory-service
2. Event processing pipeline end-to-end
3. LLM extraction with real Ollama models
4. Memory writing back to memory-service
5. Checkpoint recovery and replay

See `testing.md` for detailed integration test scenarios.

## Prerequisites

- Unit test framework must be set up first (see `unit-test-framework-setup.md`)
- Docker and Docker Compose installed for running test dependencies

## Tasks

**Working directory**: `cognition-processor-quarkus/` (all commands relative to this directory)

### 1. Create integration test directory structure

```bash
mkdir -p src/test/java/io/github/rigazilla/memory/integration
mkdir -p src/test/resources/test-profiles
```

**Expected**: Directories created with no errors

### 2. Add integration test dependencies

**File**: `pom.xml`  
Add to the dependencies section:

```xml
<!-- Integration testing -->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-test-h2</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers</artifactId>
    <version>1.19.8</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>1.19.8</version>
    <scope>test</scope>
</dependency>
```

### 3. Configure Maven for integration tests

**File**: `pom.xml`  
Add to the `<build><plugins>` section:

```xml
<plugin>
    <artifactId>maven-failsafe-plugin</artifactId>
    <version>${surefire-plugin.version}</version>
    <executions>
        <execution>
            <goals>
                <goal>integration-test</goal>
                <goal>verify</goal>
            </goals>
        </execution>
    </executions>
    <configuration>
        <systemPropertyVariables>
            <java.util.logging.manager>org.jboss.logmanager.LogManager</java.util.logging.manager>
            <maven.home>${maven.home}</maven.home>
        </systemPropertyVariables>
    </configuration>
</plugin>
```

### 4. Create integration test profile

**File**: `src/test/java/io/github/rigazilla/memory/integration/IntegrationTestProfile.java`

```java
package io.github.rigazilla.memory.integration;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

public class IntegrationTestProfile implements QuarkusTestProfile {
    
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
            // Fast timers for integration tests
            "cognition.scheduler.debounce-delay", "PT5S",
            "cognition.scheduler.max-batch-age", "PT10S",
            
            // Test configuration
            "cognition.worker.id", "integration-test-worker",
            "cognition.runtime.id", "integration-test-runtime",
            "cognition.runtime.version", "1.0.0-IT",
            
            // Memory service will be configured via Testcontainers
            "memory-service.grpc.host", "localhost",
            "memory-service.api-key", "test-api-key"
        );
    }
    
    @Override
    public String getConfigProfile() {
        return "integration-test";
    }
}
```

### 5. Create Docker Compose for test dependencies

**File**: `src/test/resources/docker-compose-test.yml`

```yaml
version: '3.8'

services:
  memory-service:
    image: chirino/memory-service:latest
    ports:
      - "8082:8082"
    environment:
      - API_KEY=test-api-key
    healthcheck:
      test: ["CMD", "grpc_health_probe", "-addr=:8082"]
      interval: 5s
      timeout: 3s
      retries: 5

  ollama:
    image: ollama/ollama:latest
    ports:
      - "11434:11434"
    volumes:
      - ollama-data:/root/.ollama
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:11434/api/tags"]
      interval: 10s
      timeout: 5s
      retries: 3

volumes:
  ollama-data:
```

### 6. Create example integration test

**File**: `src/test/java/io/github/rigazilla/memory/integration/EventProcessingIT.java`

```java
package io.github.rigazilla.memory.integration;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;

/**
 * Integration test for end-to-end event processing.
 * 
 * @Disabled by default - requires memory-service and Ollama running.
 * Run with: mvn verify -Pintegration-tests
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@Disabled("Requires external dependencies - run explicitly with integration-tests profile")
class EventProcessingIT {

    @Test
    void testBasicEventProcessing() {
        // Placeholder for integration test
        // TODO: Implement once test infrastructure is complete
        // 1. Subscribe to event stream
        // 2. Create test conversation in memory-service
        // 3. Add entries
        // 4. Wait for processing
        // 5. Verify memories created
    }
}
```

### 7. Create Maven profile for integration tests

**File**: `pom.xml`  
Add to the `<profiles>` section (create if it doesn't exist):

```xml
<profile>
    <id>integration-tests</id>
    <properties>
        <skipITs>false</skipITs>
    </properties>
    <build>
        <plugins>
            <plugin>
                <groupId>io.fabric8</groupId>
                <artifactId>docker-maven-plugin</artifactId>
                <version>0.44.0</version>
                <executions>
                    <execution>
                        <id>start-containers</id>
                        <phase>pre-integration-test</phase>
                        <goals>
                            <goal>start</goal>
                        </goals>
                    </execution>
                    <execution>
                        <id>stop-containers</id>
                        <phase>post-integration-test</phase>
                        <goals>
                            <goal>stop</goal>
                        </goals>
                    </execution>
                </executions>
                <configuration>
                    <dockerComposeFile>${project.basedir}/src/test/resources/docker-compose-test.yml</dockerComposeFile>
                </configuration>
            </plugin>
        </plugins>
    </build>
</profile>
```

### 8. Create integration test utilities

**File**: `src/test/java/io/github/rigazilla/memory/integration/TestDataHelper.java`

```java
package io.github.rigazilla.memory.integration;

import java.util.UUID;

/**
 * Helper utilities for integration tests.
 */
public class TestDataHelper {
    
    public static String createTestConversationId() {
        return "test-conv-" + UUID.randomUUID();
    }
    
    public static String createTestUserId() {
        return "test-user-" + UUID.randomUUID();
    }
    
    // Add methods to interact with memory-service API
    // for creating test conversations, entries, etc.
}
```

### 9. Document integration test setup

**File**: `src/test/resources/INTEGRATION_TESTS.md`

```markdown
# Integration Tests

## Prerequisites

1. Docker and Docker Compose installed
2. Memory service image available
3. Ollama image available (optional - for LLM tests)

## Running Integration Tests

### Quick run (without LLM)
```bash
# Start dependencies
docker-compose -f src/test/resources/docker-compose-test.yml up -d

# Run integration tests
mvn verify -Pintegration-tests

# Cleanup
docker-compose -f src/test/resources/docker-compose-test.yml down
```

### With automated setup
```bash
# Maven profile handles Docker lifecycle
mvn verify -Pintegration-tests
```

### Run specific integration test
```bash
mvn verify -Pintegration-tests -Dit.test=EventProcessingIT
```

## Test Categories

- `*IT.java` - Integration tests requiring external services
- Tagged `@Disabled` by default - must opt-in via profile
```

### 10. Add CI/CD configuration example

**File**: `.github/workflows/integration-tests.yml.example`

```yaml
name: Integration Tests

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

jobs:
  integration-test:
    runs-on: ubuntu-latest
    
    steps:
      - uses: actions/checkout@v4
      
      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: maven
      
      - name: Start memory-service
        run: |
          docker-compose -f src/test/resources/docker-compose-test.yml up -d memory-service
          # Wait for service to be healthy
          timeout 60 bash -c 'until docker-compose -f src/test/resources/docker-compose-test.yml ps | grep healthy; do sleep 2; done'
      
      - name: Run integration tests
        run: mvn verify -Pintegration-tests
      
      - name: Stop services
        if: always()
        run: docker-compose -f src/test/resources/docker-compose-test.yml down
```

## Acceptance Criteria

- [ ] Integration test directory structure created
- [ ] Maven failsafe plugin configured
- [ ] Docker Compose file for test dependencies created
- [ ] Integration test profile defined
- [ ] Maven profile for integration tests added
- [ ] At least one example integration test created (can be @Disabled)
- [ ] Test utilities created for common operations
- [ ] Documentation for running integration tests
- [ ] CI/CD workflow example provided
- [ ] `mvn verify -Pintegration-tests` executes successfully

**Expected output** when running `mvn verify -Pintegration-tests`:
```
[INFO] BUILD SUCCESS
[INFO] Tests run: 0, Failures: 0, Errors: 0, Skipped: 1
```
(Skipped = 1 because example test is @Disabled)

## Running Order

1. First complete `unit-test-framework-setup.md`
2. Set up this integration test framework
3. Start implementing actual integration tests from `testing.md`

## Notes

- Integration tests should be @Disabled by default and only run via explicit profile
- Keep integration tests fast (< 5 min total) by using short timer configs
- Consider using Testcontainers for more portable test setup (alternative to Docker Compose)
- Integration tests are not a replacement for unit tests - both are needed

## Troubleshooting

### Common Issues

**Issue**: Docker Compose fails to start services  
**Solution**: Ensure Docker daemon is running with `docker ps`. Check if ports 8082 and 11434 are available.

**Issue**: Integration tests timeout waiting for services  
**Solution**: Increase healthcheck timeout in docker-compose-test.yml or manually verify services are healthy with `docker-compose ps`

**Issue**: `mvn verify -Pintegration-tests` doesn't find the profile  
**Solution**: Ensure the `<profiles>` section is in pom.xml and properly closed with `</profiles>`

**Issue**: Testcontainers fails with permission errors  
**Solution**: Add your user to the docker group: `sudo usermod -aG docker $USER` (requires logout/login)

**Issue**: Memory service image not found  
**Solution**: Build memory-service image first or use a published image tag in docker-compose-test.yml

## Related

- See `testing.md` for specific integration test scenarios to implement
- See `unit-test-framework-setup.md` for prerequisite work

## References

- [Quarkus Testing Guide](https://quarkus.io/guides/getting-started-testing)
- [Testcontainers Documentation](https://www.testcontainers.org/)
- [Docker Compose Documentation](https://docs.docker.com/compose/)
- [Maven Failsafe Plugin](https://maven.apache.org/surefire/maven-failsafe-plugin/)

## Future Enhancements

- [ ] Add Testcontainers support as alternative to Docker Compose
- [ ] Add test data fixtures for common scenarios
- [ ] Add performance/load testing profile
- [ ] Add contract testing for gRPC APIs
