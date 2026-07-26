package com.dji.sdk.sample.tak

import android.content.Context
import com.taklite.client.tak.TakManager

/**
 * Shared "My Channels" persistence + pull, used by both [TakConnectActivity] (manual Pull
 * Channels button, checkbox UI) and [TakAutoConnect] (silent pull right after an auto-connect,
 * so Pre-Flight Setup already shows a fresh list when the pilot opens it — no button tap
 * needed). Two CSVs: which channels the pilot has CHECKED (routing), and the last-known full
 * list the server returned (what to show as checkboxes) — a channel checked once is kept in
 * the display list even if a later pull doesn't return it (matches the old pullChannels()
 * merge behavior in TakConnectActivity).
 */
object TakChannelsStore {
    private const val PREFS = "takpilot2_tak"
    private const val KEY_CHANNELS = "channels"
    private const val KEY_AVAILABLE = "channels_available"

    fun selected(context: Context): Set<String> = csv(context, KEY_CHANNELS).toSet()

    /** Last-known full list to render as checkboxes (server channels ∪ ever-selected ones). */
    fun displayList(context: Context): List<String> =
        (csv(context, KEY_AVAILABLE) + selected(context)).distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)

    fun saveSelected(context: Context, selected: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CHANNELS, selected.joinToString(",")).apply()
        TakManager.getInstance().setChannels(selected.toList())
    }

    fun clearAll(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_CHANNELS).remove(KEY_AVAILABLE).apply()
    }

    /** Pull from the server (no-ops if not connected) and persist the merged list.
     *  [onResult] always fires (with the cached list if not connected/pull failed). */
    fun pull(context: Context, onResult: (List<String>) -> Unit) {
        if (!TakManager.getInstance().isConnected) {
            onResult(displayList(context))
            return
        }
        TakMissionManager.listMyChannels { chans ->
            val merged = (chans + selected(context)).distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_AVAILABLE, merged.joinToString(",")).apply()
            onResult(merged)
        }
    }

    private fun csv(context: Context, key: String): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return (prefs.getString(key, "") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }
}
