package app.narrio;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;

/** Framework-only opt-in verifier: never relies on classes renamed in the updated release. */
public class UpdateFixtureVerification extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle args) { super.onCreate(args); arguments = args; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Context context = getTargetContext();
            require("verify".equals(arguments.getString("updateStage")), "Explicit verification stage required");
            require("app.narrio.local".equals(context.getPackageName()), "Only the isolated Local fixture is allowed");
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            require((info.applicationInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0, "Use a signed release fixture");
            require(info.getLongVersionCode() == Long.parseLong(arguments.getString("updateCode")), "Installed code differs");
            require(info.versionName.equals(arguments.getString("updateVersion")), "Installed version differs");
            require("before-update".equals(context.getSharedPreferences("updateFixture", 0).getString("preserved", null)), "Preferences were not retained");
            try (SQLiteDatabase database = SQLiteDatabase.openDatabase(context.getDatabasePath("narrio.db").getPath(), null, SQLiteDatabase.OPEN_READONLY);
                 Cursor cursor = database.rawQuery("SELECT bookJson FROM shelf WHERE bookId = ?", new String[]{"update-fixture-book"})) {
                require(cursor.moveToFirst() && cursor.getString(0).contains("\"title\":\"Update fixture\""), "Library entry was not retained");
            }
            result.putString("stream", "PASS: installed release version, preferences, and Room shelf retained\n");
            finish(Activity.RESULT_OK, result);
        } catch (Exception error) {
            result.putString("stream", "FAIL: " + error.getMessage() + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }
    private static void require(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
}
