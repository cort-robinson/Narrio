# In-app updater verification

October 3, 2026, feature branch based on dev `bf6fc5e`.

- All 83 JVM tests pass, including 11 updater policy/repository tests with controlled HTTP responses and streamed APK bytes.
- All 25 release-tooling tests pass. Workflow actionlint and `git diff --check` pass.
- Android debug and instrumentation builds pass. Lint reports zero errors, 50 warnings, and one hint; no warning suppressions were added.
- All five `AppUpdatesExperienceTest` cases pass on the API 36 emulator at compact phone, 1.5 system font scale, and expanded 1800 × 1440/density 240. They cover controls, permissions, playback gates, retry, local-build disabling, recreation, APK rejection, and older-OS copy using a forced capability flag.
- Twelve controlled native captures were reviewed; the finish verdict is [ship](../.impeccable/review/app-updates/finish-verdict.md). Forced older-OS copy is not an older-OS installation test.
- A signed non-debuggable `app.narrio.local` release at code 100/version 1.0.0 invoked the production `AppUpdateInstaller` with a code 101/version 1.0.1 R8 release APK signed by the same local test certificate. APK/package/signature verification and permission checks passed; the session requested no user action. Play Protect required a scan of the unfamiliar test APK, returned “This app looks safe,” and required its Install action. No security setting was disabled. PackageManager then reported code 101/version 1.0.1. The framework-only post-install verifier passed the installed-version, private-preference marker, and Room shelf-entry checks.

The initial install test's fixed 20-second replacement expectation failed while Play Protect awaited its scan. That expectation was corrected: submitting a session is separate from verifying replacement. This establishes actual package replacement with the system's confirmation fallback and retained data, not fully unattended installation on all devices. It does not exercise a physical phone, authenticated providers, a real older Android OS, or scheduled battery-dependent execution.

## Repeat the signed fixture

Use a disposable emulator selected explicitly in every ADB command. Supply local test signing through the existing `NARRIO_KEYSTORE_*`/`NARRIO_KEY_*` environment variables; never use or commit stable/preview signing keys. Keep APKs in ignored `verification/private/`.

1. Build the newer **R8 release** with `:app:assembleRelease -PnarrioLocal=true -PappVersionCode=101 -PappVersionName=1.0.1 -PrequireSigning=true`. Save its APK privately and push it to `/data/local/tmp/narrio-update-fixture.apk` on the selected disposable emulator.
2. Build the base and tests with `:app:assembleRelease :app:assembleReleaseAndroidTest -I scripts/verification/app-update-fixture.gradle -PnarrioLocal=true -PappVersionCode=100 -PappVersionName=1.0.0 -PrequireSigning=true`. The base is deliberately unminified and non-debuggable so AndroidJUnitRunner can run; the newer target retains normal R8 optimization. Install the base and test APKs. Allow only this Local fixture to request package installation using Android's per-app setting.
3. Run `adb -s <serial> shell am instrument -w -e class app.narrio.AppUpdateInstallTest -e updateStage install -e updateApk /data/local/tmp/narrio-update-fixture.apk -e updateCode 101 -e updateVersion 1.0.1 app.narrio.local.test/androidx.test.runner.AndroidJUnitRunner`. Complete any system scan/confirmation normally. Process termination during successful self-replacement is expected; the request-stage runner alone cannot establish success.
4. Rebuild only `:app:assembleReleaseAndroidTest` with the same base arguments/script plus `-PfixtureVerifier=true`; install only that test APK. Run `adb -s <serial> shell am instrument -w -e updateStage verify -e updateCode 101 -e updateVersion 1.0.1 app.narrio.local.test/app.narrio.UpdateFixtureVerification`. Require its explicit `PASS` result and independently check PackageManager's version.
5. Remove the disposable emulator overlay and temporary APK. Do not install fixture builds onto the user's physical phone or count this as stable/preview signing acceptance.

PowerShell callers should quote dotted `-PappVersionName=...` arguments when invoking `gradlew.bat`. The opt-in fixture is excluded from normal CI; controlled updater UI tests are included in the required native job.

## Published preview and first-launch correction

PR #28 merged as `155148f`; [dev-64](https://github.com/cort-robinson/Narrio/releases/tag/dev-64) and its [original workflow](https://github.com/cort-robinson/Narrio/actions/runs/37151632027) completed successfully. Downloaded APK and manifest checksums match, package/code are `app.narrio.dev`/64, and the APK retains the preview signing certificate. The published R8 APK launches without a crash and its manual check returns “You're up to date.” Stable remains v1.4.2.

That startup check exposed duplicate lifecycle delivery: attaching an observer replays ON_START, then synchronizing the current lifecycle state canceled the newly started update request after advancing its attempt throttle. Repeated visibility states now refresh permission/playback controls without canceling or restarting the job; actual foreground/background transitions retain their debounce and cancellation.

A fresh, non-debuggable R8 Dev-channel fixture at code 10000/version 1.5.0-dev.10000, signed only with the local test key and installed only in a disposable read-only API 36 emulator, automatically returned “You're up to date” on its first launch without tapping Check for updates. The high fixture code prevents downloads of real published previews. It is not a published version or a signing-identity acceptance test. No production versions or release assets changed. This small correction receives its own checked dev PR.
