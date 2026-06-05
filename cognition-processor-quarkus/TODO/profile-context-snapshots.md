# TODO: Profile Context Snapshots

**Priority**: MEDIUM
**Status**: Future enhancement
**Affected Component**: Memory extraction, consolidation, memory writing

## Problem

The current cognition pipeline extracts durable atomic memories, but agents often need a compact user profile at the start of a conversation. A raw search over individual memories can return useful facts, but it does not naturally produce a coherent "who is this user, what are they working on, what should the assistant remember right now?" context block.

A nightly consolidation job should compose selected extracted memories into a single derived profile context snapshot for each user. The snapshot can be retrieved as initial context before agent interaction while preserving the underlying atomic memories as the source of truth.

## Goals

1. **Provide fast initial context**: Give agents a compact profile summary without running many memory searches.
2. **Preserve provenance**: Link every snapshot section back to the source memories and source conversation evidence.
3. **Separate durable facts from current state**: Avoid mixing stable identity facts with short-lived tasks and blockers.
4. **Support freshness and staleness**: Mark when a section was last updated and when it may need recomputation.
5. **Keep governance intact**: Store snapshots under the user's governed namespace and inherit source-memory access constraints.
6. **Avoid example contamination**: Prompts and docs must use synthetic placeholder examples only.

## Non-Goals

1. **Replacing atomic memories**: The snapshot is a derived memory product, not the primary memory store.
2. **Replacing semantic search**: Agents should still search memories for detailed or task-specific context.
3. **Making a complete biography**: The snapshot should be concise and operational, not exhaustive.
4. **Guessing private details**: Sensitive or unsupported information must not be inferred.

## Current Behavior

- Extractor returns five coarse memory types:
  - `fact`
  - `preference`
  - `procedure`
  - `problem_solution`
  - `decision`
- Memories are written independently under:

```text
["user", <userId>, "cognition.v1", <memory_type>]
```

- There is no derived user profile snapshot.
- There is no structured distinction between stable profile data, active work state, open loops, constraints, or standing assistant instructions.

## Proposed Behavior

Add a nightly consolidation job that reads recent and durable cognition memories for a user, resolves duplicates/conflicts, and writes one derived profile context memory.

Suggested namespace:

```text
["user", <userId>, "cognition.v1", "profile_context"]
```

Suggested key:

```text
latest
```

There should be only one current profile context entry per user. Each consolidation updates `profile_context/latest`; historical versions should be retained by memory-service's normal memory version/history mechanism rather than by creating timestamped snapshot keys.

The value should contain a composed human-readable report in `content`. The structured `sections` field should be kept minimal and used as evidence/reference metadata for the report rather than as the primary text to inject.

## Profile Sections

### Core Sections

1. **Profile Snapshot**
   - Compact identity and role summary.
   - Example: "Maya is a graduate student in Seattle who works part-time as a frontend developer."

2. **Work / School Context**
   - Current job, school, business, client, or research context.
   - Example: "Maya contributes to Northstar CRM and is taking a distributed systems course."

3. **Active Goals**
   - Goals the user is currently trying to accomplish.
   - Example: "Prepare a demo build for the Northstar CRM pilot."

4. **Top Of Mind**
   - Immediate focus areas likely to matter in the next interaction.
   - Example: "Debugging a production-only OAuth callback failure."

5. **Open Loops**
   - Waiting-on items, unresolved blockers, pending decisions, or follow-ups.
   - Example: "Waiting for Sam to confirm the staging redirect URI."

6. **Projects**
   - Active, paused, completed, and abandoned projects with status and next step.
   - Example: "RecipeLens is paused after prototype validation."

7. **People And Organizations**
   - Key collaborators, clients, companies, schools, teams, and their relationship to the user.
   - Example: "Sam Lee is the backend lead for Northstar CRM."

8. **Preferences And Working Style**
   - Communication style, tools, design taste, coding preferences, and collaboration preferences.
   - Example: "Prefers short implementation plans followed by concrete code changes."

9. **Constraints And Risk Flags**
   - Budget, schedule, legal, compliance, privacy, academic, or operational constraints.
   - Example: "Avoid publishing customer testimonials until legal review is complete."

10. **Standing Instructions**
    - Durable assistant instructions and boundaries that should be honored across sessions.
    - Example: "Ask before changing deployment configuration."

11. **Recent History**
    - Recent milestones, decisions, completed work, and important changes.
    - Example: "Completed the dashboard redesign and switched analytics providers last week."

12. **Long-Term Background**
    - Older durable context that remains useful but is not top-of-mind.
    - Example: "Previously built a note-taking app and discontinued it after user testing."

13. **Context Freshness**
    - Snapshot generation time, stale sections, low-confidence areas, and unresolved conflicts.
    - Example: "Project status was last supported by evidence from 2026-05-30."

### Optional Sections

1. **Tooling And Environment**
   - Devices, operating systems, editors, frameworks, hosting platforms, and common services.

2. **Reusable Assets**
   - Brand colors, standard commands, templates, copy blocks, API patterns, and project-specific constants.

3. **Known Pain Points**
   - Recurring bugs, repeated workflow friction, or areas where the user often needs extra support.

4. **Operating Rhythm**
   - How the user organizes work, delegates tasks, tracks plans, or collaborates with agents.

5. **Privacy Preferences**
   - User-specific preferences about what should not be repeated, summarized, or reused casually.

## Required Extraction Data

The current five memory types are a good base, but the profile snapshot needs richer metadata and subtyping.

### Suggested Memory Candidate Fields

```json
{
  "type": "fact",
  "subtype": "project_status",
  "subject": "Northstar CRM",
  "predicate": "has_current_blocker",
  "object": "OAuth callback fails in production",
  "content": "Northstar CRM is blocked by a production-only OAuth callback failure.",
  "status": "active",
  "importance": 0.9,
  "confidence": 0.86,
  "observed_at": "2026-06-02T21:30:00Z",
  "effective_at": "2026-06-02T21:30:00Z",
  "expires_at": null,
  "entities": ["Northstar CRM"],
  "topics": ["oauth", "deployment", "debugging"],
  "profile_section_hints": ["top_of_mind", "open_loops", "projects"],
  "sensitivity": "normal",
  "citations": ["User said the production OAuth callback is still failing."]
}
```

### Suggested Subtypes

#### Profile And Identity

- `identity_fact`
- `location`
- `education`
- `employment`
- `role`
- `skill`
- `interest`

#### Relationships

- `person_relationship`
- `organization_relationship`
- `collaborator_role`
- `customer_or_client`

#### Projects

- `active_project`
- `paused_project`
- `completed_project`
- `abandoned_project`
- `project_stack`
- `project_status`
- `project_next_step`
- `project_blocker`
- `project_milestone`

#### Work State

- `active_goal`
- `open_loop`
- `deadline`
- `waiting_on`
- `decision_pending`
- `recent_change`

#### Preferences And Style

- `communication_preference`
- `tool_preference`
- `design_preference`
- `coding_preference`
- `learning_preference`
- `workflow_preference`

#### Governance And Safety

- `standing_instruction`
- `assistant_boundary`
- `privacy_preference`
- `risk_flag`
- `compliance_constraint`
- `academic_constraint`

#### Knowledge Assets

- `reusable_asset`
- `brand_spec`
- `technical_spec`
- `standard_command`
- `procedure`
- `known_issue`
- `problem_solution`

## Profile Context Entry Value Shape

The `profile_context/latest` value should be structured enough for machines and readable enough for prompt injection.

```json
{
  "kind": "profile_context_snapshot",
  "version": "profile_context.v1",
  "user_id": "user_123",
  "generated_at": "2026-06-03T03:00:00Z",
  "source_window": {
    "from": "2026-05-04T00:00:00Z",
    "to": "2026-06-03T03:00:00Z"
  },
  "content": "Active Goals\nMaya is preparing a demo build for the Northstar CRM pilot. She wants the demo to emphasize faster follow-up workflows for account managers.\n\nOpen Loops\nMaya is waiting for Sam to confirm the staging OAuth redirect URI. The production-only OAuth callback failure still needs local reproduction, and the analytics provider switch needs one final dashboard smoke test.\n\nPreferences And Working Style\nMaya prefers short implementation plans followed by concrete code changes. She likes deployment changes to be called out separately before they are made.",
  "sections": {
    "active_goals": {
      "confidence": 0.85,
      "source_memory_keys": ["memory-key-1", "memory-key-2", "memory-key-3"]
    },
    "open_loops": {
      "confidence": 0.84,
      "source_memory_keys": ["memory-key-4", "memory-key-5", "memory-key-6", "memory-key-7"]
    },
    "preferences_and_working_style": {
      "confidence": 0.83,
      "source_memory_keys": ["memory-key-8", "memory-key-10"]
    }
  },
  "conflicts": [],
  "omitted": [
    {
      "reason": "low_confidence",
      "source_memory_key": "memory-key-9"
    }
  ]
}
```

## Consolidation Strategy

1. **Load candidate memories**
   - Query the user's cognition memory namespace.
   - Include stable long-term memories plus recent short-term memories.

2. **Group by subject and subtype**
   - Group project facts with their project.
   - Group person facts by person/entity.
   - Group standing instructions separately from ordinary preferences.

3. **Deduplicate**
   - Merge semantically equivalent memories.
   - Keep all provenance references.
   - Prefer higher-confidence and more recent statements where appropriate.

4. **Resolve conflicts**
   - Use recency for volatile fields such as active blocker or current status.
   - Use confidence and repeated support for stable facts.
   - Preserve unresolved conflicts in the snapshot metadata instead of hiding them.

5. **Rank by usefulness**
   - Prioritize active goals, open loops, standing instructions, constraints, and current projects.
   - Keep low-importance historical details out of the main `content` report.

6. **Render report content**
   - Generate concise `content` text from structured grouped memories.
   - Keep the report brief enough for prompt budget use.
   - Store section-level confidence and source memory references in `sections`.

7. **Write snapshot**
   - Write or update `profile_context/latest`.
   - Do not create additional timestamped profile snapshot entries.
   - Rely on memory-service history/version retention for previous snapshot versions.
   - Use revision-aware updates when available to avoid lost updates.

## Freshness Rules

Suggested freshness labels:

- `current`: Supported by recent evidence or explicit current-state language.
- `stable`: Durable fact unlikely to change often.
- `stale`: Not recently confirmed and potentially time-sensitive.
- `conflicted`: Multiple supported memories disagree.
- `low_confidence`: Included only if useful and clearly marked.

Suggested volatility defaults:

| Subtype | Default Freshness Behavior |
| --- | --- |
| `identity_fact` | Stable unless contradicted |
| `location` | Stable but should become stale after long inactivity |
| `active_goal` | Current, expires quickly |
| `open_loop` | Current until resolved or stale |
| `project_status` | Current, expires moderately quickly |
| `standing_instruction` | Stable until explicitly changed |
| `tool_preference` | Stable but can be superseded |
| `deadline` | Expires after date passes |

## Prompting Guidelines

Extraction and consolidation prompts should avoid real-looking example data that could contaminate outputs. Use placeholders or synthetic data clearly marked as examples.

Good:

```text
Example only: User works on [PROJECT_NAME] using [FRAMEWORK].
```

Also acceptable:

```text
Synthetic example: Jordan is building Atlas Notes with SvelteKit.
```

Avoid:

```text
User works at Acme Corp.
```

The prompt must explicitly require:

- Extract only information supported by evidence.
- Distinguish current state from historical background.
- Mark standing instructions separately from ordinary preferences.
- Do not infer sensitive personal details.
- Include citations for every atomic memory.

## Access Control And Privacy

- Profile snapshots must be written on behalf of the user and stored in the user's namespace.
- Snapshot provenance must reference source memories and source evidence.
- Sensitive memories should either be omitted or summarized according to memory-service governance rules.
- The consolidator should not broaden visibility beyond the most restrictive relevant source.
- The snapshot should include privacy-sensitive sections only when they are necessary for assistant behavior.

## API Usage Pattern

An agent can use profile context as a fast initial memory product:

1. Fetch `profile_context/latest`.
2. Inject the `content` report into the agent context.
3. Run targeted memory search only when the task needs deeper detail.
4. Respect `conflicts`, `stale`, and `low_confidence` flags.

## Implementation Plan

### Phase 1: Schema And Prompt Updates

1. Extend memory candidate representation with optional metadata:
   - `subtype`
   - `subject`
   - `status`
   - `importance`
   - `observed_at`
   - `effective_at`
   - `expires_at`
   - `entities`
   - `topics`
   - `profile_section_hints`
   - `sensitivity`
2. Update extraction prompt to request these fields.
3. Update verifier prompt to verify metadata claims, not only content.
4. Keep backwards compatibility with existing simple memory candidates.

### Phase 2: Snapshot Consolidator

1. Add `ProfileContextConsolidator`.
2. Query cognition memories for one user.
3. Group, deduplicate, rank, and render section data.
4. Write `profile_context/latest`.
5. Include metrics:
   - source memories considered
   - source memories used
   - conflicts found
   - stale sections
   - generated token/character length

### Phase 3: Scheduling

1. Add configurable nightly schedule.
2. Allow manual rebuild for a specific user.
3. Add checkpointing so failed users can be retried.
4. Avoid rebuilding unchanged profiles when source memories have not changed.

### Phase 4: Retrieval Integration

1. Document how agents should retrieve profile context.
2. Add a small example client query.
3. Consider a dedicated API endpoint if direct memory lookup is too awkward.

## Testing

### Unit Tests

- Section ranking with mixed memory types.
- Freshness classification.
- Conflict detection.
- Deduplication of equivalent facts.
- Omission of low-confidence unsupported details.

### Integration Tests

- Create synthetic conversations.
- Extract atomic memories.
- Run profile consolidation.
- Verify `profile_context/latest` is written with provenance.
- Verify snapshot updates when a project status changes.
- Verify standing instructions remain separate from preferences.

### Prompt Tests

- No extraction from examples.
- No profile snapshot content unsupported by source memories.
- Correct separation of active goals, historical background, and standing instructions.

## Open Questions

1. Should snapshots be generated only nightly, or also after important memory writes?
2. What maximum token or character budget should the rendered profile use?
3. Should sensitive sections be opt-in per user or governed entirely by memory-service ACL metadata?
4. Should consolidation use an LLM, deterministic rules, embeddings, or a hybrid approach?

## Decisions

1. **Single current entry per user**: Store only `profile_context/latest` for each user.
2. **No timestamped snapshot keys**: Do not create separate profile context memories for each nightly run.
3. **History via memory-service**: Previous versions are available through memory-service's retained version/history record.

## Relationship To Existing TODOs

- Builds on `consolidation.md` by applying deduplication and conflict handling to a specific derived memory product.
- Related to `prompt-example-contamination.md` because profile prompts must avoid leaking example content into extracted memories or snapshots.
- Benefits from `testing.md` because snapshot quality needs regression coverage.



---

## Reviewer Feedback: Phase 0 Prototype Recommendation

**Added during PR #5 review** - This minimal prototype phase is recommended to validate the core concept before committing to the full 4-phase implementation.

### Scope

**3 Core Sections:**
- Profile Snapshot (identity/role)
- Active Goals (current work)
- Preferences (working style)

**Implementation:**
- Manual trigger: `POST /api/consolidate/{userId}`
- LLM-based consolidation of existing memories
- Simple schema: confidence + source memory keys
- Write to: `["user", userId, "cognition.v1", "profile_context"]` key `"latest"`
- No scheduling, no extended metadata, no conflict resolution

### Retrieval Test

```bash
# Trigger consolidation
curl -X POST http://localhost:8090/api/consolidate/alice

# Fetch snapshot
curl -X POST http://localhost:8082/v1/memories/search \
  -H "Authorization: Bearer cognition-processor-key-123" \
  -H "Content-Type: application/json" \
  -d '{
    "namespace_prefix": ["user", "alice", "cognition.v1", "profile_context"],
    "key": "latest"
  }'
```

Verify:
- Content is coherent markdown with 3 sections
- Provenance links trace back to source conversations

### What to Validate

1. **Usefulness** - Is generated profile valuable for agent context?
2. **LLM viability** - Quality, cost, latency acceptable?
3. **Schema sufficiency** - Does simple structure work?
4. **Missing features** - What's actually needed vs. nice-to-have?

**Success = Concept proven, informed decisions for Phase 1**
