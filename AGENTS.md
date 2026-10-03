# Narrio agent instructions

Before changing this repository, read and follow [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md). It is the canonical development, testing, versioning, and release workflow.

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

## Versioning, signing, and verification

- Let release automation calculate versions and Android version codes. Do not make manual version-bump commits, move/delete release tags, or replace published release assets.
- Preserve both signing identities, stable application ID, and preview workflow run-number continuity. Stable Narrio must retain upgrade compatibility; Narrio Dev remains a separate app.
- Never commit credentials, keystores, `signing.properties`, account data, audio, or generated APKs. Use the configured GitHub environments for automated signing.
- Match local verification to the change and complete required CI. Use controlled tests for CI; report physical-device and live-provider checks separately and accurately.
- Inspect the current preview APK and generated version/notes before an authorized stable promotion. Follow the documented recovery procedure for a failed release; keep its original source and signing identity.
- Report only completed checks/publication as successful, with links to the relevant PR, run, or release. A queued/running job is still pending.
