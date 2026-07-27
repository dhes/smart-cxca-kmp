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

// Chunk the FHIR modelinfo XML into generated Kotlin string constants for commonTest: JVM class
// files cap string constants at 64 KB (modified UTF-8), so ~20k-char chunks keep every target
// happy. Consumers of the library still pass the modelinfo in as a plain String.
val generateModelInfoKt =
  tasks.register("generateModelInfoKt") {
    val xmlFile = layout.projectDirectory.file("modelinfo/fhir-modelinfo-4.0.1.xml")
    val outDir = layout.buildDirectory.dir("generated/modelinfo/kotlin")
    inputs.file(xmlFile)
    outputs.dir(outDir)
    doLast {
      val chunks = xmlFile.asFile.readText().chunked(20_000)
      val escaped =
        chunks.map {
          it.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("$", "\\$")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .replace("\t", "\\t")
        }
      val out = outDir.get().file("FhirModelInfo.kt").asFile
      out.parentFile.mkdirs()
      out.writeText(
        buildString {
          appendLine("package health.hopena.cxca.cql")
          appendLine()
          appendLine("// GENERATED from modelinfo/fhir-modelinfo-4.0.1.xml — do not edit.")
          escaped.forEachIndexed { i, c -> appendLine("private const val MI_$i = \"$c\"") }
          appendLine()
          appendLine("internal val FHIR_MODELINFO_401_XML: String = buildString {")
          chunks.indices.forEach { appendLine("  append(MI_$it)") }
          appendLine("}")
        }
      )
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

  @OptIn(ExperimentalWasmDsl::class)
  wasmJs {
    // Compiling the CQL library + parsing the 4 MB modelinfo is slow under Node; mocha's 2 s
    // default per-test timeout is far too tight.
    nodejs { testTask { useMocha { timeout = "300s" } } }
  }

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
    commonTest {
      // The FHIR modelinfo as generated Kotlin constants — the one way to hand 4 MB of XML to
      // every target (wasm has no classpath). See generateModelInfoKt below.
      kotlin.srcDir(generateModelInfoKt)
      dependencies {
        implementation(libs.kotlin.test)
        implementation(libs.kotlinx.coroutines.test)
      }
    }
  }
}
