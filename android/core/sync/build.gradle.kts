plugins { alias(libs.plugins.android.library) }

android {
    namespace = "app.lanvault.core.sync"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:transport"))
    implementation(project(":core:media"))
    implementation(project(":core:security"))
    implementation(project(":core:database"))
    implementation(project(":core:network"))
    implementation(libs.work.runtime)
    implementation(libs.coroutines.android)
}
