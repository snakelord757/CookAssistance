plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}
android {
    namespace = "com.cookassistance.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.cookassistance.app"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        debug { buildConfigField("String", "SERVER_URL", "\"http://10.0.2.2:8080\"") }
        release {
            val endpoint = providers.gradleProperty("serverUrl").orElse("").get()
            require(endpoint.isEmpty() || (endpoint.startsWith("https://") && !endpoint.contains('"'))) { "serverUrl must be HTTPS" }
            buildConfigField("String", "SERVER_URL", "\"$endpoint\"")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
}
dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui); implementation(libs.compose.material3)
    implementation(libs.activity); implementation(libs.lifecycle.compose)
    implementation(libs.lifecycle.viewmodel); implementation(libs.lifecycle.savedstate)
    implementation(libs.navigation3)
    implementation(libs.ktor.client.core); implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content); implementation(libs.ktor.json)
    implementation(libs.coil.compose); implementation(libs.coil.network)
    testImplementation(libs.junit); testImplementation(libs.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.test); androidTestImplementation(libs.android.test.junit)
    androidTestImplementation(libs.android.test.runner)
    androidTestImplementation(libs.espresso.core)
    debugImplementation(libs.compose.test.manifest); debugImplementation(libs.compose.tooling)
}
