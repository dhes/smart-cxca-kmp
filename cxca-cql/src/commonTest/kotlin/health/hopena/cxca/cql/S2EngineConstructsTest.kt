/*
 * Copyright 2026 Open Health Stack Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package health.hopena.cxca.cql

import dev.ohs.fhir.model.r4.Bundle
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.workflow.expression.EvaluationContext
import dev.ohs.fhir.workflow.expression.EvaluationResult
import dev.ohs.fhir.workflow.expression.ProtocolExpression
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json

/**
 * Engine-construct spike for the CXCA.S2.DT port: verifies, on this target's engine build, every
 * CQL construct S2 needs beyond what S1 already proved — `Max(... return ...)` over dateTimes,
 * `as FHIR.dateTime` on a choice element, `date from`, `years between`, null propagation, and a
 * `parameter ... default Today()` driven by the (now wired) evaluation clock. The library below is
 * the include-free fragment of CXCADueForScreeningLogic: the `Eligibility` include is deliberately
 * absent — multi-library resolution is the next step, not this spike.
 */
private val DUE_SPIKE_CQL =
  """
  library CXCADueSpike version '0.1.0'
  using FHIR version '4.0.1'
  valueset "HIV-positive status": 'http://smart.who.int/cxca/ValueSet/hiv-positive-status'
  valueset "Cervical cancer screening test": 'http://smart.who.int/cxca/ValueSet/cervical-cancer-screening-test'
  parameter "Today" default Today()
  context Patient
  define "Living with HIV":
    exists ([Observation: "HIV-positive status"] O where O.status.value in { 'final', 'amended' })
  define "Last Cervical Screen Date":
    Max(
      [Observation: "Cervical cancer screening test"] O
        return (O.effective as FHIR.dateTime).value
    )
  define "Years Since Last Screen":
    if "Last Cervical Screen Date" is null then null
    else years between (date from "Last Cervical Screen Date") and (date from Today)
  define "No Previous Screen":
    "Last Cervical Screen Date" is null
  define "Screening Interval Elapsed":
    if "Living with HIV"
      then "No Previous Screen" or "Years Since Last Screen" >= 3
      else "No Previous Screen" or "Years Since Last Screen" >= 5
  """
    .trimIndent()

private val SPIKE_VALUE_SETS =
  listOf(
    """{"resourceType":"ValueSet","id":"hiv-positive-status","status":"active",
       "url":"http://smart.who.int/cxca/ValueSet/hiv-positive-status",
       "compose":{"include":[{"system":"http://snomed.info/sct","concept":[{"code":"165816005"}]}]}}""",
    // Spike-local single-code expansion; the real S2 port ships the DAK ValueSet asset.
    """{"resourceType":"ValueSet","id":"cervical-cancer-screening-test","status":"active",
       "url":"http://smart.who.int/cxca/ValueSet/cervical-cancer-screening-test",
       "compose":{"include":[{"system":"http://snomed.info/sct","concept":[{"code":"417036008"}]}]}}""",
  )

private val spikeEvaluator by lazy {
  CqlExpressionEvaluator(DUE_SPIKE_CQL, SPIKE_VALUE_SETS, FHIR_MODELINFO_401_XML)
}

class S2EngineConstructsTest {

  /** Fixed evaluation clock for every test; all interval maths below are relative to this date. */
  private val today = LocalDate(2026, 7, 26)

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
  }

  private fun resource(text: String): Resource = json.decodeFromString(Resource.serializer(), text)

  private fun patient(id: String) =
    resource("""{"resourceType":"Patient","id":"$id","gender":"female","birthDate":"1981-02-02"}""")

  private fun hivObservation(patientId: String) =
    resource(
      """{"resourceType":"Observation","id":"obs-hiv-$patientId","status":"final",
         "code":{"coding":[{"system":"http://snomed.info/sct","code":"165816005"}]},
         "subject":{"reference":"Patient/$patientId"}}"""
    )

  private fun screenObservation(patientId: String, effective: String, suffix: String = "") =
    resource(
      """{"resourceType":"Observation","id":"obs-screen-$patientId$suffix","status":"final",
         "code":{"coding":[{"system":"http://snomed.info/sct","code":"417036008"}]},
         "effectiveDateTime":"$effective",
         "subject":{"reference":"Patient/$patientId"}}"""
    )

  private fun bundleOf(resources: List<Resource>) =
    Bundle(
      type = Enumeration(value = Bundle.BundleType.Collection),
      entry = resources.map { Bundle.Entry(resource = it) },
    )

  private suspend fun evaluate(define: String, observations: List<Resource>): EvaluationResult =
    spikeEvaluator.evaluate(
      ProtocolExpression.Elm(define),
      EvaluationContext(
        subject = patient("s2"),
        variables = mapOf("observations" to bundleOf(observations)),
        today = today,
      ),
    )

  private suspend fun elapsed(observations: List<Resource>): Boolean =
    evaluate("Screening Interval Elapsed", observations).asBoolean()
      ?: error("expected a boolean")

  private suspend fun yearsSince(observations: List<Resource>): Any? =
    when (val r = evaluate("Years Since Last Screen", observations)) {
      is EvaluationResult.Values -> r.value.singleOrNull()
      else -> error("Unexpected result: $r")
    }

  // ---- years between / date from / parameter Today ----

  @Test
  fun shouldComputeWholeYearsSinceLastScreen() = runTest {
    // 2022-01-15 -> 2026-07-26 is 4 full years.
    assertEquals(4, yearsSince(listOf(screenObservation("s2", "2022-01-15"))))
  }

  @Test
  fun shouldNotCountAPartialYear() = runTest {
    // One day short of 3 years: 2023-07-27 -> 2026-07-26 is 2 full years.
    assertEquals(2, yearsSince(listOf(screenObservation("s2", "2023-07-27"))))
  }

  @Test
  fun shouldCountAnExactAnniversaryAsAFullYear() = runTest {
    assertEquals(3, yearsSince(listOf(screenObservation("s2", "2023-07-26"))))
  }

  // ---- null propagation ----

  @Test
  fun shouldPropagateNullWhenNeverScreened() = runTest {
    assertEquals(null, yearsSince(emptyList()))
  }

  // ---- Max over a return-projected collection ----

  @Test
  fun shouldPickTheLatestOfSeveralScreens() = runTest {
    // Max must pick 2024-08-01 (1 full year ago), not 2020-03-01 (6 years ago).
    val screens =
      listOf(
        screenObservation("s2", "2020-03-01", suffix = "-a"),
        screenObservation("s2", "2024-08-01", suffix = "-b"),
      )
    assertEquals(1, yearsSince(screens))
  }

  // ---- The S2 interval table (WLHIV 3y, general 5y), through all constructs at once ----

  @Test
  fun shouldBeElapsedWhenNeverScreened() = runTest {
    assertEquals(true, elapsed(emptyList()))
  }

  @Test
  fun shouldNotBeElapsedForWlhivScreenedTwoYearsAgo() = runTest {
    val obs = listOf(hivObservation("s2"), screenObservation("s2", "2024-08-01"))
    assertEquals(false, elapsed(obs))
  }

  @Test
  fun shouldBeElapsedForWlhivScreenedOverThreeYearsAgo() = runTest {
    val obs = listOf(hivObservation("s2"), screenObservation("s2", "2023-05-01"))
    assertEquals(true, elapsed(obs))
  }

  @Test
  fun shouldNotBeElapsedForGeneralPopulationScreenedFourYearsAgo() = runTest {
    assertEquals(false, elapsed(listOf(screenObservation("s2", "2022-01-15"))))
  }

  @Test
  fun shouldBeElapsedForGeneralPopulationScreenedOverFiveYearsAgo() = runTest {
    assertEquals(true, elapsed(listOf(screenObservation("s2", "2021-05-01"))))
  }
}
