package com.dshnative.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import com.dshnative.app.ui.theme.DshNativeTheme
import com.dshnative.app.ui.ChatScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DshNativeTheme {
                ChatScreen()
            }
        }
    }
}