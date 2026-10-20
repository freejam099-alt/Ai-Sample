plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.kotlin.compose)
}

// The AI Sample Catalog is merged into the JetPacker app as a library module.
android {
    namespace = "com.android.ai.catalog"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = 26
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.navigation.runtime.ktx)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.ai)
    ksp(libs.hilt.compiler)
    implementation(project(":catalog-ui-component"))
    implementation(project(":catalog-gemini-chatbot"))
    implementation(project(":catalog-gemini-image-chat"))
    implementation(project(":catalog-gemini-live-todo"))
    implementation(project(":catalog-gemini-multimodal"))
    implementation(project(":catalog-gemini-video-metadata-creation"))
    implementation(project(":catalog-gemini-video-summarization"))
    implementation(project(":catalog-magic-selfie"))
    implementation(project(":catalog-nanobanana"))
}
