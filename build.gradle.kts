plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.kapt) apply false
    alias(libs.plugins.hilt.android) apply false
}

// Expose the Rokid Maven repository to all subprojects
allprojects {
    repositories {
        maven { url = uri("https://maven.rokid.com/repository/maven-public/") }
    }
}
