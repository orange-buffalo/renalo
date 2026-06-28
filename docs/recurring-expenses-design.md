# Recurring Expenses Design

## Goal

Recurring expenses are implemented as a generator of normal `Expense` records.

The application does not distinguish between manual, imported, API-created, past, present, or future expenses for reporting purposes. If an `Expense` exists, it is considered a real expense and is shown/reported normally.

There is no separate `ExpenseOccurrence` model.

`RecurringExpenseRule -> Expense`

Generated recurring expenses are normal `Expense` rows with recurrence metadata.

The same design is expected to apply later to recurring incomes. Expense implementation must therefore avoid expense-only recurrence logic where practical. Recurrence schedule calculation, scope semantics, locking rules, and human-readable recurrence descriptions should be generalized so they can be reused consistently by expenses and incomes.

## Initial Schedule Support

The initial phase supports these schedules in the UI:

- Daily
- Weekly
- Biweekly
- Monthly
- Custom

The persisted model must be extensible from the beginning. Schedules should be stored as a frequency plus an interval unit, not as fixed schedule names.

Examples:

- Daily: every `1` `day`
- Weekly: every `1` `week`
- Biweekly: every `2` `weeks`
- Monthly: every `1` `month`
- Custom: user-selected frequency from `1` to `100` plus interval unit `day`, `week`, or `month`

This allows schedules such as every 3 days or every 6 weeks without changing the core recurrence model, while still leaving room for future interval units such as yearly.

Schedule calculation must be centralized in shared recurrence code. It must not be duplicated inside expense-specific services or UI-specific helpers.

## Terminology

For editing/deleting recurring expenses, the supported scopes are:

1. This occurrence only
2. This and all following occurrences
3. All occurrences

Do not use the term future occurrences in the UI or requirements, because "future" can be confused with "after today".

"This and all following occurrences" means:

> The selected occurrence and every later occurrence in the same recurring sequence, regardless of whether their dates are in the past, present, or future.

Example:

Today is June.
User selects the March occurrence.
"This and all following occurrences" means March, April, May, June, July, ...

## Core Models

### `RecurringExpenseRule`

Represents the recurrence generator/template.

Only recurrence-related fields are listed here.

- `id`
- `start_date`
- `end_date` nullable
- `recurrence_frequency`
- `recurrence_interval`
- `recurrence_rule` nullable/reserved for future complex rules
- `status`
- `generated_until`
- `last_generated_at` nullable
- `created_at`
- `updated_at`

Field notes:

#### `start_date`

The first scheduled date of the recurrence.

#### `end_date`

The last date on which this rule is allowed to generate expenses. `null` means no known end.

#### `recurrence_frequency`

The positive integer frequency for the recurrence.

Examples:

- `1` for every day/week/month
- `2` for every 2 weeks, which the UI may label as biweekly
- `3` for every 3 months

#### `recurrence_interval`

The interval unit used with `recurrence_frequency`.

Initial supported values:

- `day`
- `week`
- `month`

The model should allow future values such as `year` without changing the recurrence concept.

#### `recurrence_rule`

Reserved for future complex recurrence patterns if frequency plus interval becomes insufficient. Exact complex recurrence types are out of scope for this version of the design.

#### `status`

Suggested values:

- `active`
- `ended`
- `deleted`

#### `generated_until`

Records how far ahead expenses have already been generated for this rule.

#### `last_generated_at`

Records when the background generation job last processed this rule.

### `Expense`

Represents an actual expense shown and reported by the app.

Only recurrence-related fields are listed here.

- `id`
- `date`
- `recurring_rule_id` nullable
- `recurring_instance_date` nullable
- `recurring_locked` boolean default false
- `created_at`
- `updated_at`

Field notes:

#### `date`

The actual expense date used in reporting.

For generated recurring expenses, this initially equals the scheduled recurrence date.

If an imported/API-created expense is matched into this generated expense, `date` may be updated to the actual observed date.

#### `recurring_rule_id`

The recurring rule that generated this expense.

`null` means this expense is not part of a recurring series.

#### `recurring_instance_date`

The scheduled recurrence slot this expense belongs to.

This is separate from `date`.

Example:

`recurring_instance_date = 2026-07-15`
`date = 2026-07-15` initially

If a matching imported/API expense is later merged and has the same matched date, the date remains the same. If matching rules are expanded in the future, `date` may differ from `recurring_instance_date`.

#### `recurring_locked`

Protects this specific expense from automatic recurrence generation or bulk rule updates.

Set to `true` when:

- user edits this occurrence only
- imported/API expense is merged into this generated expense

Locked expenses are still normal expenses. Locking only means the recurrence generator must not overwrite them automatically.

### `RecurringExpenseSkip`

Represents a deleted/skipped recurrence slot.

Used so that deleting one generated occurrence does not cause the generator to recreate it later.

- `id`
- `recurring_rule_id`
- `recurring_instance_date`
- `created_at`

Recommended constraint:

`unique(recurring_rule_id, recurring_instance_date)`

## Constraints

Recommended constraint on `Expense`:

```sql
unique(recurring_rule_id, recurring_instance_date)
where recurring_rule_id is not null
```

This prevents duplicate generated expenses for the same recurrence slot.

Recommended constraint on `RecurringExpenseSkip`:

```sql
unique(recurring_rule_id, recurring_instance_date)
```

## Generation Strategy

Recurring expenses are generated as real `Expense` rows.

Generation window:

from `rule.start_date`
to today + 12 months

If a rule starts in the past, the app generates past expenses from `start_date` to today.

Recurring expenses are generated by:

- immediate generation when a recurring rule is created
- immediate scoped generation/update during supported edit flows
- an hourly background job

Recurring expenses must not be generated or recalculated on normal page load, list load, report load, or read-only API access.

Read flows should only read existing `Expense` records.

## Hourly Background Job

A background job runs once per hour.

Purpose:

Ensure all active recurring rules have generated `Expense` rows up to today + 12 months.

The job must be idempotent. Running it multiple times must not create duplicates or overwrite locked/custom expenses.

Job behavior:

```text
For each active RecurringExpenseRule:
  target_generation_until = today + 12 months

  If generated_until >= target_generation_until:
    Optionally skip this rule.

  Otherwise:
    Generate missing recurrence slots from rule.start_date to target_generation_until.

    For each expected recurrence date:
      If RecurringExpenseSkip exists for this rule + date:
        Do nothing.

      Else if Expense exists for this rule + recurring_instance_date:
        If recurring_locked = false:
          It may be updated from the rule if required.
        If recurring_locked = true:
          Leave it unchanged.

      Else:
        Create Expense:
          date = recurring_instance_date
          recurring_rule_id = rule.id
          recurring_instance_date = calculated recurrence date
          recurring_locked = false

    Set generated_until = target_generation_until.
    Set last_generated_at = now.
```

Concurrency requirement:

The job must be safe if two workers run at the same time.
The unique constraint on `recurring_rule_id + recurring_instance_date` is required.
Duplicate insert conflicts should be ignored or retried safely.

Failure handling:

If generation fails for one rule, the job should continue processing other rules.
The failed rule can be retried by the next hourly run.

## Schedule Immutability

Recurring schedule/date fields are immutable after creation.

Users cannot edit the following on an existing recurring rule:

- date of recurrence
- start date
- frequency
- interval
- recurrence pattern

To change the schedule, the user must delete the relevant scope and create a new expense or recurring expense.

Examples:

Move one occurrence from the 5th to the 8th:

Delete this occurrence only.
Create a normal one-off `Expense` on the 8th.

Change monthly bill from the 5th to the 8th from March onward:

Delete this and all following occurrences from March.
Create a new recurring expense starting March 8.

Correct an incorrectly-created schedule for the whole series:

Delete all occurrences.
Create a new recurring expense with the correct schedule.

## Monthly Date Edge Case

For monthly recurrences scheduled on the 29th, 30th, or 31st:

If the target month does not contain that day, use the last day of the month.

Example:

Monthly on the 31st:

- January 31
- February 28 or 29
- March 31
- April 30

This behavior belongs in the shared recurrence calculation code so recurring incomes later use the exact same monthly date handling.

## Shared Recurrence Infrastructure

Recurring expenses are the first consumer of recurrence behavior, but the recurrence infrastructure must be designed for reuse by recurring incomes.

Shared behavior should include:

- recurrence schedule representation, including frequency and interval
- occurrence date calculation
- monthly end-of-month fallback behavior
- generation-window calculation
- scope terminology and selection semantics
- lock/skip safety semantics where the owning domain model supports them
- human-readable recurrence description formatting

Expense-specific behavior should be limited to expense persistence, expense field copying, expense security, expense API contracts, and expense UI forms/tables.

The implementation should make it difficult for future recurring incomes to diverge in occurrence calculation or scope behavior. If a recurrence calculation bug is fixed, expenses and incomes should receive the fix through the same shared code path.

## Overview UI

In the expenses overview, recurring expenses are displayed as normal expense rows.

For rows linked to an active recurring rule, the Date column displays a second line below the normal expense date:

- font size: 90% of the main date text
- content: a human-readable recurrence description

Examples:

- `Repeats daily`
- `Repeats weekly`
- `Repeats every 2 weeks`
- `Repeats monthly until 12 Jun 2020`

The recurrence description must be generated from the stored recurrence schedule and optional end date. The same description logic should be reusable later for incomes.

A recurrence is active for overview highlighting only when its end date is absent or after today. If the recurrence end date has passed or is today, existing expenses remain normal historical facts and the overview must not show the recurrence description line for those rows.

Expense list/detail API responses for generated recurring expenses must include the rule start date and end date alongside the recurrence description, so the UI can make active/inactive display decisions without parsing human-readable text.

The UI must not use the term future occurrences. It must use `This and all following occurrences` for scoped edit/delete flows.

## Create/Edit UI

Create and edit screens expose recurrence as a UI-only checkbox:

`Recurring expense`

When unchecked:

- schedule controls are hidden
- the expense is created or edited as a normal one-off expense

When checked:

- schedule controls are displayed
- the submitted data creates or updates the recurrence according to the supported scope behavior

Initial schedule controls should map to the extensible frequency/interval model. For example, a `Biweekly` choice in the UI should submit/store every `2` `weeks`, not a separate `biweekly` schedule type. A `Custom` choice should reveal a second row with `Repeat every` and `Cadence` dropdowns, where the frequency options are `1` through `100` and cadence options are day, week, and month.

On existing recurring expenses, schedule/date fields remain immutable. Edit screens display the current schedule and the first occurrence date for context, using text such as `The first occurrence in this series is on 5 Jun 2022.` Users cannot change the recurrence date, start date, frequency, interval, or recurrence pattern on the existing recurring rule. To change the schedule, they must delete the relevant scope and create a new expense or recurring expense.

## Editing Recurring Expenses

Editing supports only non-schedule fields.

Date/schedule fields cannot be edited. The user must delete and recreate instead.

Supported edit scopes:

- This occurrence only
- This and all following occurrences
- All occurrences

## Edit: This Occurrence Only

Behavior:

Update the selected `Expense`.
Set `recurring_locked = true`.
Do not update the `RecurringExpenseRule`.
Do not update any other `Expense`.

The selected occurrence becomes a custom occurrence.

## Edit: This and All Following Occurrences

Behavior:

End the old `RecurringExpenseRule` before the selected occurrence.
Create a new `RecurringExpenseRule` starting at the selected occurrence.
Copy the same schedule into the new rule.
Apply the edited non-schedule values to the new rule.
Generate expenses for the new rule up to today + 12 months.

The original rule remains responsible for occurrences before the selected occurrence.

The new rule is responsible for the selected occurrence and all following occurrences.

Locked expenses in the affected scope are not automatically overwritten unless they are the selected expense being explicitly edited.

## Edit: All Occurrences

Behavior:

Update the `RecurringExpenseRule`.
Update all unlocked `Expenses` linked to that rule, including past expenses.
Also update the selected `Expense` even if it is already locked, because the user is explicitly editing that occurrence as the entry point for the all-occurrences action.
Do not overwrite any other locked `Expenses`.

This is intended for correcting the whole recurring series.

Example:

User entered the wrong amount/category/name when creating the recurrence.
They select "All occurrences".
The rule is updated.
All unlocked generated expenses are updated.
The selected expense is updated even when it was previously locked by a "This occurrence only" edit.
Other locked/custom occurrences remain unchanged.

## Deleting Recurring Expenses

Deletion supports the same scopes:

- This occurrence only
- This and all following occurrences
- All occurrences

Deletion is an explicit user action and may delete locked expenses within the selected scope.

When deleting an expense from a recurring series, the confirmation dialog must show the series boundaries for context, using text such as `This expense is part of the repeated series starting 5 Jun 2022 and ending 5 Jun 2023.` If the rule has no end date, the dialog should state that there is no end date.

## Delete: This Occurrence Only

Behavior:

Delete the selected `Expense`.
Create `RecurringExpenseSkip` for:

- `recurring_rule_id`
- `recurring_instance_date`

The skip prevents the hourly background job from recreating the deleted occurrence.

## Delete: This and All Following Occurrences

Behavior:

Set old `RecurringExpenseRule.end_date` to before the selected `recurring_instance_date`.
Delete `Expenses` linked to the old rule where `recurring_instance_date >= selected recurring_instance_date`.
Delete related `RecurringExpenseSkip` rows in the same affected range.

No skip records are needed for the deleted following occurrences because the rule no longer covers those dates.

## Delete: All Occurrences

Behavior:

Mark `RecurringExpenseRule` as deleted.
Delete all `Expenses` linked to the rule.
Delete all `RecurringExpenseSkip` rows linked to the rule.

## Future Design Note: Import/API Matching

This section is out of scope for the initial recurring expenses implementation because import flows do not exist yet. It remains a future design note for externally created/imported expenses, not for normal UI interactions that use the application's REST endpoints.

When a new imported/API-created expense is received, the app should try to match it against already-generated recurring expenses.

Matching rule for this version:

- same date
- same amount

If a match is found:

Update/merge into the existing generated `Expense`.
Set `recurring_locked = true`.
Do not create a duplicate `Expense`.

The merged expense remains linked to the recurring rule.

This treats the imported/API-created data as a confirmation or replacement of the generated recurring expense.

If no match is found:

Create a new `Expense` normally.

## Regeneration Safety Rules

The recurrence generator must never recreate or overwrite user-intended exceptions.

Rules:

- Do not generate recurring expenses during read-only page/API/report loads.
- Do not create an `Expense` if a matching `RecurringExpenseSkip` exists.
- Do not create a duplicate `Expense` for the same `recurring_rule_id + recurring_instance_date`.
- Do not automatically overwrite an `Expense` where `recurring_locked = true`.
- Do not allow schedule/date edits on an existing recurring rule.

## Summary

The design intentionally avoids a separate occurrence model.

Recurring expenses are generated directly as normal expenses.

`RecurringExpenseRule -> Expense`

A generated recurring expense remains the same `Expense` row forever:

future expense -> current expense -> past expense

There is no transformation step.

The minimum recurrence metadata required on `Expense` is:

- `recurring_rule_id`
- `recurring_instance_date`
- `recurring_locked`

The three supported user scopes are:

- This occurrence only
- This and all following occurrences
- All occurrences

Recurring schedules are immutable after creation. Schedule changes are handled by deletion plus creation of a new normal or recurring expense.

Recurring generation is performed by write flows and by an hourly background job. It is not performed during normal page load or read-only access.
