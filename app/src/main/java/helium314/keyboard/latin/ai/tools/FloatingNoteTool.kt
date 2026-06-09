// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai.tools

import android.content.Intent
import helium314.keyboard.latin.ai.AiToolRegistry.ToolResult
import helium314.keyboard.latin.ai.FloatingNoteService
import org.json.JSONArray
import org.json.JSONObject

/**
 * AI Tool to spawn a persistent floating note window with custom location,
 * scale, spawning delay, and camouflage behavior.
 */
class FloatingNoteTool : AiTool {

    override val name = "spawn_floating_note"

    override val description =
        "Spawn a persistent floating text note window on the screen. " +
        "Position, scale, spawning delay, and optional camouflage behavior are configurable. " +
        "Notes can be saved and loaded. Images can be attached via gallery picker."

    override val gate = ToolGate.ACTIONS

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("text", JSONObject().apply {
                put("type", "string")
                put("description", "Initial text content of the note.")
            })
            put("x", JSONObject().apply {
                put("type", "integer")
                put("description", "Initial X coordinate offset (default 100).")
            })
            put("y", JSONObject().apply {
                put("type", "integer")
                put("description", "Initial Y coordinate offset (default 200).")
            })
            put("width", JSONObject().apply {
                put("type", "integer")
                put("description", "Width of the floating note in dp (default 250).")
            })
            put("height", JSONObject().apply {
                put("type", "integer")
                put("description", "Height of the floating note in dp (default 200).")
            })
            put("delayMs", JSONObject().apply {
                put("type", "integer")
                put("description", "Delay in milliseconds before spawning the note (default 0).")
            })
            put("camouflageDurationMs", JSONObject().apply {
                put("type", "integer")
                put("description", "Duration in milliseconds after which the note becomes transparent (camouflage) until clicked again (default 0 / disabled).")
            })
            put("noteId", JSONObject().apply {
                put("type", "string")
                put("description", "Optional ID of a previously saved note to load. If omitted, a new empty note is created.")
            })
        })
    }

    override fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val text = args.optString("text", "")
        val x = args.optInt("x", 100)
        val y = args.optInt("y", 200)
        val width = args.optInt("width", 250)
        val height = args.optInt("height", 200)
        val delayMs = args.optInt("delayMs", args.optInt("delay_ms", 0))
        val camouflageDurationMs = args.optInt("camouflageDurationMs", args.optInt("camouflage_duration_ms", 0))
        val noteId = args.optString("noteId", "")

        // If noteId is provided, load the saved note's content
        var resolvedText = text
        if (noteId.isNotEmpty() && text.isEmpty()) {
            val savedNotes = helium314.keyboard.latin.ai.NoteStorage.loadAllNotes(ctx.appContext)
            val savedNote = savedNotes.firstOrNull { it.id == noteId }
            if (savedNote != null) {
                resolvedText = savedNote.content
            }
        }

        return try {
            val intent = Intent(ctx.appContext, FloatingNoteService::class.java).apply {
                putExtra("text", resolvedText)
                putExtra("x", x)
                putExtra("y", y)
                putExtra("width", width)
                putExtra("height", height)
                putExtra("delayMs", delayMs)
                putExtra("camouflageDurationMs", camouflageDurationMs)
            }
            ctx.appContext.startService(intent)

            ToolResult("Floating note successfully spawned at ($x, $y) with size ${width}x${height}dp.")
        } catch (e: Exception) {
            ToolResult("Failed to spawn floating note: ${e.message}", isError = true)
        }
    }
}
