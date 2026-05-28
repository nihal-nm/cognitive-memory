# TODO: Unit Test Framework Setup

**Priority**: HIGH  
**Status**: Not started  
**Blocked by**: None

## Problem

The project currently has no unit test infrastructure in place. While `quarkus-junit5` is declared as a dependency in `pom.xml`, there is:
- No `src/test/java` directory structure
- No test dependencies for mocking (Mockito)
- No example tests to establish patterns
- No test execution configured in build

This blocks contributors from writing tests and ensures code quality through automated testing.

## Context

See `testing.md` for comprehensive test coverage plans. This task focuses specifically on bootstrapping the unit test infrastructure so that contributors can start writing tests.

## Tasks

**Working directory**: `cognition-processor-quarkus/` (all commands relative to this directory)

### 1. Create test directory structure

```bash
mkdir -p src/test/java/io/github/rigazilla/memory/cognition/event
mkdir -p src/test/java/io/github/rigazilla/memory/cognition/extraction
mkdir -p src/test/java/io/github/rigazilla/memory/cognition/verification
mkdir -p src/test/java/io/github/rigazilla/memory/cognition/writer
mkdir -p src/test/resources
```

**Expected**: Directories created with no errors

### 2. Add required test dependencies to pom.xml

**File**: `pom.xml`  
Add after the existing `quarkus-junit5` dependency:

```xml
<!-- Test dependencies -->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-junit5</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-junit5-mockito</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>io.rest-assured</groupId>
    <artifactId>rest-assured</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.mockito</groupId>
    <artifactId>mockito-core</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.assertj</groupId>
    <artifactId>assertj-core</artifactId>
    <version>3.25.3</version>
    <scope>test</scope>
</dependency>
```

### 3. Create a simple smoke test

**File**: `src/test/java/io/github/rigazilla/memory/SmokeTest.java`

```java
package io.github.rigazilla.memory;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class SmokeTest {
    
    @Test
    void testApplicationStarts() {
        // If this test runs, Quarkus context started successfully
        assertTrue(true);
    }
}
```

### 4. Create test application.properties

**File**: `src/test/resources/application.properties`

```properties
# Test configuration
quarkus.log.level=INFO
quarkus.log.category."io.github.rigazilla.memory".level=DEBUG

# Disable actual gRPC connections in tests
memory-service.grpc.host=localhost
memory-service.grpc.port=50051
memory-service.api-key=test-api-key

# Fast timers for tests
cognition.scheduler.debounce-delay=PT1S
cognition.scheduler.max-batch-age=PT5S

# Test worker config
cognition.worker.id=test-worker
cognition.runtime.id=test-runtime
cognition.runtime.version=1.0.0-test
```

### 5. Create example unit test with mocking

**File**: `src/test/java/io/github/rigazilla/memory/cognition/event/CheckpointServiceTest.java`

```java
package io.github.rigazilla.memory.cognition.event;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Example unit test for CheckpointService.
 * Demonstrates basic test structure and Quarkus test integration.
 */
@QuarkusTest
class CheckpointServiceTest {

    @Test
    void testResetCheckpoint_createsCursorWithStartValue() {
        // This is a placeholder test demonstrating test structure.
        // Actual implementation requires mocking gRPC stubs.
        assertTrue(true, "Test infrastructure is working");
    }
}
```

### 6. Verify test execution

Run tests to verify infrastructure:
```bash
mvn test
```

**Expected output**:
```
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```
- Tests compile successfully
- Quarkus test context starts
- SmokeTest passes
- CheckpointServiceTest passes

### 7. Update CONTRIBUTING.md

**File**: `CONTRIBUTING.md` (create if it doesn't exist)  
Add section about running tests:
```markdown
## Running Tests

### Unit Tests
```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=CheckpointServiceTest

# Run with coverage report
mvn test jacoco:report
```

Coverage reports will be in `target/site/jacoco/index.html`.
```

## Acceptance Criteria

- [ ] `src/test/java` directory structure exists
- [ ] Test dependencies added to pom.xml
- [ ] `mvn test` runs successfully
- [ ] At least one smoke test passes
- [ ] Test resources configured (application.properties)
- [ ] Example unit test created as template
- [ ] Documentation updated (CONTRIBUTING.md or README.md)

## Related

- See `testing.md` for comprehensive test plan
- See `integration-test-framework-setup.md` for integration test infrastructure
- PR #3 review identified need for tests on checkpoint reset feature

## Troubleshooting

### Common Issues

**Issue**: `mvn test` fails with "cannot find symbol" errors  
**Solution**: Run `mvn clean compile` first to regenerate protobuf sources

**Issue**: Tests fail with "Unable to start Quarkus test"  
**Solution**: Check that `application.properties` in `src/test/resources` has valid configuration

**Issue**: Mockito errors about "cannot mock final class"  
**Solution**: Ensure using `quarkus-junit5-mockito` dependency, not standalone Mockito

**Issue**: "No tests were executed"  
**Solution**: Ensure test classes end with `Test` suffix and use `@QuarkusTest` annotation

## References

- [Quarkus Testing Guide](https://quarkus.io/guides/getting-started-testing)
- [Quarkus Mocking Guide](https://quarkus.io/guides/getting-started-testing#mock-support)
- [JUnit 5 User Guide](https://junit.org/junit5/docs/current/user-guide/)
