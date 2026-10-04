# Narrio agent instructions

Before changing this repository, read and follow [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md). It is the canonical development, testing, versioning, and release workflow.

These instructions apply only to work on Narrio. Keep Narrio workflow rules in this repository rather than user-wide agent instructions, skills, settings, or memory.

## Branches and pull requests

- Inspect the working tree and current branch first. Preserve unrelated changes and other worktrees; use an isolated branch/worktree when needed.
- Start ordinary work from updated `dev` on a dedicated feature, fix, docs, or chore branch. Do not implement changes directly on `dev` or `master`.
- Use Conventional Commits for commits and PR titles. Describe user-facing changes under `## Release notes` and actual checks under `## Validation`.
- Target ordinary change PRs at `dev`. Wait for **CI** and **PR policy**, then squash merge when merging is within the user's authorized scope.
- Promote `dev` to `master` with a **merge commit**, preserving feature commits for versioning and release notes. A `master` merge triggers stable release automation; perform it only within the user's authorized promotion/release scope. Do not request approval again when already authorized.
- After a promotion or hotfix, sync `master` back into `dev` through a checked PR with a **merge commit**. Never squash this history sync.
- Urgent stable hotfixes start from `master` on `hotfix/*`, use Conventional Commits, and target `master`; sync them back to `dev` afterward.
- Before merging, re-check the actual PR head and required checks. Use that verified head as the merge guard; new commits require renewed verification.
- Preserve persistent branches and release tags. Do not bypass or weaken branch/tag rules or required checks to get a change merged.

## Local development verification

Follow the [local verification policy](docs/DEVELOPMENT.md#local-development-verification) when choosing checks.

- Batch a coherent set of edits, then run the smallest checks that cover the changed behavior. Prefer focused JVM tests and compilation during iteration; documentation-only changes need no Android setup.
- Use an emulator when Android runtime behavior or visual interaction needs verification. Select the affected test class/method or user flow; reserve full local suites for broad changes or unresolved failures.
- Reuse an explicitly selected, already running project test emulator. Reset only the fixture/app state needed for isolation; use a fresh boot when startup, installation, or isolation is the behavior under test. Preserve devices and sessions used by other agents.
- Use incremental Gradle builds and its default local daemon. Reserve clean builds and optimized release builds for a demonstrated need or relevant release verification.
- Before the PR is ready, confirm that checks appropriate to the completed diff have passed, including screen inspection for UI changes. Reuse passing results while their relevant inputs are unchanged. Re-run affected checks when source, dependencies, fixtures, or build settings change; broaden checks when failures or shared behavior justify it. Report the actual commands, scope, and results.
- Complete all required CI against the current PR candidate. Focused local verification does not remove Android CI coverage or the documented release, physical-device, and live-provider checks. Scripted and AI-driven UI testing follow the same local test-selection policy.

## Versioning, signing, and verification

- Let release automation calculate versions and Android version codes. Do not make manual version-bump commits, move/delete release tags, or replace published release assets.
- Preserve both signing identities, stable application ID, and preview workflow run-number continuity. Stable Narrio must retain upgrade compatibility; Narrio Dev remains a separate app.
- Never commit credentials, keystores, `signing.properties`, account data, audio, or generated APKs. Use the configured GitHub environments for automated signing.
- Match local verification to the change and complete required CI. Use controlled tests for CI; report physical-device and live-provider checks separately and accurately.
- Inspect the current preview APK and generated version/notes before an authorized stable promotion. Follow the documented recovery procedure for a failed release; keep its original source and signing identity.
- Report only completed checks/publication as successful, with links to the relevant PR, run, or release. A queued/running job is still pending.
