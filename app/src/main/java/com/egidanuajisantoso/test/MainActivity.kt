package com.egidanuajisantoso.test

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.egidanuajisantoso.test.ui.theme.TestTheme
import com.egidanuajisantoso.test.ui.scanner.ScannerScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TestTheme {
                ScannerScreen()
            }
        }
    }
}
