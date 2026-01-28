// build.gradle.kts
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.0"
    application
}

group = "com.semantic.coverage"
version = "1.0.0"

repositories {
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        jvmTarget = "17"  // Исправлено с 21 на 17
    }
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.5")
    implementation("org.apache.commons:commons-text:1.10.0")
    implementation("commons-io:commons-io:2.13.0")
    implementation("org.apache.commons:commons-math3:3.6.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    implementation("com.aallam.openai:openai-client:3.7.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
}

application {
    mainClass.set("com.semantic.coverage.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    manifest {
        attributes("Main-Class" to "com.semantic.coverage.MainKt")
    }

    // Для создания fat jar (включая все зависимости)
    from(configurations.runtimeClasspath.get().map {
        if (it.isDirectory) it else zipTree(it)
    })

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

//// =========== build.gradle.kts
//plugins {
//    kotlin("jvm") version "1.9.0"
//    application
//    id("com.github.johnrengelman.shadow") version "8.1.1"
//}
//
//group = "com.semantic.coverage"
//version = "1.0.0"
//
//repositories {
//    mavenCentral()
//    google()
//}
//
//dependencies {
//    implementation(kotlin("stdlib"))
//
//    // Для парсинга
//    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")
//    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.15.2")
//
//    // Для командной строки
//    implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.5")
//
//    // Для текстовой обработки
//    implementation("org.apache.commons:commons-text:1.10.0")
//    implementation("commons-io:commons-io:2.13.0")
//
//    // Для эмбеддингов (пример с TensorFlow Java)
//    implementation("org.tensorflow:tensorflow-core-platform:0.4.1")
//    implementation("org.tensorflow:tensorflow-framework:0.4.1")
//
//    // Для отчетов
//    implementation("com.github.ajalt.clikt:clikt:4.2.0")
//
//    // Тестирование
//    testImplementation(kotlin("test"))
//    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
//}
//
//kotlin {
//    jvmToolchain(17)
//}
//
//application {
//    mainClass.set("MainKt")
//}
//
//tasks.test {
//    useJUnitPlatform()
//}
//
//tasks.shadowJar {
//    archiveBaseName.set("semantic-coverage")
//    archiveClassifier.set("")
//    archiveVersion.set("")
//
//    manifest {
//        attributes(
//             "Main-Class" to "MainKt",
//            "Implementation-Version" to version
//        )
//    }
//}
//
//tasks.jar {
//    manifest {
//        attributes(
//            "Main-Class" to "MainKt"
//        )
//    }
//}


// ===================
//import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
//
//plugins {
////    kotlin("jvm") version "2.0.20"
//    kotlin("jvm") version "1.9.0"
//}
//
//group = "org.example"
//version = "1.0-SNAPSHOT"
//
//repositories {
//    mavenCentral()
//    google()
//}
//
//dependencies {
//    implementation(kotlin("stdlib"))
//
//    // Для парсинга
//    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")
//    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.15.2")
//
//    // Для командной строки
//    implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.5")
//
//    // Для текстовой обработки
//    implementation("org.apache.commons:commons-text:1.10.0")
//    implementation("commons-io:commons-io:2.13.0")
//
//    // Для эмбеддингов (пример с TensorFlow Java)
//    implementation("org.tensorflow:tensorflow-core-platform:0.4.1")
//    implementation("org.tensorflow:tensorflow-framework:0.4.1")
//
//    // Для отчетов
//    implementation("com.github.ajalt.clikt:clikt:4.2.0")
//
//    implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.5")
//
//    // Тестирование
//    testImplementation(kotlin("test"))
//    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
//    testImplementation("io.mockk:mockk:1.13.7")
//}
//
//tasks.test {
//    useJUnitPlatform()
//}
//kotlin {
//    jvmToolchain(17)
//}
//
//tasks.withType<KotlinCompile> {
//    kotlinOptions {
//        jvmTarget = "17"
//        freeCompilerArgs = listOf("-Xjsr305=strict", "-opt-in=kotlin.io.path.ExperimentalPathApi")
//    }
//}