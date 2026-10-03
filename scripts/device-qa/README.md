# Physical-phone media checks

This self-targeted, test-only instrumentation package controls only Narrio's Android media session. It has no Internet permission and does not read app storage, account credentials, or generated playback links. It preserves enabled accessibility services.

Build with `./scripts/device-qa/build.ps1`. The debug-signed helper is written under ignored `verification/private/device-qa/`; it is separate from the production APK.

Use an explicitly authorized connected device, record its original part, position, playback and network settings, then install the helper with `adb -s DEVICE install -t verification/private/device-qa/qa.apk`.

Examples:

```text
adb -s DEVICE shell am instrument -w -e action state app.narrio.deviceqa/.DeviceChecks
adb -s DEVICE shell am instrument -w -e action play -e wait 3000 app.narrio.deviceqa/.DeviceChecks
adb -s DEVICE shell am instrument -w -e action part -e index 1 app.narrio.deviceqa/.DeviceChecks
adb -s DEVICE shell am instrument -w -e action seek -e position 120000 app.narrio.deviceqa/.DeviceChecks
adb -s DEVICE shell am instrument -w -e action pause app.narrio.deviceqa/.DeviceChecks
```

Part indices are zero-based. Wait for the new part's duration/timeline before issuing an exact seek. Read the returned state rather than assuming a command completed: NONE=0, PAUSED=2, PLAYING=3, BUFFERING=6, ERROR=7. System headset-hook events can be exercised with `adb -s DEVICE shell cmd media_session dispatch headsethook`; this tests button handling, not a physical Bluetooth route change.

Restore the original playback position and settings after testing. Remove the helper with `adb -s DEVICE uninstall app.narrio.deviceqa`. The main app's instrumented tests use synthetic account fixtures and are unsuitable for an in-place test of a user's connected production account.
