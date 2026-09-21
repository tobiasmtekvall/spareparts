package se.spareparts.inventory

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import se.spareparts.inventory.data.ConnectivityMonitor
import se.spareparts.inventory.data.Repository
import se.spareparts.inventory.data.SessionManager
import se.spareparts.inventory.data.SettingsStore
import se.spareparts.inventory.work.SyncWorker

class AppContainer(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsStore(context, scope)

    /** Read once, before the first frame, so we know whether to show the sign-in screen. */
    val session = SessionManager(settings).also { runBlocking { it.load() } }

    val connectivity = ConnectivityMonitor(context)
    val repository = Repository(
        filesDir = context.filesDir,
        settings = settings,
        session = session,
        connectivity = connectivity,
        scope = scope,
        scheduleFlush = { SyncWorker.schedule(context) },
    )
}

class SparePartsApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Pull on app start and every time the app returns to the foreground.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                container.scope.launch {
                    if (!container.session.signedIn) return@launch
                    container.repository.sync()
                    if (container.repository.pending.value.isNotEmpty()) SyncWorker.schedule(this@SparePartsApp)
                }
            }
        })
    }
}

val Context.container: AppContainer get() = (applicationContext as SparePartsApp).container
