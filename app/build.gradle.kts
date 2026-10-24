// app 模块的构建配置：App 的一切"规格"都在这
plugins {
    id("com.android.application")          // Android 应用插件
    id("org.jetbrains.kotlin.android")     // Kotlin 语言插件
}

android {
    namespace = "com.zhizhou.xxtassistant"  // 包名（唯一标识）

    compileSdk = 34                        // 用什么版本的安卓 SDK 来编译

    defaultConfig {
        applicationId = "com.zhizhou.xxtassistant"  // 安装到手机后的应用ID
        minSdk = 26                                 // 最低支持安卓 8.0
        targetSdk = 34                               // 针对安卓 14 优化
        versionCode = 9
        versionName = "0.2.7"
    }

    flavorDimensions += "edition"
    productFlavors {
        create("basic") {
            dimension = "edition"
            versionName = "0.2.7-basic"
        }
        create("full") {
            dimension = "edition"
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    // 基础库：安卓官方扩展、兼容旧系统、Material 设计组件
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui:1.7.1")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.1")
    implementation("androidx.compose.foundation:foundation:1.7.1")
    implementation("androidx.compose.material3:material3:1.3.0")
    // 作业列表 UI
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // 定时提醒（WorkManager：安卓官方后台任务框架）
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    testImplementation("junit:junit:4.13.2")
}
