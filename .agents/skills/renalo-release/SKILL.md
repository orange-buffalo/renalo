---
name: renalo-release
description: >
  Creates a Renalo release locally: verifies GitHub CLI and git state, updates
  the Gradle project version, builds the project, prepares release notes,
  pushes the release tag, and creates a draft GitHub release.
compatibility: "requires: gh CLI, git, Java 25, Bun 1.3+, Gradle wrapper"
---

# Renalo Release Skill

Use this skill when the user wants to create a new Renalo release, draft a GitHub release, publish release notes, or run the local release workflow.

This workflow is intentionally local and stateful. It may update `build.gradle.kts`, create a release commit and tag, build the project, push the release tag, and create a draft GitHub release.

## Preconditions

Run these checks before changing anything:

```bash
gh auth status
gh repo view --json nameWithOwner,url,defaultBranchRef
git rev-parse --is-inside-work-tree
git branch --show-current
git status --short
git fetch origin main --tags
```

Stop immediately if any condition is not met:

- `gh auth status` must show an authenticated GitHub CLI session.
- The current branch must be exactly `main`; releases are only supported from the main branch.
- The working copy must be clean, including no staged changes and no untracked files.
- `origin/main` must exist and be reachable.

After fetching, verify local `main` is current:

```bash
git status --branch --short
git rev-parse HEAD
git rev-parse origin/main
```

If `HEAD` differs from `origin/main`, stop and ask the user whether to pull, push, or abort. Do not release from a branch that is behind or ahead without explicit user approval.

## Release Workflow

Execute these steps in order. Report command failures verbatim and stop unless the remediation is explicitly listed.

### 1. Capture the Previous GitHub Release

Use GitHub releases as the source of the previously published version:

```bash
previous_release_json="$(gh release view --json tagName,name,url,createdAt,isDraft,isPrerelease 2>/dev/null || true)"
previous_tag="$(gh release view --json tagName --jq .tagName 2>/dev/null || true)"
previous_release_url="$(gh release view --json url --jq .url 2>/dev/null || true)"
```

If no GitHub release exists, fall back to the latest remote version tag:

```bash
if [[ -z "$previous_tag" ]]; then
  previous_tag="$(git tag --list 'v[0-9]*.[0-9]*.[0-9]*' --sort=-version:refname | head -n 1)"
fi
```

Rules:

- Prefer `gh release view` over git tags because the report must reference the latest GitHub release.
- Use the previous release tag as the exclusive lower bound for commit collection.
- If there is no previous GitHub release and no previous version tag, use the first repository commit as the lower bound and say this is the first release.
- Preserve the tag exactly as GitHub reports it, usually `vX.Y.Z`.

### 2. Determine the Release Version

Read the current project version from `build.gradle.kts`:

```bash
current_version="$(grep -E '^version = ' build.gradle.kts | head -n 1 | sed -E 's/^version = "(.*)"$/\1/')"
```

Rules:

- The repository currently uses a `-SNAPSHOT` development version in `build.gradle.kts`.
- If `current_version` ends with `-SNAPSHOT`, derive the release version by removing the suffix.
- If the version cannot be derived unambiguously, stop and ask the user for the intended release version.

### 3. Update Version

Update `build.gradle.kts` so `version = "<release-version>"`.

Do not run the local project build or image build in this workflow; CI is responsible for verifying the release commit and producing the container image.

### 4. Create the Release Commit and Tag

Commit the version change with a conventional commit message, then tag the release commit:

```bash
git add build.gradle.kts
git commit -m "release: v<release-version>"
git tag "v<release-version>"
```

Stop if the commit or tag creation fails.

### 5. Collect Commits for Release Notes

Collect commits between the previous release, exclusive, and current `HEAD`, inclusive.

If a previous release tag exists:

```bash
git log "${previous_tag}..HEAD" --no-merges --pretty=format:'%H%x09%s%x09%an'
```

If this is the first release:

```bash
git log --no-merges --pretty=format:'%H%x09%s%x09%an'
```

Also collect summary stats:

```bash
if [[ -n "$previous_tag" ]]; then
  git log "${previous_tag}..HEAD" --no-merges --pretty=format:'%H' | wc -l
  git shortlog -sn "${previous_tag}..HEAD"
  git diff --stat "${previous_tag}..HEAD"
else
  git log --no-merges --pretty=format:'%H' | wc -l
  git shortlog -sn HEAD
  git diff --stat "$(git rev-list --max-parents=0 HEAD)..HEAD"
fi
```

Commit collection rules:

- Exclude merge commits.
- Exclude bot or infrastructure-only authors from contributor-facing notes where appropriate: `[bot]`, `GitHub`, and `orange-buffalo`.
- Do not include uncategorized commits in the release notes unless they describe user-visible behavior.
- Keep commit hashes available for the final report, but release notes should be readable and not just a raw commit dump.

### 6. Build Release Notes

Create the release notes in a temp file:

```bash
notes_file="$(mktemp -t renalo-release-notes.XXXXXX.md)"
```

Use this structure:

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

Formatting rules:

- Start with `## What's Changed`.
- Use Markdown headings exactly as shown above.
- Use emoji-prefixed headings exactly as shown above.
- Keep `Dependency Updates` as the last section when present.
- Omit empty categories.
- Skip merge commits.
- Hide uncategorized commits by default.
- Prefer pull request references from commit subjects, e.g. `(#2623)`.
- Preserve issue or PR references when already present.
- Rewrite commit subjects into concise release-note entries when needed.
- Use sentence case and no trailing periods unless the entry contains multiple sentences.

Classification rules:

- `feat` commits go under `New Features`.
- `fix` commits go under `Bug Fixes`.
- `build` and `ci` commits go under `Build & CI`.
- `docs` commits go under `Documentation`.
- Dependabot-style `chore: bump ...` commits go under `Dependency Updates`.
- `refactor` commits go under `Refactorings`.
- `test` or `tests` commits go under `Tests`.
- `perf` commits go under `Bug Fixes` if they fix user-visible slowness, otherwise omit unless important.
- `chore` commits are omitted unless they are dependency updates or release-relevant maintenance.

When rewriting entries:

- Remove redundant conventional commit prefixes such as `feat:`, `fix:`, or `chore(deps):`.
- Keep the original technical meaning.
- Do not exaggerate impact.
- Do not invent features or fixes not supported by commits.
- If a commit is unclear, omit it rather than guessing.

Before creating the GitHub release, show the generated notes to the user and ask for confirmation. If the user requests changes, edit the temp file and show the updated notes before continuing.

### 7. Push the Release Commit and Tag

Push the release commit first, then the tag:

```bash
git push origin main
git push origin "v<release-version>"
```

Stop if either push fails. Do not create the GitHub release if the commit or tag push did not succeed.

### 8. Create the Draft GitHub Release

Create the release in draft state:

```bash
gh release create "v<release-version>" \
  --title "v<release-version>" \
  --notes-file "$notes_file" \
  --draft \
  --prerelease \
  --latest=false
```

Then fetch the release URL:

```bash
release_url="$(gh release view "v<release-version>" --json url --jq .url)"
```

### 9. Find the CI Build

After pushing, find the CI run for the release commit on `main`:

```bash
release_sha="$(git rev-parse HEAD)"
ci_url="$(gh run list --workflow build.yml --branch main --commit "$release_sha" --json url --jq '.[0].url' --limit 1)"
```

If no CI run is found, check whether the workflow file has not been picked up yet or whether the workflow name changed.

### 10. Final Report

When finished, report:

- The new version and tag.
- The previous release tag or note that this is the first release.
- The release URL.
- The CI run URL if found.
- The commit range covered by the release notes.

Do not claim the release is published unless the tag and draft release both exist remotely.
