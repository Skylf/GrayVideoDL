plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.chaquopy)
}

android {
    namespace = "com.example.grayvideodl"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.grayvideodl"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.7B"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 指定 Chaquopy 支持的 CPU 架构
        // arm64-v8a：当前主流 Android 设备
        // x86_64：Android 模拟器
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
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
}

// Chaquopy 配置：嵌入 Python 运行环境
// Python 版本需与构建机器上的 Python 版本匹配
chaquopy {
    defaultConfig {
        // Python 解释器版本，需与 buildPython 指向的版本一致
        version = "3.11"

        // 构建机器上的 Python 解释器路径
        // 用于在构建时安装 Python 依赖包
        // 此处使用 listOf 传入命令及参数列表
        buildPython = listOf(
            "C:/Users/Administrator/AppData/Local/Programs/Python/Python311/python.exe"
        )

        // 指定 pip 安装的 Python 依赖包
        // yt-dlp：通用视频下载工具，支持 1800+ 平台
        // 注意：PyPI 镜像通过 PIP_INDEX_URL 环境变量在 gradlew.bat 中配置
        pip {
            // 安装 yt-dlp（含 curl-cffi 扩展，用于 TLS 指纹模拟）
            // [curl-cffi] 解决抖音/快手等平台的反爬拦截
            install("yt-dlp[curl-cffi]")
        }
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
