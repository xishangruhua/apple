package com.cfop.lab

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import com.cfop.lab.ui.CFOPNativeApp
import com.cfop.lab.vision.CubeSquareDetector
import org.opencv.android.OpenCVLoader

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        CubeSquareDetector.openCvReady = try {
            OpenCVLoader.initLocal()
        } catch (e: Throwable) {
            Log.e("CFOPLab", "OpenCV init failed", e)
            false
        }

        setContent {
            MaterialTheme {
                CFOPNativeApp(context = this)
            }
        }
    }
}
