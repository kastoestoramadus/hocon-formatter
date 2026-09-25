import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    `java-gradle-plugin`
    `maven-publish`
}

group = "io.github.kastoestoramadus"
version = "0.1.0-SNAPSHOT"

repositories {
    // Until the core reaches Maven Central, `sbt coreJVM/publishM2` is what puts it here.
    mavenLocal()
    mavenCentral()
}

// Scala 3.8 needs Java 17, so a lower target would only move the failure to the first format.
tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

gradlePlugin {
    plugins {
        create("hoconFormatter") {
            id = "io.github.kastoestoramadus.hocon-formatter"
            implementationClass = "ww86.hocon_fmt.gradle.HoconFormatterPlugin"
            displayName = "HOCON formatter"
            description = "Formats HOCON configuration files, or checks that they are formatted."
        }
    }
}

val functionalTestSourceSet = sourceSets.create("functionalTest")

dependencies {
    "functionalTestImplementation"(platform("org.junit:junit-bom:6.0.1"))
    "functionalTestImplementation"("org.junit.jupiter:junit-jupiter")
    "functionalTestRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

val functionalTest = tasks.register<Test>("functionalTest") {
    description = "Runs builds that apply the plugin, through Gradle TestKit."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = functionalTestSourceSet.output.classesDirs
    classpath = functionalTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}

gradlePlugin.testSourceSets.add(functionalTestSourceSet)

tasks.check {
    dependsOn(functionalTest)
}
