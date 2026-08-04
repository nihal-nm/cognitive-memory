# CI/CD Coverage Enforcement - 35% Minimum

## Goal

Enforce a minimum code coverage threshold of 35% in the GitHub Actions CI/CD pipeline. Build must fail if coverage drops below this threshold.

## Current State

The workflow at `.github/workflows/quarkus-build-and-test.yml` already:
- Generates JaCoCo coverage reports
- Uses `Madrapps/jacoco-report@v1.7.2` action
- Has `min-coverage-overall: 80` configured
- BUT has `continue-on-error: true` which prevents build failure

## Required Changes

### Option 1: Fix Existing Coverage Check (Recommended)

Modify `.github/workflows/quarkus-build-and-test.yml`:

```yaml
- name: Coverage report
  if: runner.os == 'Linux' && always() && hashFiles('cognition-processor-quarkus/target/site/jacoco/jacoco.xml') != ''
  uses: Madrapps/jacoco-report@v1.7.2
  with:
    paths: cognition-processor-quarkus/target/site/jacoco/jacoco.xml
    token: ${{ secrets.GITHUB_TOKEN }}
    comment-type: summary
    min-coverage-overall: 35  # Changed from 80 to 35
    min-coverage-changed-files: 35  # Changed from 80 to 35
    title: Code Coverage
    # REMOVED: continue-on-error: true  # This was preventing build failure
```

**Changes:**
1. Set `min-coverage-overall: 35` (down from 80)
2. Set `min-coverage-changed-files: 35` (down from 80)
3. Remove `continue-on-error: true` line

### Option 2: Add Explicit Coverage Check Step

Add a new step after coverage report generation:

```yaml
- name: Enforce minimum coverage
  if: runner.os == 'Linux'
  run: |
    COVERAGE=$(grep -oP 'line-rate="\K[0-9.]+' cognition-processor-quarkus/target/site/jacoco/jacoco.xml | head -1)
    COVERAGE_PCT=$(echo "$COVERAGE * 100" | bc)
    echo "Current coverage: ${COVERAGE_PCT}%"
    if (( $(echo "$COVERAGE_PCT < 35" | bc -l) )); then
      echo "::error::Coverage ${COVERAGE_PCT}% is below minimum threshold of 35%"
      exit 1
    fi
```

## Recommendation

**Use Option 1** - it's simpler and leverages the existing action that already provides nice PR comments with coverage details.

## Implementation Steps

1. Edit `.github/workflows/quarkus-build-and-test.yml`
2. Locate the "Coverage report" step (around line 40)
3. Change `min-coverage-overall: 80` to `min-coverage-overall: 35`
4. Change `min-coverage-changed-files: 80` to `min-coverage-changed-files: 35`
5. Remove the `continue-on-error: true` line
6. Commit and push changes
7. Verify in next PR that coverage check fails if below 35%

## Testing

To test the enforcement:
1. Temporarily remove some tests to drop coverage below 35%
2. Push to a branch and create PR
3. Verify the build fails with coverage error
4. Revert the test removal
5. Verify the build passes

## Current Coverage

After test optimization: **37.0%** (safely above 35% threshold)
- Line coverage: 37%
- Branch coverage: 37%
- Instruction coverage: 37%
