import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinHierarchyTemplate

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "health.hopena.cxca.demo"
  compileSdk = 36

  defaultConfig {
    applicationId = "health.hopena.cxca.demo"
    minSdk = 26
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"
  }

  buildTypes { getByName("release") { isMinifyEnabled = false } }
  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
  }
}

kotlin {
  jvmToolchain(21)

  androidTarget { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }

  jvm("desktop")

  // Note: iosX64 (Intel iOS simulator) is omitted because Compose Multiplatform 1.11.x does not
  // publish iosX64 artifacts; including it breaks dependency resolution for the whole project.
  listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
    target.binaries.framework {
      baseName = "CxcaDemoKit"
      isStatic = true
    }
  }

  @OptIn(ExperimentalWasmDsl::class)
  wasmJs {
    browser()
    binaries.executable()
  }

  // fhir-engine (nonWebMain) has no wasmJs target, so group the engine-backed platforms apart
  // from the web target, mirroring OpenSRP's opensrp-app source-set hierarchy.
  @OptIn(ExperimentalKotlinGradlePluginApi::class)
  applyHierarchyTemplate(KotlinHierarchyTemplate.default) {
    common {
      group("nonWeb") {
        withAndroidTarget()
        withIos()
        withJvm()
      }
      group("web") { withWasmJs() }
    }
  }

  targets.configureEach {
    compilations.configureEach {
      compilerOptions.configure {
        freeCompilerArgs.add("-Xexpect-actual-classes")
        optIn.addAll(
          "kotlin.time.ExperimentalTime",
          "kotlin.uuid.ExperimentalUuidApi",
          "androidx.compose.material3.ExperimentalMaterial3Api",
        )
      }
    }
  }

  sourceSets {
    commonMain.dependencies {
      implementation("dev.ohs.fhir:workflow:2.0.0-alpha01")
      implementation(libs.ohs.fhir.model)
      implementation(libs.kotlinx.coroutines.core)
      implementation(libs.kotlinx.datetime)
      implementation(libs.kotlinx.serialization.json)
      implementation(libs.kermit)
      implementation(compose.runtime)
      implementation(compose.foundation)
      implementation(compose.material3)
      implementation(compose.materialIconsExtended)
      implementation(compose.ui)
      implementation(compose.components.resources)
    }
    val nonWebMain by getting { dependencies { implementation(libs.ohs.fhir.engine) } }
    androidMain.dependencies {
      implementation(libs.androidx.activity.compose)
      // Provides Dispatchers.Main on Android via ServiceLoader (Room's DB coroutine needs it).
      // See
      // https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-dispatchers/-main.html
      implementation(libs.kotlinx.coroutines.android)
    }
    val desktopMain by getting {
      dependencies {
        implementation(compose.desktop.currentOs)
        // Provides Dispatchers.Main on the JVM/desktop via ServiceLoader (Room's DB coroutine needs
        // it).
        // See
        // https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-dispatchers/-main.html
        implementation(libs.kotlinx.coroutines.swing)
      }
    }
    commonTest.dependencies {
      implementation(libs.kotlin.test)
      implementation(libs.kotlinx.coroutines.test)
    }
  }
}

compose.desktop {
  application {
    mainClass = "dev.ohs.fhir.workflow.demo.MainKt"
    nativeDistributions {
      targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
      packageName = "kotlin-fhir-workflow-demo"
      packageVersion = "1.0.0"
    }
  }
}
