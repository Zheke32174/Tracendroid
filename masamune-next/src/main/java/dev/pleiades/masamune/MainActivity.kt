package dev.pleiades.masamune

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.pleiades.masamune.ui.MasamuneRoot
import dev.pleiades.masamune.ui.theme.MasamuneTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MasamuneTheme {
                MasamuneRoot()
            }
        }
    }
}
