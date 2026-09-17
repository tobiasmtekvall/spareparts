package se.spareparts.inventory

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.spareparts.inventory.ui.AppRoot
import se.spareparts.inventory.ui.theme.SparePartsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val c = container
        setContent {
            val settings by c.settings.settings.collectAsStateWithLifecycle()
            SparePartsTheme(themeMode = settings.theme) {
                AppRoot(c)
            }
        }
    }
}
