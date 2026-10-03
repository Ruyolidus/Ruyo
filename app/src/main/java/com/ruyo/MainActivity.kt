package com.ruyo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.ruyo.ui.RuyoModel
import com.ruyo.ui.RuyoApp

class MainActivity : ComponentActivity() {
    private val model: RuyoModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { RuyoApp(model) }
    }

    override fun onStart() { super.onStart(); model.appForeground(true) }

    override fun onStop() {
        model.flushPosition()
        model.appForeground(false)
        super.onStop()
    }
}
