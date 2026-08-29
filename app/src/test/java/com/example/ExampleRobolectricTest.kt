package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jordiguixbetancor.m3ueditor.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// SDK 35 keeps this test green on every Robolectric version/environment (36 is not
// supported by all versions).
@Config(sdk = [35])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("M3U Editor y Reproductor", appName)
  }
}
