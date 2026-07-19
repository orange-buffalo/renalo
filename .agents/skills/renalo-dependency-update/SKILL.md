---
name: renalo-dependency-update
description: >
  Updates all Renalo build, backend, frontend, documentation, container, and
  CI dependencies, fixes compatibility issues, creates a pull request, waits
  for CI, addresses failures, and conditionally squash-merges safe updates.
compatibility: "requires: gh CLI, git, Java 25, Bun 1.3+, Gradle wrapper, repository write access"
---

# Renalo Dependency Update Skill

Use this skill when the user asks to update, upgrade, refresh, or audit all dependencies in Renalo. This is an end-to-end workflow: discover stable releases, update every dependency surface, make compatibility fixes, verify the repository, publish a pull request, remediate CI failures, and merge only when the changes satisfy the automatic-merge policy below.

Do not use this skill for a request limited to one named dependency.

## Safety Rules

- Preserve unrelated user changes. Start from a clean worktree; if it is not clean, stop and ask how to proceed.
- Never push directly to `main`, use destructive Git commands, use plain `--force`, or commit secrets.
- Select the latest stable release. Do not adopt snapshots, milestones, release candidates, betas, or alphas unless the repository already uses a prerelease for that dependency or the user explicitly requests it.
- Update direct dependencies, build plugins and tools, lockfiles, container base images, and pinned GitHub Actions. Do not intentionally rewrite transitive lockfile versions back to older releases.
- Read release notes and migration guides for major upgrades and for any upgrade that fails compilation or tests.
- Compatibility work must preserve existing behavior unless the dependency explicitly removed it and the user approves a behavioral change.
- Follow `AGENTS.md`, including formatting, documentation, testing, and generated-file rules.

## 1. Preconditions And Branch

Run:

```bash
gh auth status
gh repo view --json nameWithOwner,url,defaultBranchRef
git rev-parse --is-inside-work-tree
git status --short --branch
git log --oneline --decorate -n 10
git fetch origin main
```

Require an authenticated GitHub session, the `orange-buffalo/renalo` repository, `main` as the default branch, and a clean worktree. If currently on `main`, fast-forward it and create a descriptive branch:

```bash
git pull --ff-only origin main
git switch -c chore/update-dependencies
```

If `chore/update-dependencies` already exists locally or remotely, choose a unique suffix rather than deleting or overwriting it. If already on a non-main branch, confirm it contains no unrelated commits before using it; otherwise stop and ask the user.

## 2. Inventory Every Dependency Surface

Inspect at least:

- `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, and `gradle/wrapper/gradle-wrapper.properties`.
- `ui/package.json` and `ui/bun.lock`.
- `docs/user/package.json`, `docs/user/bun.lock`, and `docs/user/Dockerfile`.
- `.github/workflows/**` for pinned GitHub Actions, runtime versions, and images.
- All other `package.json`, lockfiles, Gradle files, Dockerfiles, workflow files, and explicit version strings found by repository search.

Record current versions before editing. Include Java, Bun, and other tool versions only when they are actually pinned by the repository.

## 3. Discover Stable Updates

Use multiple sources rather than trusting one report:

```bash
./gradlew --no-configuration-cache dependencyUpdates --console=plain
(cd ui && bun outdated)
(cd docs/user && bun outdated)
```

Also check:

- `https://services.gradle.org/versions/current` for the Gradle wrapper.
- Gradle Plugin Portal Maven metadata for every plugin.
- Maven Central metadata for explicitly versioned JVM dependencies and BOMs.
- The applicable package registry for Bun packages.
- Official registries for Docker image tags.
- Official GitHub Action releases for pinned action majors.

The Gradle report can miss newer framework BOMs or plugins and can suggest unstable versions. Independently verify its output. A managed dependency with no version is updated through the Micronaut platform; do not add unnecessary explicit versions.

## 4. Apply All Updates

Update Gradle and JVM versions manually, then regenerate the wrapper when needed:

```bash
./gradlew wrapper --gradle-version <latest-stable-gradle>
```

Update both Bun projects to latest releases and regenerate their lockfiles:

```bash
(cd ui && bun update --latest)
(cd docs/user && bun update --latest)
```

Update container tags and GitHub Actions when newer stable versions exist. Keep version ranges and exact pins consistent with each manifest's existing policy unless a package manager necessarily normalizes them.

Run the dependency reports again. Investigate every remaining reported update and document why any version is intentionally retained. Do not declare completion while an unexplained direct dependency update remains.

## 5. Compatibility And Verification

Fix compile, lint, type, build, and test failures caused by upgrades. Search release notes before changing application code, and use the smallest behavior-preserving migration.

After UI source changes, run `bun run format` from `ui/`. Then run the broad verification justified by this cross-cutting task:

```bash
(cd ui && bun run check && bun run test && bun run build)
(cd docs/user && bun install --frozen-lockfile && bun run check && bun run build)
./gradlew build
./gradlew --no-configuration-cache jibBuildTar
docker build --build-arg RENALO_DOCS_SITE_URL=http://localhost --tag renalo-docs:dependency-update docs/user
```

When frontend behavior or rendering code changes, inspect relevant Playwright trace screenshots as required by `AGENTS.md`. Dependency-only lockfile or manifest changes do not require new product documentation.

## 6. Classify Automatic-Merge Safety

Classify the final diff before publishing the pull request.

Automatic squash merge is allowed only when all of these are true:

- The diff consists of dependency declarations, lockfiles, wrapper/generated dependency files, container tags, CI action versions, and documentation about dependency tooling.
- Any compatibility edits are minor and mechanical, such as renamed imports, updated configuration keys, or type-only adaptations that preserve behavior.
- No test expectation, test fixture, business rule, persistence behavior, security behavior, authentication flow, API contract, database migration, user-facing workflow, or production algorithm changed.
- All local verification and PR checks pass.
- There is no uncertainty about runtime behavior or migration safety.

User review is required and automatic merge is forbidden when any of these is true:

- Production logic or meaningful test logic changed.
- Tests were weakened, removed, skipped, or had expected behavior changed.
- A database, API, security, authentication, serialization, scheduling, monetary, import, or user-facing behavior changed.
- Compatibility work is substantial, broad, or not obviously semantics-preserving.
- A major upgrade has unresolved migration risk, incomplete verification, or ambiguous release notes.
- Any required check is skipped or cannot run.

When uncertain, classify the PR as requiring user review. Record the classification and concrete reason in the PR description.

## 7. Commit And Publish Pull Request

Inspect the complete diff and recent history before committing:

```bash
git status --short
git diff --stat
git diff
git log --oneline --decorate -n 10
```

Stage only intended files and commit with a Conventional Commit message, normally:

```bash
git add <intended-files>
git commit -m "chore(deps): update dependencies"
```

Rebase and push safely:

```bash
git fetch origin main
git rebase origin/main
git push --force-with-lease -u origin "$(git branch --show-current)"
```

Create a PR against `main` with a Conventional Commit title. Use a temporary Markdown file for the body. Summarize dependency groups, compatibility changes, intentionally retained versions, and the automatic-merge classification. Do not invent validation claims or issue links.

```bash
gh pr create --base main --head "$(git branch --show-current)" --title "chore(deps): update dependencies" --body-file <body-file>
```

If a PR already exists for the branch, update it instead of creating a duplicate.

## 8. Wait For CI And Remediate Failures

Always wait for all PR checks:

```bash
gh pr checks <pr-number> --watch
```

For each failure:

1. Inspect `gh pr checks`, `gh run view <run-id>`, and `gh run view <run-id> --log-failed`.
2. Reproduce the smallest relevant failure locally.
3. Fix only the root cause related to the dependency update.
4. Run the relevant local verification.
5. Commit the fix as a new commit; do not amend.
6. Push normally unless another rebase made `--force-with-lease` necessary.
7. Re-evaluate the automatic-merge classification because CI remediation may have made the code changes substantial.
8. Wait for the new checks. Repeat until checks pass or remediation requires user input.

Never hide a failure by disabling a check, weakening a test, or excluding an affected dependency. If a failure is unrelated, flaky after a confirmed passing retry, or cannot be safely resolved, leave the PR open and report it to the user.

## 9. Merge Or Request Review

Only after all checks pass, apply the classification from step 6.

For an automatically mergeable PR, squash-merge it:

```bash
gh pr merge <pr-number> --squash --delete-branch
```

If branch protection requires queued or delayed merging, use squash auto-merge and wait for the final state:

```bash
gh pr merge <pr-number> --auto --squash --delete-branch
gh pr view <pr-number> --json state,mergeStateStatus,url
```

For a user-review-required PR, do not call `gh pr merge` at all. Leave it open and clearly report the compatibility changes requiring review.

## 10. Final Report

Report:

- The PR URL and final state.
- Major dependency groups and notable version changes.
- Compatibility changes made.
- CI outcome and any unresolved failures.
- Whether the PR was squash-merged automatically or left open for user review, with the classification reason.

Do not claim the update is complete until the PR checks have finished. Do not claim it was merged until GitHub reports the PR state as `MERGED`.
