// SPDX-License-Identifier: MIT
package pl.kyvaith.velvet

import android.app.Application
import pl.kyvaith.velvet.data.NetflixRepository
import pl.kyvaith.velvet.data.SessionStore
import pl.kyvaith.velvet.diagnostics.AppLog

class VelvetApplication : Application() {
    val sessions by lazy { SessionStore(this) }
    val netflix by lazy { NetflixRepository(sessions) }
    override fun onCreate() {
        super.onCreate()
        AppLog.install(this)
    }
}
