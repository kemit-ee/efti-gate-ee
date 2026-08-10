import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val mainClassName = "LauncherKt"

plugins {
  val kotlinVersion = "2.4.0"
  kotlin("jvm") version kotlinVersion
}

allprojects {
  repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
  }
}

subprojects {
  apply(plugin = "kotlin")

  kotlin {
    jvmToolchain(25)
  }

  dependencies {
    fun klite(module: String) = "com.github.keksworks.klite:klite-$module:2.0.1"
    implementation(klite("server"))
    implementation(klite("json"))
    implementation(klite("xml"))
    implementation(klite("slf4j"))
    implementation(klite("openapi"))
    implementation("org.postgresql:postgresql:42.7.13")

    testImplementation(klite("jdbc-test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:6.0.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.3")
    testImplementation("io.github.artsok:rerunner-jupiter:2.1.6")
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
    jvmArgs("-DENV=test", "-DOWN_PARTY_ID=TEST", "-XX:-OmitStackTraceInFastThrow")
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
    workingDir(projectDir)
    jvmArgs("--add-opens=java.base/sun.security.x509=ALL-UNNAMED")
    mainClass.set(mainClassName)
    classpath = sourceSets.main.get().runtimeClasspath
  }
}
