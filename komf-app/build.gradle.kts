import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.shadow)
}

group = "io.github.snd-r"
version = (findProperty("komfVersion") as String?) ?: libs.versions.app.version.get()

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
java {
    targetCompatibility = JavaVersion.VERSION_17
    sourceCompatibility = JavaVersion.VERSION_17
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
    implementation(project(":komf-core"))
    implementation(project(":komf-mediaserver"))
    implementation(project(":komf-notifications"))
    implementation(project(":komf-api-models"))

    implementation(libs.logback.core)
    implementation(libs.logback.classic)
    implementation(libs.slf4j.api)
    implementation(libs.kotlin.logging)

    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.default.headers)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.client.auth)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.encoding)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.kaml)
}

val komeliaWebAppDir = "${rootDir}/Komelia/komelia-app/build/dist/wasmJs/productionExecutable"
val komeliaWorkerDir = "${rootDir}/Komelia/komelia-image-decoder/wasm-image-worker/build/dist/wasmJs/productionExecutable"
val komeliaResourcesDir = "${projectDir}/src/main/resources/komelia"

tasks.register<Copy>("copyKomeliaWebApp") {
    group = "build"
    description = "Copies Komelia web app and image worker into komf-app resources for serving at /"
    from(komeliaWebAppDir)
    from(komeliaWorkerDir)
    into(komeliaResourcesDir)
    doFirst {
        if (!file(komeliaWebAppDir).exists()) {
            throw GradleException(
                "Komelia web app not built. Run first:\n" +
                "  cd Komelia && ./gradlew :komelia-app:wasmJsBrowserDistribution"
            )
        }
        if (!file(komeliaWorkerDir).exists()) {
            throw GradleException(
                "Komelia image worker not built. Run first:\n" +
                "  cd Komelia && ./gradlew :komelia-image-decoder:wasm-image-worker:wasmJsBrowserDistribution"
            )
        }
    }
}

tasks.named("processResources") {
    dependsOn("copyKomeliaWebApp")
}

tasks {
    shadowJar {
        archiveBaseName = "komf"
        archiveClassifier = ""
        manifest {
            attributes(Pair("Main-Class", "snd.komf.app.ApplicationKt"))
        }
    }
}

tasks.register("depsize") {
    description = "Prints dependencies for \"runtime\" configuration"
    doLast {
        listConfigurationDependencies(configurations["runtimeClasspath"])
    }
}

fun listConfigurationDependencies(configuration: Configuration) {
    val formatStr = "%,10.2f"

    val size = configuration.sumOf { it.length() / (1024.0 * 1024.0) }

    val out = StringBuffer()
    out.append("\nConfiguration name: \"${configuration.name}\"\n")
    if (size > 0) {
        out.append("Total dependencies size:".padEnd(65))
        out.append("${String.format(formatStr, size)} Mb\n\n")

        configuration.sortedBy { -it.length() }
            .forEach {
                out.append(it.name.padEnd(65))
                out.append("${String.format(formatStr, (it.length() / 1024.0))} kb\n")
            }
    } else {
        out.append("No dependencies found")
    }
    println(out)
}
