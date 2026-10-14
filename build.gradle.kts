// 根构建文件：只声明两个插件的版本，真正的配置在 app/build.gradle.kts 里
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
