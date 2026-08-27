package io.toolbox.host

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.toolbox.core.ui.theme.ToolBoxTheme
import io.toolbox.host.navigation.ToolBoxNavigation

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ToolBoxTheme {
                ToolBoxNavigation()
            }
        }
    }
}
