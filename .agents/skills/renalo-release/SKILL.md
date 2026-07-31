---
name: renalo-release
description: >
  Creates and finalizes a Renalo release automatically: verifies local and
  GitHub state, creates the version tag and changelog, opens a draft
  prerelease, waits for release CI, remediates trivial failures, and promotes
  the successful release as the latest final GitHub release.
compatibility: "requires: gh CLI, git, Java 25, Bun 1.3+, Gradle wrapper"
---

# Renalo Release Skill

Use this skill when the user wants to create, publish, or finalize a Renalo release or run the local release workflow.

This workflow is intentionally local, stateful, and automatic. Run it end to end without asking for routine confirmation. CI must not create tags or GitHub releases; CI publishes release container images when it sees a release version on `main`.

Ask the user only when an error cannot be resolved safely or a non-trivial decision is required. In particular, do not ask the user to approve the generated changelog by default.

## Context Requirement

Use the full conversation and repository context when writing release notes, classifying CI failures, and deciding whether a remediation is trivial. Do not rely only on commit prefixes or the user's latest message when more precise context is available.

## Preconditions

Run these checks before changing anything:

```bash
gh auth status
gh repo view --json nameWithOwner,url,defaultBranchRef
git rev-parse --is-inside-work-tree
git branch --show-current
git status --short
git fetch origin main --tags
git status --branch --short
git rev-parse HEAD
git rev-parse origin/main
```

Required state:

- GitHub CLI is authenticated and the repository is reachable.
- The current directory is inside the Renalo repository.
- The current branch is exactly `main`.
- The working copy is clean, including staged, unstaged, and untracked files.
- `origin/main` exists and is reachable.
- Never release files that may contain secrets or local credentials.

If local `main` is strictly behind `origin/main`, fast-forward automatically and recheck the clean state:

```bash
git merge --ff-only origin/main
```

If local `main` is ahead of or has diverged from `origin/main`, stop and report the error. Do not push unknown local commits, rewrite `main`, or choose a reconciliation strategy without the user.

## Automatic Release Loop

A release attempt consists of creating a version and tag, crafting its changelog, pushing it, creating a draft prerelease, and waiting for CI. Repeat the attempt automatically when a trivial CI fix requires a new commit and therefore a new version.

Keep every created version tag immutable. Never move or delete a release tag, delete a failed release, or reuse a version after remediation.

### 1. Capture the Changelog Baseline

Use the latest non-draft, non-prerelease GitHub release as the changelog baseline. Capture it once before the first attempt and retain the same baseline for every replacement attempt in this invocation, so a release created after CI remediation still documents the complete change set.

```bash
repo="$(gh repo view --json nameWithOwner --jq .nameWithOwner)"
baseline_tag="$(gh api "repos/${repo}/releases?per_page=100" \
  --jq '([.[] | select(.draft == false and .prerelease == false)][0].tag_name) // empty')"
baseline_release_url="$(gh api "repos/${repo}/releases?per_page=100" \
  --jq '([.[] | select(.draft == false and .prerelease == false)][0].html_url) // empty')"
```

If no final GitHub release exists, fall back to the latest remote semantic-version tag that is not being created by this workflow:

```bash
if [[ -z "$baseline_tag" ]]; then
  baseline_tag="$(git tag --list 'v[0-9]*.[0-9]*.[0-9]*' --sort=-version:refname | head -n 1)"
fi
```

Rules:

- Prefer the latest final GitHub release over prereleases, drafts, or raw tags.
- Use the baseline tag as the exclusive lower bound for every changelog generated during this invocation.
- If there is no final release and no version tag, treat this as the first release and collect history from the first commit.
- Preserve tags exactly as reported, normally `vX.Y.Z`.

### 2. Create a Release Attempt

Run the semver plugin release task:

```bash
./gradlew releaseVersion --console=plain
new_version="$(./gradlew -q printVersion | grep -E '^[0-9]+\.[0-9]+\.[0-9]+' | tail -n 1)"
new_tag="v${new_version}"
release_sha="$(git rev-parse HEAD)"
git rev-parse "$new_tag"
git status --short
```

Expected result:

- The task creates a release commit and local release tag.
- `new_version` is present and does not contain `-SNAPSHOT`.
- `new_tag` exists locally and points to `release_sha`.
- The working copy is clean.
- The tag does not already exist remotely.

Check the remote tag before pushing:

```bash
if git ls-remote --exit-code --tags origin "refs/tags/${new_tag}"; then
  echo "Remote release tag already exists: ${new_tag}" >&2
  exit 1
fi
```

Stop on any unexpected state. Do not manually edit the project version or invent a replacement tag.

### 3. Craft the Changelog Automatically

Collect non-merge commits from the fixed baseline through the current release commit:

```bash
if [[ -n "$baseline_tag" ]]; then
  git log "${baseline_tag}..HEAD" --no-merges --pretty=format:'%H%x09%s%x09%an'
  git shortlog -sn "${baseline_tag}..HEAD"
  git diff --stat "${baseline_tag}..HEAD"
else
  git log --no-merges --pretty=format:'%H%x09%s%x09%an'
  git shortlog -sn HEAD
fi
```

Create the changelog without requesting approval:

```bash
notes_file="$(mktemp -t renalo-release-notes.XXXXXX.md)"
```

Use this structure, omitting empty categories:

```markdown
## What's Changed

### 🚀 New Features
- Clear user-facing feature description (#123)

### 🐛 Bug Fixes
- Clear user-facing bug fix description (#124)

### 🏗️ Build & CI
- Build or CI change that matters to maintainers (#125)

### 📚 Documentation
- Documentation update (#126)

### 🧹 Refactorings
- Refactoring summary only when useful for maintainers (#128)

### ✅ Tests
- Test-only change summary only when useful for maintainers (#129)

### 📦 Dependency Updates
- Dependency update summary (#127)
```

Formatting and classification rules:

- Start with `## What's Changed` and use the headings shown above.
- Keep `Dependency Updates` last when present.
- Exclude merge commits, release-only commits, and uncategorized commits that do not describe user-visible or useful maintainer-visible behavior.
- Exclude bot or infrastructure-only authors from contributor-facing notes where appropriate, including `[bot]`, `GitHub`, and `orange-buffalo`.
- Classify `feat` as `New Features`, `fix` as `Bug Fixes`, `build` and `ci` as `Build & CI`, `docs` as `Documentation`, `refactor` as `Refactorings`, and `test` or `tests` as `Tests`.
- Put Dependabot-style bumps and dependency-specific `chore` commits under `Dependency Updates`.
- Include `perf` under `Bug Fixes` only for user-visible performance corrections; otherwise omit it unless it matters to maintainers.
- Omit other `chore` commits unless they are release-relevant.
- Prefer and preserve pull request or issue references from commit subjects.
- Rewrite conventional commit subjects into concise sentence-case entries with no trailing period.
- Preserve the technical meaning, do not exaggerate impact, and do not invent unsupported behavior.
- If a commit remains unclear after inspecting its diff and available context, omit it rather than asking the user to word it.
- If there are no qualifying entries, still create a valid `## What's Changed` body with a concise factual summary supported by the commit range.

The agent owns changelog quality. Ask the user only if accurate notes require a genuine product decision or unavailable information that cannot be determined from commits, diffs, pull requests, issues, documentation, or the conversation.

### 4. Push and Create the Draft Prerelease

Push the release commit first, then its immutable tag:

```bash
git push origin main
git push origin "$new_tag"
```

Do not create a GitHub release unless both pushes succeed. Then create the same draft prerelease used by the current release process:

```bash
gh release create "$new_tag" \
  --title "$new_tag" \
  --notes-file "$notes_file" \
  --draft \
  --prerelease \
  --latest=false
release_url="$(gh release view "$new_tag" --json url --jq .url)"
```

Record every attempted version, tag, SHA, release URL, CI URL, and outcome for the final report.

### 5. Find and Wait for Release CI

The release CI run is the `Build` workflow run for the exact release commit pushed to `main`. Poll every 10 seconds for up to 5 minutes until GitHub registers it rather than treating initial absence as failure:

```bash
gh run list --workflow build.yml --branch main --commit "$release_sha" \
  --json databaseId,url,status,conclusion,headSha --limit 10
```

Select only the run whose `headSha` equals `release_sha`, then wait for completion:

```bash
gh run watch "$run_id" --exit-status
```

If no exact run appears after a reasonable polling period, investigate workflow dispatch state, Actions availability, and workflow naming. Stop only when this cannot be resolved safely.

### 6. Handle CI Failure

On failure, inspect before deciding:

```bash
gh run view "$run_id"
gh run view "$run_id" --log-failed
```

Use artifacts, Playwright traces, local reproduction, and the smallest relevant validation when needed. Never weaken, skip, or delete a failing check merely to release.

#### Transient or External Failure

Examples include a runner outage, network timeout, package registry outage, or another clearly external one-off failure with no repository defect.

- Rerun only failed jobs with `gh run rerun "$run_id" --failed`.
- Wait for the rerun to finish and continue automatically when it succeeds.
- Automatically rerun a distinct clearly transient failure at most twice. If it persists or its classification becomes uncertain, stop and ask the user rather than looping indefinitely.
- A successful rerun keeps the same version and release attempt.

#### Trivial Repository Fix

A fix is trivial only when the root cause and correction are clear, narrowly scoped, preserve intended behavior, and require no meaningful product, security, data, compatibility, dependency, or architecture decision. Examples include an obvious typo, deterministic formatting error, missing generated update, or a small test/build correction whose intended result is already established.

For a trivial fix:

1. Apply the smallest correct change directly on local `main`.
2. Run the smallest relevant local validation.
3. Inspect `git status`, `git diff`, and recent history.
4. Commit the fix with a Conventional Commit message and push it to `main`.
5. Leave the failed tag and draft prerelease intact and unmodified for auditability.
6. Return to **Create a Release Attempt**. The semver plugin must issue a new version and tag.
7. Regenerate the changelog from the original fixed baseline so the replacement release includes both the intended release changes and the CI fix.
8. Create another draft prerelease and wait for CI again.

The final report must identify the failed version, failing CI job, root cause, files changed, validation performed, fix commit, replacement version, and both GitHub release URLs.

#### Non-Trivial or Unclear Failure

Stop and let the user decide when remediation is unclear or non-trivial. This includes changes to product behavior, authentication or security, database migrations or persisted data, public compatibility, dependency selection or downgrade, broad refactoring, test expectation changes without an established intended result, repeated flaky failures without a proven cause, or any need to rewrite published history.

Report the evidence, root cause if known, available options, tradeoffs, current tags/releases, and a recommendation. Do not guess, mutate release history, or finalize a failing release.

### 7. Promote the Successful Release

Only after the exact release CI run succeeds, convert that attempt from a draft prerelease into the latest final release:

```bash
gh release edit "$new_tag" \
  --draft=false \
  --prerelease=false \
  --latest
```

Verify the final GitHub state rather than assuming the edit succeeded:

```bash
gh release view "$new_tag" \
  --json tagName,name,url,isDraft,isPrerelease,publishedAt
latest_tag="$(gh api "repos/${repo}/releases/latest" --jq .tag_name)"
```

Success requires:

- The tag and release commit exist remotely.
- The exact release CI run completed successfully.
- `isDraft` and `isPrerelease` are both `false`.
- `latest_tag` equals `new_tag`.
- The final release has a publication time and URL.
- The working copy is clean and local `main` matches `origin/main`.

If promotion or verification fails, investigate and retry only when the correction is mechanical and safe. Otherwise stop and report the release as not finalized.

## Final Report

After successful promotion, report:

- The final version, tag, release URL, and successful CI run URL.
- The previous final release tag, or that this was the first release.
- The changelog commit range.
- Confirmation that the release is non-draft, non-prerelease, and marked latest.
- Any transient CI failures and reruns.
- For every repository remediation: failed version and release URL, failed job and root cause, fix and changed files, local validation, fix commit, replacement version, replacement release URL, and successful CI URL.
- Any older failed draft prereleases intentionally retained for auditability.

Do not call the workflow complete or the release published until GitHub verification confirms the successful attempt is the latest final release. Do not omit automatically resolved CI problems from the report.
