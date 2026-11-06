package tv.lumen.app

import android.app.Application
import tv.lumen.app.data.DiagLog
import tv.lumen.app.data.Repository
import tv.lumen.app.data.Store

class LumenApp : Application() {
    lateinit var store: Store
        private set
    lateinit var repo: Repository
        private set

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        repo = Repository(this, store)
        DiagLog.log("App started (${BuildInfo.version(this)})")
        repo.start()
    }
}

object BuildInfo {
    fun version(app: Application): String = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")
}
