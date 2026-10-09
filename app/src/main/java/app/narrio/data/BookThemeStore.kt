package app.narrio.data

import android.content.SharedPreferences
import app.narrio.domain.BookThemes
import app.narrio.domain.BookThemesCodec

class BookThemeStore(private val preferences: SharedPreferences) {
    fun read() = BookThemesCodec.decode(preferences.getString(KEY, null))

    fun save(themes: BookThemes) {
        preferences.edit().putString(KEY, BookThemesCodec.encode(themes)).apply()
    }

    private companion object { const val KEY = "bookThemes.v1" }
}
