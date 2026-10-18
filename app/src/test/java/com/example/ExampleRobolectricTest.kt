package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ui.components.formatTimecode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Demucs Studio", appName)
  }

  @Test
  fun `test timecode formatting`() {
    assertEquals("00:00.00", formatTimecode(0L))
    assertEquals("01:15.50", formatTimecode(75500L))
    assertEquals("03:45.00", formatTimecode(225000L))
  }
}
