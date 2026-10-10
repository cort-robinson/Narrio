package app.narrio.data

import android.content.SharedPreferences
import app.narrio.domain.Audiobook
import org.junit.Assert.*
import org.junit.Test

class ListeningRecordingsTest {
    private val ray = Audiobook("book", "Project Hail Mary", "Andy Weir", "Ray Porter", recordingId = "ray", provider = "knaben")
    private val kim = Audiobook("book", "Project Hail Mary", "Andy Weir", "Kim Doe", recordingId = "kim", provider = "knaben")

    @Test fun anEarlierRecordingsAudioKeepsItsName() {
        val recordings = ListeningRecordings(MemoryPreferences())
        recordings.played(ray, "ray-audio")
        recordings.played(kim, "kim-audio")
        assertEquals("kim", recordings["book"]?.recordingId)
        // A bookmark in Ray's audio is Ray's recording, not unnamed audio.
        assertEquals("Ray Porter", recordings.recordingOf("book", "ray-audio")?.narrator)
        assertEquals("Kim Doe", recordings.recordingOf("book", "kim-audio")?.narrator)
        assertNull(recordings.recordingOf("book", "unknown-audio"))
        // Going back to Ray makes it current again without keeping it as an earlier one too.
        recordings.played(ray, "ray-audio")
        assertEquals("Kim Doe", recordings.recordingOf("book", "kim-audio")?.narrator)
        assertEquals("Ray Porter", recordings.recordingOf("book", "ray-audio")?.narrator)
    }
}

/** Just enough SharedPreferences for stores that keep strings. */
private class MemoryPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()
    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
    override fun getInt(key: String, defValue: Int) = defValue
    override fun getLong(key: String, defValue: Long) = defValue
    override fun getFloat(key: String, defValue: Float) = defValue
    override fun getBoolean(key: String, defValue: Boolean) = defValue
    override fun contains(key: String) = key in values
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        override fun putString(key: String, value: String?) = apply { values[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = this
        override fun putInt(key: String, value: Int) = this
        override fun putLong(key: String, value: Long) = this
        override fun putFloat(key: String, value: Float) = this
        override fun putBoolean(key: String, value: Boolean) = this
        override fun remove(key: String) = apply { values.remove(key) }
        override fun clear() = apply { values.clear() }
        override fun commit() = true
        override fun apply() {}
    }
}
