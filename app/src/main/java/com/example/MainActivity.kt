package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.ui.AudioSeparationViewModel
import com.example.ui.DemucsStudioScreen
import com.example.ui.theme.DemucsStudioTheme

class MainActivity : ComponentActivity() {

    private val viewModel: AudioSeparationViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DemucsStudioTheme {
                DemucsStudioScreen(viewModel = viewModel)
            }
        }
    }
}
