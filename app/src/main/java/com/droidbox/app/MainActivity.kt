package com.droidbox.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.droidbox.ui.DevVmScreen

/**
 * Developer entry point. Per the project spec, the first demonstrable screen
 * is a simple developer VM screen — no polished UI until the real QEMU guest
 * boot is proven (Milestone A).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                DevVmScreen()
            }
        }
    }
}
