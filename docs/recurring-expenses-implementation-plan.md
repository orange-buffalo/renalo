# Recurring Expenses Implementation Plan

This document tracks implementation progress for recurring expenses. Keep it updated as each step is started and completed.

Source of truth: `docs/recurring-expenses-design.md`.

This plan is an execution guide derived from the design. If this plan conflicts with the design, the design prevails unless the user explicitly overrides it in later instructions. REST endpoints used by the UI are implementation details for user interactions; they are not the future import/API matching flow described in the design.

Status legend:

- `[ ]` Not started
- `[~]` In progress
- `[x]` Complete

## Design Traceability

Each implementation step maps back to one or more design sections:

| Design section | Implementation steps |
| --- | --- |
| Goal | Steps 2, 3, 4, 5, 6 |
| Initial Schedule Support | Steps 1, 4, 7 |
| Terminology | Steps 8, 9, 10, 11 |
| Core Models | Steps 2, 3, 4 |
| Constraints | Steps 2, 3 |
| Generation Strategy | Steps 3, 4, 5 |
| Hourly Background Job | Step 5 |
| Schedule Immutability | Steps 8, 9 |
| Monthly Date Edge Case | Step 1 |
| Shared Recurrence Infrastructure | Steps 1, 12 |
| Overview UI | Step 6 |
| Create/Edit UI | Steps 7, 9 |
| Editing Recurring Expenses | Steps 8, 9 |
| Deleting Recurring Expenses | Steps 10, 11 |
| Regeneration Safety Rules | Steps 3, 5, 8, 10, 12 |
| Future Design Note: Import/API Matching | Future design note only, out of scope for the current implementation plan |
| Summary | All steps |

## Current Scope Boundaries

In scope for this plan:

- User-created recurring expenses through the existing UI-backed application flows.
- Normal REST endpoints required to support those UI interactions.
- Generated expense display, editing, deleting, and hourly generation.
- Shared recurrence foundations needed for future recurring incomes.

Out of scope for this plan:

- Import/API matching of externally created expenses into generated recurring expenses.
- Any dedicated external/import API behavior beyond the existing REST transport needed by the UI.
- Recurring incomes implementation, although shared recurrence code must be designed so incomes can reuse it later.

## Principles

- Implement recurring expenses as generation of normal `Expense` rows.
- Do not introduce a separate occurrence model.
- Keep schedule/date fields immutable after recurring rule creation.
- Never generate or recalculate recurring expenses from read-only flows.
- Centralize recurrence calculation so recurring incomes can reuse the same code later.
- Keep each implementation step reviewable while still delivering a visible increment.

## Step 1: Shared Recurrence Foundation

Status: `[x]`

Goal: Introduce reusable recurrence primitives without wiring them into expense persistence yet.

Deliverables:

- Add a shared recurrence package outside expense-specific code.
- Add a recurrence interval enum with initial values for day, week, and month.
- Add a recurrence schedule value type containing `frequency` and `interval`.
- Validate that `frequency` is a positive integer.
- Add shared occurrence calculation for daily, weekly, biweekly as every 2 weeks, and monthly schedules.
- Add monthly 29th/30th/31st fallback to the target month's last day.
- Add shared generation-window calculation for `today + 12 months`.
- Add shared recurrence description formatting, for example `Repeats weekly`, `Repeats every 2 weeks`, and `Repeats monthly until 12 Jun 2020`.

Tests:

- Unit-test occurrence calculation for day, week, every 2 weeks, and month.
- Unit-test monthly end-of-month fallback, including leap year February.
- Unit-test recurrence descriptions with and without end dates.

Review notes:

- No database or API behavior should change in this step.
- The code should be usable later by recurring incomes without depending on expense classes.

## Step 2: Database Model And Entities

Status: `[x]`

Goal: Add recurrence persistence structures while preserving current one-off expense behavior.

Deliverables:

- Add `RecurringExpenseRule` persistence model.
- Add recurrence fields to expense persistence:
  - `recurring_rule_id`
  - `recurring_instance_date`
  - `recurring_locked`
- Add `RecurringExpenseSkip` persistence model.
- Add a partial unique constraint preventing duplicate expenses for the same recurring rule and instance date.
- Add a unique constraint for recurring skips by rule and instance date.
- Add repository/data-access methods needed for later generation and scope operations.
- Ensure existing expenses remain non-recurring with null recurrence fields and `recurring_locked = false`.

Tests:

- Migration test or repository-level verification for nullable recurrence fields on existing expense flows.
- Repository tests for uniqueness constraints.
- Repository tests for finding expenses and skips by rule and instance date.

Review notes:

- Do not expose recurrence through APIs yet unless needed for internal tests.
- Existing expense API and UI tests should remain unchanged except for harmless extra persisted columns.

## Step 3: Recurring Expense Generation Service

Status: `[x]`

Goal: Generate normal expense rows from recurring expense rules through a write-side service.

Deliverables:

- Add a recurring expense generation service.
- Generate missing expenses from `rule.start_date` through `today + 12 months`.
- Respect `rule.end_date` when present.
- Skip dates recorded in `RecurringExpenseSkip`.
- Do not create duplicates for existing `recurring_rule_id + recurring_instance_date` pairs.
- Do not overwrite locked expenses.
- Update unlocked generated expenses from the rule when generation decides an update is required.
- Update `generated_until` and `last_generated_at` after successful processing of a rule.
- Handle duplicate insert conflicts safely for concurrent generation attempts.
- Continue processing other rules if one rule fails.

Tests:

- Service tests for creating expected generated expenses.
- Service tests for idempotency across repeated runs.
- Service tests for skip records.
- Service tests for locked expenses not being overwritten.
- Service tests for end date limiting generation.
- Service tests for generation continuing after one rule fails if practical at this layer.

Review notes:

- This service must not be called from read-only list/report/page-load paths.
- Keep shared recurrence calculation usage visible in tests.

## Step 4: Create Recurring Expense API Flow

Status: `[x]`

Goal: Allow users to create recurring expenses and immediately generate their expense rows.

Deliverables:

- Extend expense creation request handling to accept recurrence data when the UI checkbox is enabled.
- Persist a `RecurringExpenseRule` for recurring creations.
- Store schedule as `frequency` plus `interval`.
- Treat UI choices such as biweekly as every `2` weeks.
- Immediately generate expenses through the recurring expense generation service.
- Preserve existing one-off expense creation behavior when recurrence data is absent.
- Ensure created generated expenses are regular user-bound through existing expense ownership rules.

Tests:

- API tests for one-off creation staying unchanged.
- API tests for daily, weekly, biweekly, and monthly recurring creation.
- API tests for generation from past start dates through the configured window.
- API security tests proving users cannot create recurrence data for another user's account/category.
- API tests asserting complete JSON response bodies for changed endpoints.

Review notes:

- If the API response includes recurrence metadata, include enough data for the overview description without forcing read-time generation.

## Step 5: Hourly Background Generation Job

Status: `[ ]`

Goal: Keep active recurring rules generated up to the rolling 12-month window.

Deliverables:

- Add an hourly scheduled job.
- Process active recurring expense rules.
- Skip rules whose `generated_until` already covers `today + 12 months` where appropriate.
- Use the recurring expense generation service for all generation behavior.
- Log failures per rule without stopping the whole job.

Tests:

- Job tests proving active rules are processed.
- Job tests proving already-covered rules are skipped if that optimization is implemented.
- Job tests proving failed rules do not stop later rules.

Review notes:

- The job should be thin orchestration around the generation service.

## Step 6: Overview API And Table Display

Status: `[ ]`

Goal: Show recurring metadata in the expenses overview without changing reporting semantics.

Deliverables:

- Include recurrence display metadata in expense list responses for generated recurring expenses.
- Render recurring expenses as normal rows in the overview table.
- Add a second line in the Date column for recurring rows.
- Use 90% font size for the recurrence description line.
- Generate descriptions such as `Repeats weekly` and `Repeats monthly until 12 Jun 2020` from shared formatting logic or equivalent API-provided display data.
- Keep non-recurring expense rows visually unchanged except for any needed layout alignment.

Tests:

- API tests for recurrence metadata in list responses if the API response changes.
- Playwright tests asserting table content for recurring and non-recurring rows.
- Visual trace review for table spacing, second-row date display, and responsive layout.

Review notes:

- Do not add read-time generation to support the overview.
- Follow existing table empty/loading/action patterns.

## Step 7: Create Screen UI

Status: `[ ]`

Goal: Add recurring expense controls to the create expense UI.

Deliverables:

- Add a UI-only checkbox labeled `Recurring expense`.
- Hide schedule controls when unchecked.
- Show schedule controls when checked.
- Provide initial choices for daily, weekly, biweekly, and monthly.
- Submit recurrence as frequency plus interval.
- Preserve the current one-off creation flow when unchecked.
- Keep the form aligned with standard page form layout rules.

Tests:

- Playwright tests for creating a one-off expense with the checkbox unchecked.
- Playwright tests for creating a recurring expense with each initial schedule option if practical, or representative options plus API coverage for the rest.
- Visual trace review for the hidden and visible schedule states.

Review notes:

- Use Untitled UI components where applicable.
- Do not hand-write component replacements if existing Untitled UI components cover the controls.

## Step 8: Edit Scope API Flows

Status: `[ ]`

Goal: Implement edit behavior for the three recurring scopes.

Deliverables:

- Add edit scope request handling for recurring expenses:
  - This occurrence only
  - This and all following occurrences
  - All occurrences
- For this occurrence only, update the selected expense and set `recurring_locked = true`.
- For this and all following occurrences, end the old rule before the selected occurrence, create a new rule starting at the selected occurrence, apply non-schedule changes to the new rule, and generate the new rule.
- For all occurrences, update the rule and all unlocked linked expenses, including past expenses.
- Do not allow schedule/date fields to be changed on an existing recurring rule.
- Do not overwrite locked expenses except the selected expense being explicitly edited when applicable.

Tests:

- API tests for each edit scope.
- API tests proving schedule fields are immutable after rule creation.
- API tests proving locked occurrences are preserved during bulk updates.
- API security tests for editing only the current user's recurring expenses.

Review notes:

- Keep scope terminology exactly as defined in the design.

## Step 9: Edit Screen UI

Status: `[ ]`

Goal: Add recurring edit scope UX while keeping schedule fields immutable.

Deliverables:

- Detect when an expense is part of a recurring series.
- Show the current recurrence schedule for context.
- Prevent editing recurrence date, start date, frequency, interval, and pattern for existing recurring rules.
- Present edit scope choices using exact labels:
  - This occurrence only
  - This and all following occurrences
  - All occurrences
- Submit the selected scope with non-schedule changes.
- Preserve existing one-off edit behavior for non-recurring expenses.

Tests:

- Playwright tests for one-off edit behavior staying unchanged.
- Playwright tests for each recurring edit scope or representative UI coverage backed by API tests.
- Playwright tests proving schedule controls are not editable for existing recurring expenses.
- Visual trace review for the recurring edit UI.

Review notes:

- Do not use the term future occurrences in UI copy.

## Step 10: Delete Scope API Flows

Status: `[ ]`

Goal: Implement delete behavior for the three recurring scopes.

Deliverables:

- Add delete scope request handling for recurring expenses:
  - This occurrence only
  - This and all following occurrences
  - All occurrences
- For this occurrence only, delete the selected expense and create a skip for the selected rule and instance date.
- For this and all following occurrences, end the old rule before the selected instance date, delete linked expenses from the selected instance onward, and delete related skip rows in the affected range.
- For all occurrences, mark the rule deleted, delete all linked expenses, and delete all linked skip rows.
- Allow explicit deletes to delete locked expenses within the selected scope.

Tests:

- API tests for each delete scope.
- API tests proving this occurrence only creates a skip and generation does not recreate the deleted row.
- API tests proving this and all following occurrences affects selected and later instance dates, not dates after today.
- API security tests for deleting only the current user's recurring expenses.

Review notes:

- Scope behavior must be based on `recurring_instance_date`, not the current date.

## Step 11: Delete UI Flows

Status: `[ ]`

Goal: Add recurring delete scope UX.

Deliverables:

- Detect recurring expenses when delete is requested.
- Present delete scope choices using exact labels:
  - This occurrence only
  - This and all following occurrences
  - All occurrences
- Submit the selected scope to the delete API.
- Preserve existing one-off delete behavior for non-recurring expenses.
- Refresh the table from existing expenses after deletion without triggering generation.

Tests:

- Playwright tests for one-off delete behavior staying unchanged.
- Playwright tests for recurring delete scope selection and resulting table content.
- Visual trace review for the delete scope UI.

Review notes:

- Make destructive scope choices clear without introducing unsupported terminology.

## Step 12: Cross-Feature Consistency And Documentation

Status: `[ ]`

Goal: Harden the implementation and leave a clear base for recurring incomes.

Deliverables:

- Review recurrence code boundaries and remove expense-specific assumptions from shared recurrence code.
- Confirm no read-only expense flows call generation.
- Confirm all recurrence descriptions use the shared formatting path or an explicitly equivalent DTO generated from it.
- Update `AGENTS.md` with durable recurrence implementation conventions discovered during implementation.
- Update this plan with completed status and any deferred follow-up decisions.

Tests:

- Run targeted regression tests covering the changed API and Playwright surfaces.
- Inspect Playwright trace screenshots for overview, create, edit, and delete recurring flows.

Review notes:

- Do not run the full test suite by default unless targeted coverage is insufficient or the change is broadly cross-cutting.
