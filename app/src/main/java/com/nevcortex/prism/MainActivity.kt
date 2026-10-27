package com.nevcortex.prism

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import com.nevcortex.prism.ui.LocalAppContainer
import com.nevcortex.prism.ui.nav.PrismScaffold
import com.nevcortex.prism.ui.theme.PrismTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as PrismApp).container
        setContent {
            PrismTheme {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    PrismScaffold(container)
                }
            }
        }
    }
}
