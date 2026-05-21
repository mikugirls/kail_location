plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

android {
    namespace = "com.kail.location"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kail.location"
        minSdk = 27
        targetSdk = 36
        versionCode = 28
        versionName = "1.5.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        buildConfigField("String", "POCKETBASE_URL", "\"http://localhost:48080\"")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        viewBinding = true
        buildConfig = true
        prefab = true
        aidl = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("libs")
        }
    }
}

// Copy kail_inject executable from CMake build output into merged native libs,
// renaming to .so suffix so AGP packages it into APK lib/<abi>/libkail_inject.so
tasks.whenTaskAdded {
    if (name == "mergeDebugNativeLibs") {
        doFirst {
            val cmakeDebugDir = file("${buildDir}/intermediates/cxx/Debug")
            if (cmakeDebugDir.exists()) {
                cmakeDebugDir.listFiles()?.forEach { hashDir ->
                    if (hashDir.isDirectory) {
                        val arm64Exe = file("${hashDir.absolutePath}/obj/arm64-v8a/kail_inject")
                        if (arm64Exe.exists()) {
                            copy {
                                from(arm64Exe)
                                into("${buildDir}/intermediates/merged_native_libs/debug/out/lib/arm64-v8a/")
                                rename { "libkail_inject.so" }
                            }
                        }
                        val armExe = file("${hashDir.absolutePath}/obj/armeabi-v7a/kail_inject")
                        if (armExe.exists()) {
                            copy {
                                from(armExe)
                                into("${buildDir}/intermediates/merged_native_libs/debug/out/lib/armeabi-v7a/")
                                rename { "libkail_inject.so" }
                            }
                        }
                    }
                }
            }
        }
    }
    if (name == "mergeReleaseNativeLibs") {
        doFirst {
            val cmakeReleaseDir = file("${buildDir}/intermediates/cxx/RelWithDebInfo")
            if (!cmakeReleaseDir.exists()) {
                val altDir = file("${buildDir}/intermediates/cxx/Release")
                if (altDir.exists()) {
                    altDir.listFiles()?.forEach { hashDir ->
                        if (hashDir.isDirectory) {
                            val arm64Exe = file("${hashDir.absolutePath}/obj/arm64-v8a/kail_inject")
                            if (arm64Exe.exists()) {
                                copy {
                                    from(arm64Exe)
                                    into("${buildDir}/intermediates/merged_native_libs/release/out/lib/arm64-v8a/")
                                    rename { "libkail_inject.so" }
                                }
                            }
                            val armExe = file("${hashDir.absolutePath}/obj/armeabi-v7a/kail_inject")
                            if (armExe.exists()) {
                                copy {
                                    from(armExe)
                                    into("${buildDir}/intermediates/merged_native_libs/release/out/lib/armeabi-v7a/")
                                    rename { "libkail_inject.so" }
                                }
                            }
                        }
                    }
                }
            } else {
                cmakeReleaseDir.listFiles()?.forEach { hashDir ->
                    if (hashDir.isDirectory) {
                        val arm64Exe = file("${hashDir.absolutePath}/obj/arm64-v8a/kail_inject")
                        if (arm64Exe.exists()) {
                            copy {
                                from(arm64Exe)
                                into("${buildDir}/intermediates/merged_native_libs/release/out/lib/arm64-v8a/")
                                rename { "libkail_inject.so" }
                            }
                        }
                        val armExe = file("${hashDir.absolutePath}/obj/armeabi-v7a/kail_inject")
                        if (armExe.exists()) {
                            copy {
                                from(armExe)
                                into("${buildDir}/intermediates/merged_native_libs/release/out/lib/armeabi-v7a/")
                                rename { "libkail_inject.so" }
                            }
                        }
                    }
                }
            }
        }
    }
    if (name == "stripDebugDebugSymbols") {
        doLast {
            val mergedDir = file("${buildDir}/intermediates/merged_native_libs/debug/out/lib")
            val strippedDir = file("${buildDir}/intermediates/stripped_native_libs/debug/stripDebugDebugSymbols/out/lib")
            if (mergedDir.exists() && strippedDir.exists()) {
                listOf("arm64-v8a", "armeabi-v7a").forEach { abi ->
                    val src = file("${mergedDir.absolutePath}/$abi/libkail_inject.so")
                    val dst = file("${strippedDir.absolutePath}/$abi/libkail_inject.so")
                    if (src.exists() && !dst.exists()) {
                        copy {
                            from(src)
                            into("${strippedDir.absolutePath}/$abi/")
                        }
                    }
                }
            }
        }
    }
    if (name == "stripReleaseDebugSymbols") {
        doLast {
            val mergedDir = file("${buildDir}/intermediates/merged_native_libs/release/out/lib")
            val strippedDir = file("${buildDir}/intermediates/stripped_native_libs/release/stripReleaseDebugSymbols/out/lib")
            if (mergedDir.exists() && strippedDir.exists()) {
                listOf("arm64-v8a", "armeabi-v7a").forEach { abi ->
                    val src = file("${mergedDir.absolutePath}/$abi/libkail_inject.so")
                    val dst = file("${strippedDir.absolutePath}/$abi/libkail_inject.so")
                    if (src.exists() && !dst.exists()) {
                        copy {
                            from(src)
                            into("${strippedDir.absolutePath}/$abi/")
                        }
                    }
                }
            }
        }
    }
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:34.12.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-crashlytics")

    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.preference)
    implementation(libs.material)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.noties.markwon:core:4.6.2")

    // ShadowHook
    implementation("com.bytedance.android:shadowhook:1.0.9")

    // Dobby
    implementation("io.github.vvb2060.ndk:dobby:1.2")

    // Compose dependencies (keep them for future use or mixed usage)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0")
    implementation("com.google.zxing:core:3.5.1")
    implementation("com.google.android.gms:play-services-ads:25.2.0")
    implementation("androidx.lifecycle:lifecycle-process:2.8.3")
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    val room_version = "2.7.0"
    implementation("androidx.room:room-runtime:$room_version")
    implementation("androidx.room:room-ktx:$room_version")
    ksp("androidx.room:room-compiler:$room_version")

    // NewBlackbox sandbox core module
    implementation(project(":NewBlackbox:Bcore"))
}
