// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import android.content.Context
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import org.json.JSONArray
import org.json.JSONObject

data class NoteImage(
    val uri: String,
    val label: String
)

data class SavedNote(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String = "",
    val content: String = "",
    val images: List<NoteImage> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
)

object NoteStorage {
    private const val PREF_SAVED_NOTES = "floating_saved_notes"

    fun saveNote(context: Context, note: SavedNote) {
        val notes = loadAllNotes(context).toMutableList()
        val idx = notes.indexOfFirst { it.id == note.id }
        if (idx >= 0) notes[idx] = note else notes.add(0, note)
        persistAll(context, notes)
    }

    fun loadAllNotes(context: Context): List<SavedNote> {
        val prefs = DeviceProtectedUtils.getSharedPreferences(context)
        val json = prefs.getString(PREF_SAVED_NOTES, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val imagesArr = obj.optJSONArray("images")
                val images = if (imagesArr != null) {
                    (0 until imagesArr.length()).map { j ->
                        val imgObj = imagesArr.getJSONObject(j)
                        NoteImage(
                            uri = imgObj.optString("uri", ""),
                            label = imgObj.optString("label", "")
                        )
                    }
                } else emptyList()
                SavedNote(
                    id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                    title = obj.optString("title", ""),
                    content = obj.optString("content", ""),
                    images = images,
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    fun deleteNote(context: Context, noteId: String) {
        val notes = loadAllNotes(context).filter { it.id != noteId }
        persistAll(context, notes)
    }

    private fun persistAll(context: Context, notes: List<SavedNote>) {
        val arr = JSONArray()
        notes.forEach { note ->
            arr.put(JSONObject().apply {
                put("id", note.id)
                put("title", note.title)
                put("content", note.content)
                if (note.images.isNotEmpty()) {
                    val imagesArr = JSONArray()
                    note.images.forEach { img ->
                        imagesArr.put(JSONObject().apply {
                            put("uri", img.uri)
                            put("label", img.label)
                        })
                    }
                    put("images", imagesArr)
                }
                put("createdAt", note.createdAt)
            })
        }
        DeviceProtectedUtils.getSharedPreferences(context)
            .edit()
            .putString(PREF_SAVED_NOTES, arr.toString())
            .apply()
    }
}
