package app.narrio.data

import android.content.SharedPreferences
import app.narrio.domain.AppearanceCodec
import app.narrio.domain.AppearanceSettings

class AppearanceStore(private val preferences: SharedPreferences) {
    fun read() = AppearanceCodec.decode(preferences.getString(KEY, null), preferences.getString("theme", null))

    fun save(settings: AppearanceSettings) {
        preferences.edit().putString(KEY, AppearanceCodec.encode(settings)).remove("theme").apply()
    }

    private companion object { const val KEY = "appearance.v1" }
}
