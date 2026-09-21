plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// --- Version (single source of truth) ------------------------------------------
// 只改 appVersionName 这一行；versionCode 与 APK 产物名都从它派生，
// 避免出现「版本号改了一处、忘了另一处」导致无法覆盖安装。
val appVersionName = "0.1.8"

/** `MAJOR.MINOR.PATCH` → 单调递增的 versionCode（0.1.0 → 100，0.1.1 → 101，0.2.0 → 200）。 */
val appVersionCode: Int = appVersionName.split('.').let { parts ->
    require(parts.size == 3) { "versionName must be MAJOR.MINOR.PATCH, got '$appVersionName'" }
    val numbers = parts.map { part ->
        part.toIntOrNull() ?: error("versionName segment is not a number: '$part'")
    }
    numbers[0] * 10_000 + numbers[1] * 100 + numbers[2]
}
// -----------------------------------------------------------------------------

// --- Version catalog (single source of truth) ---------------------------------
val media3Version = "1.4.1"
val okhttpVersion = "4.12.0"
val retrofitVersion = "2.11.0"
val coroutinesVersion = "1.9.0"
val lifecycleVersion = "2.8.7"
val activityVersion = "1.9.3"
val coreVersion = "1.13.1"
val recyclerViewVersion = "1.3.2"
val securityCryptoVersion = "1.0.0"
// -----------------------------------------------------------------------------

android {
    namespace = "com.tvmedia.openlist"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tvmedia.openlist"
        minSdk = 24
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    // 代理是纯 JVM 逻辑（只有日志走 android.util.Log），集成测试在 JVM 里跑：
    // 返回默认值而不是抛 "not mocked"，这样 LocalProxyServer 可以直接被测试驱动。
    testOptions {
        unitTests.isReturnDefaultValues = true
        // 代理会持有 24 MiB × 2 的窗口，默认测试堆不够（会 OOM）。
        // 真机上这也是要盯的内存量。
        unitTests.all { it.maxHeapSize = "1g" }
    }

    // 产物名带版本：app/build/outputs/apk/debug/tvmedia-v0.1.0-debug.apk
    // 归档到 releases/ 时不需要改名，与 tag 名（v0.1.0）天然一致。
    //
    // 说明：AGP 8.x 的 VariantOutput 没有 outputFileName，改名只能走 legacy variant API。
    @Suppress("DEPRECATION")
    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "tvmedia-v$appVersionName-$name.apk"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:$coreVersion")
    implementation("androidx.activity:activity-ktx:$activityVersion")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:$lifecycleVersion")
    implementation("androidx.recyclerview:recyclerview:$recyclerViewVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesVersion")

    implementation("com.squareup.okhttp3:okhttp:$okhttpVersion")
    implementation("com.squareup.retrofit2:retrofit:$retrofitVersion")
    implementation("com.squareup.retrofit2:converter-gson:$retrofitVersion")

    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")

    implementation("androidx.security:security-crypto:$securityCryptoVersion")

    testImplementation("junit:junit:4.13.2")
}
