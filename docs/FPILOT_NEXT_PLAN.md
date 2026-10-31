# FPilot Next implementation plan

## Status

This document is the execution contract for the approved FPilot Next direction. It does not claim that the implementation is complete. Work is split into reviewable pull requests so that storage, request compilation, history migration, and tool execution can be tested independently.

## Product invariants

1. Workspace-first: the user-selected Android folder is the primary project context.
2. Glassbox: every model input assembled by the app must be inspectable and, where safe, editable before submission.
3. Provenance: each effective prompt section and tool definition records its source file and transformation.
4. User authority: model output is a proposal; editing a transcript must never replay tool calls.
5. Least privilege: workspace access is scoped to the selected SAF tree; writes are opt-in and destructive operations require explicit confirmation.
6. Provider portability: provider-specific behavior stays behind adapters; the core request model is provider-neutral.
7. Secrets stay outside workspace documents and request snapshots.

## Baseline discovered on 2026-11-01

The default `main` branch is still the MiniiChat baseline (Compose, Kotlin, Ktor, DataStore). The experimental `agent-fs` branch is the correct starting point for FPilot Next: its README describes an Android SAF workspace, a bounded tool loop, opt-in file modifications, and local conversation/settings storage. The implementation work must therefore target `agent-fs`, not `main`.

The `agent-fs` README also documents current gaps: tool-call rounds are not streamed round-by-round, no delete tool exists, and there is no per-edit approval dialog. These are constraints to verify against source and tests before changing behavior.

## Target architecture

- **Workspace Repository**: SAF-backed, path-normalized access rooted at the granted tree URI. It owns list/read/search/create/update operations and revision checks.
- **Document Model**: typed representations for configuration, system instructions, request layout, memory, tool definitions, skills, and session history.
- **Request Compiler**: resolves document sources, applies a deterministic layout, expands approved variables, validates tool schemas, and emits an immutable effective-request snapshot.
- **Generation Runtime**: provider-neutral turn and tool-call orchestration. Provider adapters translate the normalized request and stream events.
- **Tool Providers**: workspace operations are exposed through a capability registry with explicit read/write permissions and bounded execution.
- **Glassbox UI**: workspace browser, document editor, request inspector, tool progress, and proposed-change review.

## Workspace document layout

```text
<selected-folder>/
  .fpilot/
    config.md
    system.md
    request-layout.md
    memory.md
    tools/
    skills/
    sessions/
      <session-id>/
        history.md
        history.meta.json
    requests/
      <request-id>.md
```

The application must not create or modify this structure without explaining the change and obtaining the relevant permission. Missing files should have safe defaults and an explicit initialization flow.

## Delivery sequence and acceptance criteria

### Phase 0 — source audit and baseline

- Read the existing agent loop, API request/response models, provider configuration, SAF repository, tool registry, persistence models, and relevant UI.
- Record current branch head, build/test results, and behavior gaps.
- Verify repository and dependency licenses before importing or adapting code from APilot.
- No APilot source is to be copied until its license and provenance are reviewed.

**Exit:** source map, baseline test/build report, and an explicit port/adapt/reject decision for each candidate subsystem.

### Phase 1 — runtime boundary

- Introduce provider-neutral generation request, response, streaming event, and tool-call models.
- Keep the existing OpenAI-compatible implementation as the first adapter.
- Add adapter contract tests for text, tool calls, streaming completion, cancellation, and errors.

**Exit:** existing chat and agent flows pass through the normalized runtime without changing user-visible behavior.

### Phase 2 — SAF workspace repository

- Centralize all SAF access behind a repository interface.
- Normalize relative paths and reject traversal, absolute paths, and operations outside the granted tree.
- Add document identity/revision checks and explicit conflict results.
- Preserve read-only behavior by default; gate every write operation.

**Exit:** repository tests cover path safety, missing files, permission revocation, stale revisions, and write denial.

### Phase 3 — document model and initialization

- Add typed loaders for config, system, request layout, memory, tools, skills, and session documents.
- Define versioned front matter/metadata and deterministic defaults.
- Initialize `.fpilot/` only through a user-visible action.

**Exit:** malformed or absent documents fail safely and are reported with source locations.

### Phase 4 — Glassbox request compiler

- Compile system instructions, memory, session context, user request, and tool descriptions into a deterministic request.
- Produce a readable snapshot showing exact included content, source paths, ordering, variables, and transformations.
- Never include API keys or secret headers in snapshots.
- Validate the compiled request before network submission.

**Exit:** golden tests prove deterministic output and that every included section has provenance.

### Phase 5 — Markdown session history

- Define a versioned Markdown transcript format with stable message IDs and metadata.
- Treat user-editable Markdown as the authoritative content for visible conversation text; keep Room/database data as an index/cache/runtime projection.
- Record edited assistant text distinctly from the original generated response.
- Never infer or replay tool execution from edited transcript text.

**Exit:** round-trip tests preserve roles, IDs, timestamps, tool-result linkage, and edited content.

### Phase 6 — workspace tools

- Expose list, search, read, inspect, create, and exact-match edit through a capability registry.
- Add diff previews, revision checks, bounded output, cancellation, and per-operation audit records.
- Require confirmation for consequential writes; do not add delete until its confirmation and recovery model is specified.

**Exit:** permission, path traversal, stale-write, and confirmation tests pass.

### Phase 7 — Glassbox interface

- Add a workspace browser and Markdown editor.
- Add a request control panel and effective-request inspector.
- Stream assistant and tool progress as distinct events.
- Show proposed file changes as diffs before approval.

**Exit:** users can inspect and edit every non-secret model input before sending and review every write before it is applied.

### Phase 8 — prompting, memory, and skills

- Separate short-lived session context from durable workspace memory.
- Make memory updates explicit, inspectable, and reversible.
- Load skills on demand and include their source in request provenance.
- Add tests for precedence, token budgeting, and prompt injection boundaries.

**Exit:** memory and skill selection are deterministic, visible, and do not silently overwrite user documents.

### Phase 9 — provider compatibility

- Keep OpenAI-compatible behavior as the baseline.
- Add provider-specific adapters only where protocol differences require them.
- Test tool-call and streaming capability detection; report unsupported features instead of silently degrading.

**Exit:** adapter contract suite passes for each supported provider implementation.

### Phase 10 — quality and safety

- Add unit, integration, migration, and UI tests.
- Test SAF revocation, malformed documents, network interruption, cancellation, oversized tool output, prompt injection, and partial writes.
- Measure request compilation and workspace search performance.

**Exit:** CI runs the full test suite and build; known limitations are documented.

### Phase 11 — optional automation

MCP, subagents, shell execution, scheduled tasks, web search, and RAG are explicitly deferred until the workspace, request compiler, history, and tool safety layers are stable. Each requires a separate threat model and permission design.

## Non-goals for the first milestone

- No broad rewrite of the Compose UI.
- No automatic migration that overwrites user data.
- No silent background writes.
- No execution of shell commands.
- No secrets stored in Markdown or request snapshots.
- No copying APilot implementation without license review and attribution.

## Pull request policy

Each phase should be a focused PR with tests and a short migration note. Do not merge a phase solely because it compiles: its exit criteria must pass. The initial PR establishes this plan and the baseline target branch; subsequent PRs implement the phases in order.
