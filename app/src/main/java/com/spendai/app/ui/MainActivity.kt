package com.spendai.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import com.spendai.app.ui.nav.SpendAiNavHost
import com.spendai.app.ui.theme.SpendAiTheme

/**
 * Single Activity for SpendAI. Hosts the Compose nav graph and applies
 * the M3 theme. There is no Fragment or secondary Activity in
 * Phase 1.5.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SpendAiApp()
        }
    }
}

@Composable
private fun SpendAiApp() {
    SpendAiTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            SpendAiNavHost()
        }
    }
}
