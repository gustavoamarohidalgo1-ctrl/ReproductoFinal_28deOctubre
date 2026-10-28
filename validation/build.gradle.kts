plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
}

val sharedServiceTests = setOf(
    "**/BootResumeServiceInstrumentedTest.kt",
    "**/PlaybackServiceInstrumentedTest.kt",
    "**/LongPlaybackSoakInstrumentedTest.kt",
    "**/LoopPersistenceLoadInstrumentedTest.kt",
    "**/QueueUpgradeInstrumentedTest.kt",
    "**/ServiceRestartInstrumentedTest.kt",
    "**/SparseWaveFixture.kt",
    "**/PlaybackTestContract.kt"
)

// Kotlin's Android source bridge does not consistently retain AndroidSourceSet
// include filters. Stage an explicit, closed set of black-box tests instead.
val stageServiceTests by tasks.registering(Sync::class) {
    from("../app/src/androidTest/java") {
        include(sharedServiceTests)
    }
    into(layout.buildDirectory.dir("generated/serviceTests"))
}

android {
    namespace = "com.reproductor.audiolibros.validation"
    compileSdk = 35
    targetProjectPath = ":app"
    // AGP normally subtracts libraries present in the app, even when R8 has renamed
    // or removed their classes. Package the runner's own complete dependency graph.
    experimentalProperties["android.experimental.self-instrumenting"] = true

    defaultConfig {
        minSdk = 26
        targetSdk = 35
        testApplicationId = "com.reproductor.audiolibros.validation"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        create("benchmark") {
            initWith(getByName("debug"))
            isDebuggable = true
            isMinifyEnabled = false
            matchingFallbacks += "debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("main") {
        java.setSrcDirs(listOf(stageServiceTests))
        assets.srcDir("../app/src/androidTest/assets")
    }
}

androidComponents {
    beforeVariants(selector().all()) { variant ->
        variant.enable = variant.buildType == "benchmark"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
    sourceSets.getByName("main").kotlin {
        setSrcDirs(listOf(stageServiceTests))
    }
}

// Also declare the lifecycle edge explicitly for AGP/Kotlin versions that flatten
// a task-backed source directory to a plain path while creating compiler tasks.
tasks.named("preBuild") {
    dependsOn(stageServiceTests)
}

dependencies {
    implementation("androidx.test:runner:1.6.2")
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.media3:media3-common:1.6.1")
    implementation("androidx.media3:media3-session:1.6.1")
}

// AGP 8.7.3's separate test plugin publishes PACKAGED_MANIFESTS, not the public
// SingleArtifact.MERGED_MANIFEST. Its generator also forces targetPackage to this
// module when self-instrumenting. Correct only this test APK's generated manifest:
// instrumentation must run as the real app UID to exercise its private service.
// The production APK, signature checks and service export policy remain untouched.
tasks.withType<com.android.build.gradle.tasks.ProcessTestManifest>().configureEach {
    doLast {
        val manifest = packagedManifestOutputDirectory.get().asFile.resolve("AndroidManifest.xml")
        val generated = manifest.readText()
        val targetAttribute = Regex("android:targetPackage=\"[^\"]+\"")
        require(targetAttribute.findAll(generated).count() == 1) {
            "Expected exactly one instrumentation target in the validation manifest"
        }
        val actualTarget = "android:targetPackage=\"com.reproductor.audiolibros\""
        val corrected = targetAttribute.replace(generated, actualTarget)
        require(corrected.contains(actualTarget)) { "Validation must instrument the production app" }
        manifest.writeText(corrected)
    }
}
