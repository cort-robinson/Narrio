package app.narrio.deviceqa;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.SystemClock;
import org.json.JSONObject;

/** Test-only controller. Reads Narrio's session and never accesses account storage or media URLs. */
public final class DeviceChecks extends Instrumentation {
    private Bundle arguments;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        this.arguments = arguments;
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        UiAutomation automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        try {
            automation.adoptShellPermissionIdentity("android.permission.MEDIA_CONTENT_CONTROL");
            MediaSessionManager manager = (MediaSessionManager) getContext().getSystemService(Context.MEDIA_SESSION_SERVICE);
            MediaController controller = null;
            for (MediaController candidate : manager.getActiveSessions(null)) {
                if ("app.narrio".equals(candidate.getPackageName())) { controller = candidate; break; }
            }
            if (controller == null) throw new IllegalStateException("Narrio has no active media session");
            String action = arguments.getString("action", "state");
            MediaController.TransportControls controls = controller.getTransportControls();
            switch (action) {
                case "play": controls.play(); break;
                case "pause": controls.pause(); break;
                case "seek": controls.seekTo(Long.parseLong(arguments.getString("position"))); break;
                case "part": controls.skipToQueueItem(Long.parseLong(arguments.getString("index"))); break;
                case "next": controls.skipToNext(); break;
                case "previous": controls.skipToPrevious(); break;
                case "forward": controls.fastForward(); break;
                case "rewind": controls.rewind(); break;
                case "state": break;
                default: throw new IllegalArgumentException("Unknown test action");
            }
            long delay = Long.parseLong(arguments.getString("wait", "1000"));
            SystemClock.sleep(Math.min(Math.max(delay, 0), 30000));
            PlaybackState state = controller.getPlaybackState();
            MediaMetadata metadata = controller.getMetadata();
            JSONObject output = new JSONObject();
            output.put("package", controller.getPackageName());
            output.put("action", action);
            output.put("observedElapsedMs", SystemClock.elapsedRealtime());
            ConnectivityManager connectivity = (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities network = connectivity.getNetworkCapabilities(connectivity.getActiveNetwork());
            output.put("networkValidated", network != null && network.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
            if (state != null) {
                output.put("state", state.getState());
                output.put("positionMs", state.getPosition());
                output.put("bufferedMs", state.getBufferedPosition());
                output.put("speed", state.getPlaybackSpeed());
                output.put("partIndex", state.getActiveQueueItemId());
                output.put("updatedElapsedMs", state.getLastPositionUpdateTime());
                output.put("error", state.getErrorMessage());
            }
            if (metadata != null) {
                output.put("partTitle", metadata.getString(MediaMetadata.METADATA_KEY_TITLE));
                output.put("durationMs", metadata.getLong(MediaMetadata.METADATA_KEY_DURATION));
            }
            output.put("queueSize", controller.getQueue() == null ? 0 : controller.getQueue().size());
            result.putString("narrio_result", output.toString());
            automation.dropShellPermissionIdentity();
            finish(Activity.RESULT_OK, result);
        } catch (Exception exception) {
            automation.dropShellPermissionIdentity();
            result.putString("narrio_error", exception.getClass().getSimpleName() + ": " + exception.getMessage());
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
