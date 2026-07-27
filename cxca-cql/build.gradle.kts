import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.kotlin.multiplatform.library)
}

// The CQL stack publishes jvm/js/wasmJs targets (no Kotlin/Native yet), which bounds this
// module's targets: desktop JVM, Android (consumes the jvm variants), and wasmJs. iOS arrives
// when cqframework adds native targets. Built from cqframework PR #1815 via mavenLocal.
val cqlVersion = "5.1.0-kmp-fhir-providers-84476e31-SNAPSHOT"

// cql-to-elm is compiled against antlr-kotlin 1.0.3; fhir-path pulls 1.0.10, whose Interval API
// is binary-incompatible with it. Pin the version the CQL compiler needs — the FHIRPath parser's
// compatibility with 1.0.3 is asserted by shouldStillEvaluateFhirPathOnThisClasspath.
configurations.all {
  resolutionStrategy {
    force("com.strumenta:antlr-kotlin-runtime:1.0.3")
    force("com.strumenta:antlr-kotlin-runtime-jvm:1.0.3")
  }
}

kotlin {
  jvmToolchain(21)

  androidLibrary {
    namespace = "health.hopena.cxca.cql"
    compileSdk = 36
    minSdk = 26
  }

  jvm("desktop")

  @OptIn(ExperimentalWasmDsl::class) wasmJs { nodejs() }

  sourceSets {
    commonMain.dependencies {
      api("dev.ohs.fhir:workflow:2.0.0-alpha01") // the ExpressionEvaluator seam this implements
      implementation(libs.ohs.fhir.model)
      implementation(libs.kotlinx.coroutines.core)
      implementation(libs.kotlinx.datetime)
      implementation(libs.kotlinx.serialization.json)
      implementation("org.cqframework:cql-to-elm:$cqlVersion")
      implementation("org.cqframework:engine:$cqlVersion")
      implementation("org.cqframework:engine-fhir:$cqlVersion")
    }
    // Tests live in desktopTest (not commonTest) because they read the 4 MB FHIR modelinfo from
    // the JVM classpath; consumers on other platforms supply it as a resource string instead.
    val desktopTest by getting {
      dependencies {
        implementation(libs.kotlin.test)
        implementation(libs.kotlinx.coroutines.test)
      }
    }
  }
}
