# Development and releases

`master` contains stable Narrio. `dev` is the integration and testing branch. GitHub Actions checks every pull request and every push to these branches. A successful `master` push publishes a stable release when the included changes require a version increment.

## A feature or fix

1. Update `dev`, then create `feature/<name>` or `fix/<name>` from it.
2. Open a pull request targeting `dev`. Use a Conventional Commit title describing the resulting behavior, such as `feat(metadata): show audiobook descriptions`.
3. Fill in the release-note and validation sections. Keep credentials, account data, audio files, signing keys, and generated APKs out of Git.
4. Wait for **CI** and **PR policy** to pass, then **squash merge**. The PR title and body become one release commit. Delete only the completed feature branch; retain `dev`.

```powershell
git switch dev
git pull --ff-only
git switch -c feature/my-feature
# Make and verify the change, then commit using Conventional Commits.
git push -u origin feature/my-feature
gh pr create --base dev
```

PR policy checks Conventional Commit titles and meaningful release-note summaries. CI runs JVM tests, Android lint/build checks, release-tooling tests, and nine emulator smoke tests for persistence, encrypted credential removal, link renewal, source choices, offline settings, follow-along interaction/source binding, and the text-schema migration. These required tests use controlled fixtures; live provider and listening checks remain separate because provider availability should not decide whether a release can build.

## Test a development build

After a successful push to `dev`, download **Narrio-Dev-<run>** from that run's Actions artifacts. It contains a signed `Narrio-<version>-dev.<run>.apk`, checksums, a release manifest, and proposed release notes.

**Narrio Dev** has application ID `app.narrio.dev`, its own persistent signing identity, and separate local data. It installs alongside stable Narrio. Its Android version code is the workflow run number, so newer previews can update older previews. Keep this workflow's identity when changing the pipeline; resetting its run-number sequence would require a planned preview-version migration.

Check the current candidate on a phone: discovery/details, streaming, shelf/resume, offline downloads, and relevant layouts. Check real provider behavior where the change affects it. Successful emulator checks do not establish physical-device or provider acceptance.

## Release stable Narrio

1. Open a **`dev` → `master`** PR titled `chore(release): promote dev to master`.
2. Review **Proposed release** in the build job summary or `build-reports/release-preview.md`. The preview and publication use the same commit analyzer and release-note configuration.
3. Review/test the current candidate. New commits on `dev` change the candidate; repeat the affected checks before merging.
4. **Create a merge commit.** Do not squash or rebase this promotion: individual feature commits drive the version and release notes. The stable branch's ruleset allows only merge commits.
5. The `master` push repeats CI for the final source commit, builds the signed release APK, checks its version/application ID/certificate, uploads assets to a draft, verifies their bytes, then publishes the GitHub release.
6. Open a **`master` → `dev`** PR titled `chore: sync master into dev`, wait for checks, and merge it with a merge commit. This retains shared history, including hotfixes.

The APK attached to the release is the verified build; publication does not rebuild it. Signing files are removed from the runner after the job. Stable jobs are serialized and are not canceled when a newer commit arrives. GitHub may replace an older queued run with a newer queued run; the next release includes all unreleased commits.

## Versions and notes

| Commit | Result |
| --- | --- |
| `feat:` | Minor version |
| `fix:` or `perf:` | Patch version |
| `!` or a `BREAKING CHANGE:` footer | Major version |
| Only `chore:`, `docs:`, `test:`, `ci:`, `build:`, or `style:` | No stable release |

The largest required increment wins when several changes ship together. Notes group features, bug fixes, performance changes, and breaking changes, with commit/PR links. Write titles for the person using the app, and include upgrade instructions in a breaking-change footer. A promotion's maintenance title does not hide its feature commits.

Stable Git tags (`v<major>.<minor>.<patch>`) are the version history. CI injects `versionName` and `versionCode` into Gradle; do not edit app version numbers manually. Stable Android codes use `major * 1,000,000 + minor * 1,000 + patch`, keeping patch/minor releases increasing and continuing above the earlier codes 1–3. Minor and patch components must remain below 1,000, and Android codes must not exceed 2,100,000,000; automation rejects overflow. Local builds default to the latest tag plus `-dev`.

The pre-automation `v1.1.0` tag records the previously shipped baseline. It intentionally has no automated GitHub release. The metadata improvements shipped in `v1.2.0`.

To preview a release locally, use Node 22.14+ or supported Node 24.10+:

```powershell
npm ci --ignore-scripts
npm test
npm run release:preview
```

## Hotfixes and failed releases

Create `hotfix/<name>` from `master` for an urgent stable fix. Make its actual commits Conventional Commits (for example `fix(player): retain the listening position`), open a PR to `master`, pass checks, and merge with a merge commit. Test the affected behavior before merging. Merge `master` into `dev` afterward so the fix remains in future versions. Ordinary features target `dev`.

If CI or signing fails, nothing is published. Correct credential/configuration or transient failures and retry the failed job. A failure after tagging/uploading can leave an unpublished tag or draft. **Re-run that original workflow**: automation rebuilds the same source/version, replaces only unpublished draft assets, verifies the uploads, and finalizes it. A newer commit cannot recover an older source's draft; complete the older run first, then retry the newer run.

If the publication code itself is broken, rerunning its old commit repeats the bug. Fix the tooling through a checked PR. For an already verified draft, retrieve the original workflow artifacts, independently verify the APK signature/package/version/checksums and protected tag's source commit, then use the corrected finalizer against that draft's release ID. It must compare all uploaded bytes before publication. Re-run the original failed job afterward to confirm published-version retries are harmless. Keep the original tag and APK source; never substitute newer application code into an existing version.

Published versions and assets are never replaced. For a bad published release, ship a new fix/revert commit and higher version.

## Repository configuration

- Both persistent branches require **CI** and **PR policy**, an up-to-date PR, and resolved conversations. Force pushes, branch deletion, and direct pushes are blocked, including for the owner. No additional reviewer is required for this solo-owner repository; merging the promotion is the release approval.
- `master` allows merge commits; `dev` permits squash merges for features and merge commits for history synchronization. PR policy allows only `dev` or same-repository `hotfix/*` promotions to `master`.
- The **release** environment allows only branch `master`; **preview** allows only `dev`. Each stores its own `SIGNING_KEYSTORE_BASE64`, `SIGNING_KEYSTORE_PASSWORD`, `SIGNING_KEY_ALIAS`, and `SIGNING_KEY_PASSWORD` secrets, plus public variable `SIGNING_CERT_SHA256`.
- The stable environment contains the original Narrio signing identity, preserving in-place upgrades and local app data. Keep a private backup of the ignored `.signing/` directory and `signing.properties`. The preview identity is independent.
- PR jobs receive read-only repository access and no signing secrets. The metadata-only `pull_request_target` policy job never checks out or executes PR code. Only stable publication receives `contents: write`.
- Action revisions and release dependencies are pinned. Update them through a tested PR to `dev`; keep a compatible Conventional Commits preset for the notes generator.

Public source and public release assets do not expose environment secrets. The pipeline publishes GitHub APK releases; it does not publish to Google Play or implement in-app updates.
