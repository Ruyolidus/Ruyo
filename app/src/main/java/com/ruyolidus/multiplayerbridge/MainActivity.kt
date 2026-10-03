package com.ruyolidus.multiplayerbridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ruyolidus.multiplayerbridge.ui.BridgeApp
import com.ruyolidus.multiplayerbridge.ui.BridgeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BridgeTheme { BridgeApp() } }
    }
}
