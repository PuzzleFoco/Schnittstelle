package com.puzzlefoco.schnittstelle.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.puzzlefoco.schnittstelle.ui.theme.SchnittstelleTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SchnittstelleTheme {
                SchnittstelleApp()
            }
        }
    }
}

@Composable
private fun SchnittstelleApp() {
    val vm: EditorViewModel = viewModel()
    if (vm.project == null) {
        ProjectListScreen(vm = vm)
    } else {
        EditorScreen(vm = vm)
    }
}
