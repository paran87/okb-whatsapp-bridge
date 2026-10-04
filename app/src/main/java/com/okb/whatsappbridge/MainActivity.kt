package com.okb.whatsappbridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.okb.whatsappbridge.ui.navigation.BridgeApp
import com.okb.whatsappbridge.ui.theme.OkbBridgeTheme

/**
 * Operations UI only. Monitoring, storage and sync do not depend on this Activity: it can be closed
 * at any time and the NotificationListenerService + WorkManager pipeline keeps running.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as OkbBridgeApplication).container
        setContent {
            OkbBridgeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BridgeApp(container)
                }
            }
        }
    }
}
