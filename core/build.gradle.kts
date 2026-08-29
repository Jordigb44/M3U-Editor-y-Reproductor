plugins {
  alias(libs.plugins.android.library)
}

android {
  // Unique namespace per module (the Kotlin packages inside stay com.example.data / com.example.ui).
  namespace = "com.example.core"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    minSdk = 24
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
}

// Shared business logic for both apps (mobile "Pepe Editor" and "Pepe Editor TV"):
// M3U parser, channel/playlist models, networking and the EditorViewModel.
dependencies {
  implementation(libs.androidx.lifecycle.viewmodel.ktx)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.okhttp)
}
