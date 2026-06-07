package com.opencode.android.workspace

/** Rules for which bridge entries participate in SAF sync. */
object SafSyncPolicy {
    /** Bridge-only metadata; never synced to or from the external folder. */
    private val INTERNAL_NAMES = setOf(
        ".bridge-sync-state.json",
    )

    fun shouldSync(name: String): Boolean {
        if (name.isBlank() || name == "." || name == "..") return false
        return name !in INTERNAL_NAMES
    }
}
