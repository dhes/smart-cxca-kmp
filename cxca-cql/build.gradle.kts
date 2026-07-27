// KGP is already on the build classpath via the root project; declare the id without a version.
plugins { id("org.jetbrains.kotlin.jvm") }

kotlin { jvmToolchain(21) }

// The CQL stack (jvm/js/wasmJs targets, no iOS) — a plain JVM module so both the desktop and
// Android flavors of the demo can consume it. Built from cqframework PR #1815 via mavenLocal.
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

dependencies {
  api("dev.ohs.fhir:workflow:2.0.0-alpha01") // the ExpressionEvaluator seam this module implements
  implementation(libs.ohs.fhir.model)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.datetime)
  implementation("org.cqframework:cql-to-elm:$cqlVersion")
  implementation("org.cqframework:engine:$cqlVersion")
  implementation("org.cqframework:engine-fhir:$cqlVersion")

  testImplementation(libs.kotlin.test)
  testImplementation(libs.kotlinx.coroutines.test)
}
