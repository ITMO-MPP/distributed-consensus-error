plugins {
    kotlin("jvm") version "2.1.10"
    java
    application
}

repositories {
    mavenCentral()
}

application.mainClass.set("consensus.VisualiseKt")

dependencies {
    implementation("ch.qos.logback:logback-classic:1.2.9")
    testImplementation(kotlin("test-junit"))
}

sourceSets {
    main {
        java.setSrcDirs(listOf("src"))
    }
    test {
        java.setSrcDirs(listOf("test"))
    }
}

kotlin {
    jvmToolchain(21)
}
