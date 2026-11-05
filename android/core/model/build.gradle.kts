plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(17) }

sourceSets.test { resources.srcDir(rootProject.file("../protocol/compatibility-fixtures")) }

dependencies {
    implementation(libs.serialization.json)
    testImplementation(libs.junit)
}
