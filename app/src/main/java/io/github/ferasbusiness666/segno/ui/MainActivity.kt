package io.github.ferasbusiness666.segno.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    companion object {
        const val ACTION_NOW_PLAYING = "io.github.ferasbusiness666.segno.NOW_PLAYING"
        const val ACTION_DOWNLOADS = "io.github.ferasbusiness666.segno.DOWNLOADS"
    }
}
