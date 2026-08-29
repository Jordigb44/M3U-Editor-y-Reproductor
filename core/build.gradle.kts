plugins {
  alias(libs.plugins.android.library)
}

android {
  // Unique namespace per module (the Kotlin packages inside stay com.example.data / com.example.ui).
  namespace = "com.jordiguixbetancor.m3ueditor.core"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    minSdk = 24
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
}

// Shared business logic for both apps (mobile and TV apps):
// M3U parser, channel/playlist models, networking, EditorViewModel and player tuning.
dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.ktx)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.okhttp)
  implementation(libs.androidx.media3.exoplayer)
}
