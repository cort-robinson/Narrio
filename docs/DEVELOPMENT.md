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

PR policy checks Conventional Commit titles and meaningful release-note summaries. CI runs JVM tests, Android lint/build checks, release-tooling tests, and emulator smoke tests for persistence, encrypted credential removal, link renewal, metadata-only book details and source selection, source choices, closing Now playing, offline settings, follow-along interaction/source binding, and the text-schema migration. These required tests use controlled fixtures; live provider and listening checks remain separate because provider availability should not decide whether a release can build.

### Local development verification

This policy applies to agents working on Narrio. Keep it in this repository; it does not belong in user-wide agent instructions, skills, settings, or memory.

During development, batch edits that address one behavior, then choose the smallest checks that can establish whether that behavior works. Use focused local checks during iteration; CI runs the complete required suite. Select checks from this table according to the changed behavior:

| Changed behavior | Local verification |
| --- | --- |
| Documentation or agent instructions | Review the diff, links, and whitespace. Validate affected commands/examples when they change; no Android setup for prose-only edits. |
| App wording or resources | Compile/build the affected variant and inspect the changed screen when layout or behavior could be affected. |
| Parsing, matching, or provider logic | Run the affected JVM tests. Add a targeted Android check when SDK behavior can differ from the desktop JVM, such as Android regex handling. |
| UI, layout, or interaction | Build/compile the affected variant, inspect the changed screen, and run the relevant UI test or user flow on a reused test device. |
| Playback, storage, migrations, permissions, or updates | Run the related JVM tests and selected Android regression cases on a controlled test device. |
| Dependencies, build configuration, or packaging | Verify the affected build and related tests/lint once the change is coherent. Broaden local checks for shared effects; verify optimized release behavior when shrinking, signing, or packaging is affected. |
| CI or release tooling | Run the relevant tooling tests and workflow validation. Use Android checks when the changed tooling affects the Android build or device behavior. |

Prefer incremental builds and the Gradle wrapper's default local daemon. Reserve `clean`, `--no-daemon`, and optimized release builds for a demonstrated need or the relevant release checks. Choose the appropriate command rather than running every example below:

```powershell
# A focused JVM regression; no emulator is needed.
.\gradlew.bat :app:testDebugUnitTest --tests "app.narrio.data.BookSourceDiscoveryTest"

# Compile Kotlin changes for Narrio Local without installing or running it.
.\gradlew.bat :app:compileDebugKotlin -PnarrioLocal=true
```

For instrumentation, select the affected class or method with `-Pandroid.testInstrumentationRunnerArguments.class=<class>` or `<class>#<method>`, as the CI smoke job does. For example, Android source matching uses `app.narrio.BookSourceDiscoveryAndroidTest`. Bind the run to an explicitly selected, isolated project test device; keep synthetic fixture data separate from the user's stable and signed Dev installs.

Reuse a booted project test emulator across iterations. Reset fixture/app state only when the test requires isolation, and use a fresh-device boot for startup, installation, or clean-state checks. Preserve devices and sessions used by other agents. Do not start a new emulator or run the full device suite merely because another file was edited or a task is finishing. Scripted and AI-driven UI testing use the same policy: exercise the affected flow or a planned QA milestone.

Before marking a PR ready, inspect the completed diff and ensure the checks selected above have passed, including screen inspection for UI changes. Reuse passing results while their relevant inputs are unchanged. A passing result applies to the source, dependencies, fixtures, and configuration it covered; later changes invalidate the affected results. Repeat or broaden checks when those inputs change, failures remain unexplained, or shared behavior could be affected. Report the commands, selected cases/device, and actual results; distinguish focused checks from a full suite.

The full required suite still runs in CI for code-related changes against the current PR candidate. Broader local suites are appropriate for changes with shared effects or unresolved concerns. Required CI, the verified-head merge guard, and the release, phone, and live-provider checks documented below still apply.

### CI runtime

The build/lint/unit-test and Android smoke jobs run in parallel. The smoke job restores a clean API 35 Pixel 6 emulator snapshot when available. Only trusted branch pushes save snapshots, before installing the app or test APK; test runs do not save their device state. The cache key includes the runner platform and workflow configuration. On a cache miss, a trusted branch push boots a clean device, saves its snapshot, then runs the tests; that first run can take longer. PRs and manual runs with no matching cache boot a fresh device directly for testing. After SDK/emulator compatibility changes, increment the cache-key generation to invalidate old snapshots.

PRs targeting `dev` that change only `README.md`, `AGENTS.md`, or Markdown files under `docs/` skip Android setup, compilation, lint, and emulator tests. Release-tooling and CI path-selection tests still run, and the required **CI** check explicitly verifies the intentional skip. Unknown files, empty/failed comparisons, workflow/build/tooling changes, PRs to `master`, branch pushes, and manual runs execute the full Android suite. The workflow itself always runs, so documentation PRs do not leave required checks pending. No smoke tests are removed.

To inspect runtime, open the linked Actions run and expand **Android smoke tests**. **Restore clean emulator snapshot** shows whether the cache was found; **Run Android smoke tests** contains emulator boot, build/install, and test timings. Compare cache-hit runs separately from cold runs, and use the overall **CI** completion time when judging PR wait time.

## Test a development build

Bookmark [Narrio Dev downloads](https://github.com/cort-robinson/Narrio/releases?q=dev-&expanded=true) on your phone. Open the newest **Narrio Dev** prerelease, tap **Download Narrio Dev APK**, and open the downloaded APK. Allow your browser or Files app to install unknown apps if Android asks. No USB connection, GitHub login, or ZIP extraction is needed.

Every push to `dev` starts the signed preview build in parallel with the build/lint/unit tests and emulator smoke tests. The APK becomes available as soon as its build and upload verification finish. Required PR checks and the stable release gate still wait for the complete test suite.

Each preview has its own `dev-<run>` tag, source commit, checksums, and release manifest. Published preview APKs and tags are never replaced or moved, and previews never become the latest stable release. Release notes show **CI: Pending** initially, then **Passed**, **Failed**, or **Incomplete** after the checks finish. A superseded/cancelled workflow may leave its initial status in place; the linked workflow is authoritative. These statuses describe automated checks, not phone or live-provider acceptance.

The **Narrio-Dev-<run>** Actions artifact remains available as a fallback for 30 days. It also contains the proposed stable release notes. Downloading Actions artifacts requires GitHub sign-in; public prerelease APK links do not. If publication fails, rerun **Publish phone preview** using that run's original artifact. A retry verifies published bytes without replacing them; an incomplete draft can repair its uploads before publication. Rerunning the APK build can produce different bytes and cannot overwrite a published preview. Push a new checked change for a new preview instead.

**Narrio Dev** has application ID `app.narrio.dev`, its own persistent signing identity, and separate local data. It installs alongside stable Narrio. Its Android version code is the workflow run number, so newer previews can update older previews. Keep this workflow's identity when changing the pipeline; resetting its run-number sequence would require a planned preview-version migration.

Check the current candidate on a phone: discovery/details, streaming, shelf/resume, offline downloads, and relevant layouts. Check real provider behavior where the change affects it. Successful emulator checks do not establish physical-device or provider acceptance.

### In-app updates

Install a signed APK containing the updater once. Earlier installations cannot acquire updater code without that initial manual update. Stable Narrio checks normal GitHub releases; Narrio Dev checks only `dev-<run>` prereleases whose original `dev` push workflow completed successfully. The installed application ID fixes the channel. Debug and Narrio Local builds do not check for updates.

In **Settings → App updates**, automatic updates are on by default and can be disabled. Background checks are scheduled about hourly for Dev and every 12 hours for stable, subject to Android's battery/network scheduling. Opening the app also checks when the last attempt is old enough (10 minutes for Dev, six hours for stable). Downloads wait for an unmetered connection; **Download update** explicitly permits the current connection, including mobile data.

Tap **Allow app updates** in Settings and enable Android's permission for Narrio to install its own updates. You can do this before an update is available. Automatic installation runs only while Narrio is closed and playback is neither playing nor buffering, on Android 12+. Android may still require confirmation: use the update notification or Settings to finish. Older Android versions use the explicit **Install update** button and the system confirmation. Installation updates the existing signed package and retains its app data; it does not switch channels or uninstall the app.

The updater checks the manifest against the installed signing certificate, channel/version code, protected release URL, asset size, and source/run metadata. It verifies downloaded bytes, the APK package/version, non-debuggable build, and signing certificate before opening a PackageInstaller session. Interrupted/corrupt downloads never install. No provider credentials, audio, or listening history are sent to the update service. GitHub receives the usual network request and app-version user agent.

Local regression tests cover channel separation, downgrade/signature/asset rejection, full-CI preview selection, bounded downloads, corrupted caches, permission/playback controls, and disabled local builds. Controlled emulator checks do not establish automatic installation on a particular physical phone; report that separately. Preserve signing identities, release asset immutability, and preview run-number continuity when changing this code.

### Fast local testing over Wi-Fi

For rapid iterations at home, Android 11+ supports [wireless ADB pairing](https://developer.android.com/tools/adb#connect-to-a-device-over-wi-fi). The phone and PC must share the same Wi-Fi network. Enable Developer options and Wireless debugging on the phone, choose **Pair device with pairing code**, then run the following with SDK platform-tools on your PATH:

```powershell
adb pair <phone-ip>:<pairing-port>
# Enter the pairing code when prompted. Pairing and connection ports can differ.
adb connect <phone-ip>:<connection-port>
adb devices
```

Pairing persists until revoked, but reconnection may be needed when the network/port changes. Use the IP and connection port on the Wireless debugging screen. Build and install **Narrio Local** on the explicitly selected phone:

```powershell
.\gradlew.bat :app:assembleDebug -PnarrioLocal=true
adb -s <phone-ip>:<connection-port> install -r app/build/outputs/apk/debug/app-debug.apk
```

**Narrio Local** uses `app.narrio.local` and your local Android debug key. It installs separately from stable Narrio and signed Narrio Dev, with its own credentials, shelf, and progress. Repeated local installs retain its data when the same debug key is used. Do not install an ordinary debug APK over either signed app. `narrioLocal` and `narrioPreview` are mutually exclusive; cloud previews continue to use their original signing environment and run-number sequence. Wireless pairing itself is a phone setup step; documenting or building this variant does not establish device-test results.

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

If stable CI or signing fails, no stable release is published. Development APKs can be published while tests are pending or failing, with their status shown. Correct credential/configuration or transient failures and retry the failed job. A failure after tagging/uploading can leave an unpublished tag or draft. **Re-run that original stable workflow**: automation rebuilds the same source/version, replaces only unpublished draft assets, verifies the uploads, and finalizes it. A newer commit cannot recover an older source's draft; complete the older run first, then retry the newer run.

If the publication code itself is broken, rerunning its old commit repeats the bug. Fix the tooling through a checked PR. For an already verified draft, retrieve the original workflow artifacts, independently verify the APK signature/package/version/checksums and protected tag's source commit, then use the corrected finalizer against that draft's release ID. It must compare all uploaded bytes before publication. Re-run the original failed job afterward to confirm published-version retries are harmless. Keep the original tag and APK source; never substitute newer application code into an existing version.

Published versions and assets are never replaced. For a bad published release, ship a new fix/revert commit and higher version.

## Repository configuration

- Both persistent branches require **CI** and **PR policy**, an up-to-date PR, and resolved conversations. Force pushes, branch deletion, and direct pushes are blocked, including for the owner. No additional reviewer is required for this solo-owner repository; merging the promotion is the release approval.
- `master` allows merge commits; `dev` permits squash merges for features and merge commits for history synchronization. PR policy allows only `dev` or same-repository `hotfix/*` promotions to `master`.
- The **release** environment allows only branch `master`; **preview** allows only `dev`. Each stores its own `SIGNING_KEYSTORE_BASE64`, `SIGNING_KEYSTORE_PASSWORD`, `SIGNING_KEY_ALIAS`, and `SIGNING_KEY_PASSWORD` secrets, plus public variable `SIGNING_CERT_SHA256`.
- The stable environment contains the original Narrio signing identity, preserving in-place upgrades and local app data. Keep a private backup of the ignored `.signing/` directory and `signing.properties`. The preview identity is independent.
- PR jobs receive read-only repository access and no signing secrets. The metadata-only `pull_request_target` policy job never checks out or executes PR code. Stable publication and the trusted `dev` preview publication/status jobs receive `contents: write`. Preview publication/status jobs use the original verified artifact and receive no signing secrets; the preview build remains read-only.
- Action revisions and release dependencies are pinned. Update them through a tested PR to `dev`; keep a compatible Conventional Commits preset for the notes generator.

Public source and public release assets do not expose environment secrets. The pipeline publishes GitHub APK releases; the installed app checks its own release channel using those public assets. It does not publish to Google Play.
