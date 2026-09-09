package com.khatago.finance.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisallowComposableCalls
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.khatago.finance.AppContainer
import com.khatago.finance.container

/**
 * The one way a screen obtains a ViewModel.
 *
 * `viewModel(factory = …)` with a hand-built factory is normally the boring part of Compose setup,
 * but here it carries a decision: KhataGo has no DI framework, so the object graph reaches a screen
 * *only* through `LocalContext` → `AppContainer`. Doing that in one function means a future change
 * (moving to Hilt, or constructing a container per user profile) is one file, not forty screens.
 */
@Composable
inline fun <reified VM : ViewModel> khataGoViewModel(
    crossinline create: @DisallowComposableCalls (AppContainer) -> VM,
): VM {
    val container = LocalContext.current.container
    val factory = remember(container) {
        viewModelFactory { initializer { create(container) } }
    }
    return viewModel(factory = factory)
}

/** For screens with no ViewModel of their own (small detail screens), the container directly. */
@Composable
@Suppress("ComposableNaming")
fun rememberContainer(): AppContainer = LocalContext.current.container
