import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val mainClassName = "LauncherKt"

plugins {
  val kotlinVersion = "2.4.20"
  kotlin("jvm") version kotlinVersion
  jacoco
}

allprojects {
  repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
  }
}

subprojects {
  apply(plugin = "kotlin")
  apply(plugin = "jacoco")

  kotlin {
    jvmToolchain(25)
  }

  jacoco {
    toolVersion = "0.8.13"
  }

  dependencies {
    fun klite(module: String) = "com.github.keksworks.klite:klite-$module:2.0.7"
    implementation(klite("server"))
    implementation(klite("json"))
    implementation(klite("xml"))
    implementation(klite("slf4j"))
    implementation(klite("openapi"))

    testImplementation(klite("jdbc-test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:6.0.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.3")
    testImplementation("ch.tutteli.atrium:atrium-fluent:1.3.0-alpha-2")
    testImplementation("io.mockk:mockk:1.14.11")
  }

  sourceSets {
    main {
      java.setSrcDirs(listOf("src"))
      kotlin.setSrcDirs(listOf("src"))
      resources.setSrcDirs(listOf("src")).exclude("**/*.kt")
    }
    test {
      kotlin.setSrcDirs(listOf("test"))
      resources.setSrcDirs(listOf("test")).exclude("**/*.kt")
    }
  }

  tasks.test {
    workingDir(projectDir)
    useJUnitPlatform()
    jvmArgs("-DENV=test", "-DOWN_GATE_ID=TEST", "-XX:-OmitStackTraceInFastThrow")
    finalizedBy(tasks.jacocoTestReport)
  }

  // Launcher.kt has no class named Launcher -- its top-level main() compiles to the
  // synthetic facade LauncherKt (Kotlin's file-class convention for top-level functions).
  val coverageExcludes = listOf("Launcher.class", "Launcher\$*.class", "LauncherKt.class", "LauncherKt\$*.class")

  tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
      xml.required.set(true)
      html.required.set(true)
    }
    classDirectories.setFrom(
      files(classDirectories.files.map {
        fileTree(it) { exclude(coverageExcludes) }
      })
    )
  }

  tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)
    classDirectories.setFrom(
      files(classDirectories.files.map {
        fileTree(it) { exclude(coverageExcludes) }
      })
    )
    violationRules {
      rule {
        limit {
          minimum = "0.80".toBigDecimal()
        }
      }
    }
  }

  tasks.withType<KotlinCompile> {
    compilerOptions {
      freeCompilerArgs.addAll(
        "-Xcollection-literals"
      )
    }
  }

  tasks.register<Copy>("deps") {
    val depsDir = layout.buildDirectory.dir("libs/deps")
    doFirst { delete(depsDir) }
    into(depsDir)
    from(configurations.runtimeClasspath)
  }

  tasks.jar {
    dependsOn("deps")
    doFirst {
      manifest {
        attributes(mapOf(
          "Main-Class" to mainClassName,
          "Class-Path" to File("$buildDir/libs/deps").listFiles()?.joinToString(" ") { "deps/${it.name}" }
        ))
      }
    }
  }

  tasks.register<JavaExec>("run") {
    jvmArgs("--add-opens=java.base/sun.security.x509=ALL-UNNAMED")
    mainClass.set(mainClassName)
    classpath = sourceSets.main.get().runtimeClasspath
  }
}
