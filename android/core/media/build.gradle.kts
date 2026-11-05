plugins { alias(libs.plugins.android.library) }

android {
    namespace = "app.lanvault.core.media"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:security"))
    implementation(libs.androidx.core)
    implementation(libs.coroutines.android)
}
