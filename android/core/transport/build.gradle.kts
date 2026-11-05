plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core:model"))
    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
}

tasks.test {
    systemProperty(
        "lanvault.server.exe",
        providers.gradleProperty("lanvaultServerExe").orElse("").get(),
    )
}
