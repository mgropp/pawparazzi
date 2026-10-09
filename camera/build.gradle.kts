import java.net.URI

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.gropp.pawparazzi.camera"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        noCompress += "tflite"
    }
}

val modelFile = layout.projectDirectory.file("src/main/assets/efficientdet_lite0.tflite")

val downloadModel by tasks.registering {
    outputs.file(modelFile)
    onlyIf { !modelFile.asFile.exists() }
    doLast {
        val url = "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/1/efficientdet_lite0.tflite"
        modelFile.asFile.parentFile.mkdirs()
        URI(url).toURL().openStream().use { input ->
            modelFile.asFile.outputStream().use { input.copyTo(it) }
        }
    }
}

tasks.named("preBuild") { dependsOn(downloadModel) }

dependencies {
    api(project(":core:detection"))
    api(project(":core:settings"))
    api(project(":actions"))
    api(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    api(libs.mediapipe.tasks.vision)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
