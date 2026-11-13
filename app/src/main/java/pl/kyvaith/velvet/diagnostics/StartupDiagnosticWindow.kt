// SPDX-License-Identifier: MIT
package pl.kyvaith.velvet.diagnostics

/** Bounded admissions, with completion retained for loads started before the player became ready. */
internal class StartupDiagnosticWindow(
    private val loadLimit: Int = 16,
    private val errorLimit: Int = 24,
) {
    private val admittedLoads = mutableSetOf<Long>()
    private var loadAdmissions = 0
    private var errorAdmissions = 0
    private var trackAdmissions = 0
    private var formatAdmissions = 0
    private var drmAdmissions = 0
    var reachedReady = false
        private set
    var suppressedCount = 0L
        private set

    fun beginLoad(taskId: Long): Boolean {
        if (taskId in admittedLoads) return false // A retry belongs to the same load task.
        if (reachedReady || loadAdmissions >= loadLimit) return suppress()
        loadAdmissions++
        admittedLoads.add(taskId)
        return true
    }

    fun finishLoad(taskId: Long): Boolean = admittedLoads.remove(taskId)

    fun markReady(): Boolean {
        if (reachedReady) return false
        reachedReady = true
        return true
    }

    fun allowError(): Boolean {
        if (errorAdmissions >= errorLimit) return suppress()
        errorAdmissions++
        return true
    }

    fun allowTracks(): Boolean {
        if (reachedReady || trackAdmissions >= 6) return suppress()
        trackAdmissions++
        return true
    }

    fun allowFormat(): Boolean {
        if (reachedReady || formatAdmissions >= 12) return suppress()
        formatAdmissions++
        return true
    }

    fun allowDrmEvent(): Boolean {
        if (drmAdmissions >= 12) return suppress()
        drmAdmissions++
        return true
    }

    private fun suppress(): Boolean {
        suppressedCount++
        return false
    }
}
