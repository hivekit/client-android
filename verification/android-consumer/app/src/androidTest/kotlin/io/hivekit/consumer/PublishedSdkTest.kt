package io.hivekit.consumer

import android.content.Intent
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.hivekit.client.HistoryRange
import io.hivekit.client.HivekitClient
import io.hivekit.client.HivekitLocation
import io.hivekit.client.ObjectPatch
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PublishedSdkTest {
    @Test
    fun publishedSdkLoadsWithTransitiveDependenciesAndDesugaring() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expectedVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        HivekitClient().use { client ->
            assertEquals(expectedVersion, client.version)
            assertTrue(HivekitClient.getId("android").startsWith("android"))
            val patch = ObjectPatch(
                label = "Installed from JitPack",
                location = HivekitLocation(latitude = 52.52, longitude = 13.4),
                data = buildJsonObject { put("installed", true) }
            )
            assertEquals("true", patch.data?.get("installed").toString())
            val range = HistoryRange(Instant.EPOCH, Instant.parse("1970-01-01T00:00:01Z"))
            assertEquals(1L, range.end.epochSecond - range.start.epochSecond)
        }
    }

    @Test
    fun installedAppStartsAndDisplaysPublishedSdkVersion() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val expectedVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        try {
            instrumentation.runOnMainSync {
                val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                assertEquals(
                    "Hivekit SDK $expectedVersion installed",
                    (content.getChildAt(0) as TextView).text.toString()
                )
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
