package com.nevcortex.prism.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.nevcortex.prism.di.AppContainer

/** Provides the app-wide [AppContainer] to composables without prop-drilling. */
val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer not provided")
}
