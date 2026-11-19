import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("io.github.chris2crafter.thorstore.tool.MainKt")
}

dependencies {
    implementation(project(":core"))
}

// ./gradlew :catalogtool:run  -> resolves catalog/catalog-src.json into app/src/main/assets
tasks.named<JavaExec>("run") {
    workingDir = rootDir
    args(
        rootProject.file("catalog/catalog-src.json").path,
        rootProject.file("app/src/main/assets").path,
    )
}
