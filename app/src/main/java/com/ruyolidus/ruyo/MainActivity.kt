package com.ruyolidus.ruyo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ruyolidus.ruyo.ui.RuyoApp
import com.ruyolidus.ruyo.ui.RuyoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { RuyoTheme { RuyoApp() } }
    }
}
