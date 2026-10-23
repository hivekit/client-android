package io.hivekit.consumer

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import io.hivekit.client.HivekitClient

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sdkVersion = HivekitClient().use { it.version }
        setContentView(TextView(this).apply { text = "Hivekit SDK $sdkVersion installed" })
    }
}
