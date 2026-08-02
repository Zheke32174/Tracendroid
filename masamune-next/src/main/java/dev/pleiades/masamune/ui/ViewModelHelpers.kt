package dev.pleiades.masamune.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * ViewModel construction with an explicit application Context, rather than relying on
 * AndroidViewModel plus whatever default factory the current NavBackStackEntry happens to
 * expose. Explicit is cheaper to debug than implicit here.
 */
@Composable
inline fun <reified VM : ViewModel> masamuneViewModel(
    crossinline builder: (Context) -> VM,
): VM {
    val appContext = LocalContext.current.applicationContext
    return viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                builder(appContext) as T
        }
    )
}
