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
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json

/**
 * Multi-library resolution: the near-verbatim CXCA.S2.DT library `include`s the S1 eligibility
 * library, and the evaluator resolves the include from [includedLibrarySources]. Deviations from
 * the DAK source are the same two as the S1 port: explicit `.value` access (no FHIRHelpers) and
 * no other changes.
 *
 * Cross-library addressing rides on `Expression.reference`: the workflow seam (patched on the
 * `expression-reference` branch of kotlin-fhir-workflow) carries the Library canonical through
 * `ProtocolExpression.Elm`, and the evaluator picks the entry library from it — canonical tail
 * segment = CQL library name, per the DAK convention. Without a reference, the constructor's
 * entry library holds, so a bare included-library define name still fails.
 */
private val S1_SOURCE =
  """
  library CXCAEligibilityLogic version '1.0.0'
  using FHIR version '4.0.1'
  valueset "HIV-positive status": 'http://smart.who.int/cxca/ValueSet/hiv-positive-status'
  valueset "Congenital absence of cervix": 'http://smart.who.int/cxca/ValueSet/absence-of-cervix'
  valueset "Total hysterectomy": 'http://smart.who.int/cxca/ValueSet/total-hysterectomy'
  context Patient
  define "Female Sex": Patient.gender.value = 'female'
  define "Congenital Absence of Cervix": exists ([Condition: "Congenital absence of cervix"])
  define "Acquired Absence of Cervix": exists ([Condition: "Total hysterectomy"])
  define "Has Cervix": "Female Sex" and not "Congenital Absence of Cervix" and not "Acquired Absence of Cervix"
  define "No Cervix": not "Has Cervix"
  define "Living with HIV":
    exists ([Observation: "HIV-positive status"] O where O.status.value in { 'final', 'amended' })
  define "Current Patient Age In Years": AgeInYears()
  define "Screening Start Age": if "Living with HIV" then 25 else 30
  define "In Screening Age Range":
    "Current Patient Age In Years" >= "Screening Start Age" and "Current Patient Age In Years" <= 65
  define "Below Start Age": "Current Patient Age In Years" < "Screening Start Age"
  define "Age over 65": "Current Patient Age In Years" > 65
  define "Eligibility status":
    if "No Cervix" then 'Not eligible'
    else if "Below Start Age" then 'Not eligible'
    else if "Age over 65" then 'Not eligible'
    else if "In Screening Age Range" then 'Eligible'
    else 'Not eligible'
  define "Eligible": "Eligibility status" = 'Eligible'
  """
    .trimIndent()

private val S2_SOURCE =
  """
  library CXCADueForScreeningLogic version '0.1.0'
  using FHIR version '4.0.1'
  include CXCAEligibilityLogic version '1.0.0' called Eligibility
  valueset "HIV-positive status": 'http://smart.who.int/cxca/ValueSet/hiv-positive-status'
  valueset "Cervical cancer screening test": 'http://smart.who.int/cxca/ValueSet/cervical-cancer-screening-test'
  parameter "Today" default Today()
  context Patient
  define "Eligible":
    Eligibility."Eligible"
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
  define "Screening recommendation status":
    if not "Eligible" then 'Not applicable'
    else if "Screening Interval Elapsed" then 'Due'
    else 'Not due'
  define "Guidance":
    case
      when not "Eligible" then 'Client is not in the screening target population; due-for-screening does not apply.'
      when "Living with HIV" and "Screening Interval Elapsed" then 'Women living with HIV: offer HPV-DNA screening and re-screen every 3 years.'
      when "Screening Interval Elapsed" then 'Offer HPV-DNA testing as the primary screening test (general population interval: every 5 years).'
      else 'Client was screened within the recommended interval; no screening required at this visit.'
    end
  """
    .trimIndent()

private val S2_VALUE_SETS =
  listOf(
    """{"resourceType":"ValueSet","id":"hiv-positive-status","status":"active",
       "url":"http://smart.who.int/cxca/ValueSet/hiv-positive-status",
       "compose":{"include":[{"system":"http://snomed.info/sct","concept":[{"code":"165816005"}]}]}}""",
    """{"resourceType":"ValueSet","id":"absence-of-cervix","status":"active",
       "url":"http://smart.who.int/cxca/ValueSet/absence-of-cervix",
       "compose":{"include":[{"system":"http://snomed.info/sct","concept":[{"code":"37687000"}]}]}}""",
    """{"resourceType":"ValueSet","id":"total-hysterectomy","status":"active",
       "url":"http://smart.who.int/cxca/ValueSet/total-hysterectomy",
       "compose":{"include":[{"system":"http://snomed.info/sct","concept":[{"code":"428078001"}]}]}}""",
    """{"resourceType":"ValueSet","id":"cervical-cancer-screening-test","status":"active",
       "url":"http://smart.who.int/cxca/ValueSet/cervical-cancer-screening-test",
       "compose":{"include":[{"system":"http://snomed.info/sct","concept":[{"code":"417036008"}]}]}}""",
  )

/**
 * A minimal S3 stand-in for the tri-library test: nothing includes it — it is reachable ONLY by
 * Expression.reference, which is the point.
 */
private val S3_SOURCE =
  """
  library CXCAScreeningResultLogic version '0.1.0'
  using FHIR version '4.0.1'
  valueset "HPV-positive result": 'http://smart.who.int/cxca/ValueSet/hpv-positive-result'
  context Patient
  define "HPV Positive":
    exists ([Observation: "HPV-positive result"] O where O.status.value in { 'final', 'amended' })
  define "Screening result recommendation status":
    if "HPV Positive" then 'Refer for triage/treatment' else 'Not applicable'
  """
    .trimIndent()

private val s2Evaluator by lazy {
  CqlExpressionEvaluator(
    S2_SOURCE,
    S2_VALUE_SETS +
      """{"resourceType":"ValueSet","id":"hpv-positive-result","status":"active",
         "url":"http://smart.who.int/cxca/ValueSet/hpv-positive-result",
         "compose":{"include":[{"system":"http://snomed.info/sct","concept":[{"code":"1269497006"}]}]}}""",
    FHIR_MODELINFO_401_XML,
    includedLibrarySources = listOf(S1_SOURCE, S3_SOURCE),
  )
}

class S2IncludeResolutionTest {

  private val today = LocalDate(2026, 7, 26)

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
  }

  private fun resource(text: String): Resource = json.decodeFromString(Resource.serializer(), text)

  /** Born 1981-02-02: age 45 at [today] — eligible unless a condition removes the cervix. */
  private fun patient(id: String) =
    resource("""{"resourceType":"Patient","id":"$id","gender":"female","birthDate":"1981-02-02"}""")

  private fun hivObservation(patientId: String) =
    resource(
      """{"resourceType":"Observation","id":"obs-hiv-$patientId","status":"final",
         "code":{"coding":[{"system":"http://snomed.info/sct","code":"165816005"}]},
         "subject":{"reference":"Patient/$patientId"}}"""
    )

  private fun screenObservation(patientId: String, effective: String) =
    resource(
      """{"resourceType":"Observation","id":"obs-screen-$patientId","status":"final",
         "code":{"coding":[{"system":"http://snomed.info/sct","code":"417036008"}]},
         "effectiveDateTime":"$effective",
         "subject":{"reference":"Patient/$patientId"}}"""
    )

  private fun hysterectomyCondition(patientId: String) =
    resource(
      """{"resourceType":"Condition","id":"cond-hyst-$patientId",
         "code":{"coding":[{"system":"http://snomed.info/sct","code":"428078001"}]},
         "subject":{"reference":"Patient/$patientId"}}"""
    )

  private fun bundleOf(resources: List<Resource>) =
    Bundle(
      type = Enumeration(value = Bundle.BundleType.Collection),
      entry = resources.map { Bundle.Entry(resource = it) },
    )

  private suspend fun evaluate(
    define: String,
    observations: List<Resource> = emptyList(),
    conditions: List<Resource> = emptyList(),
    reference: String? = null,
  ): EvaluationResult =
    s2Evaluator.evaluate(
      ProtocolExpression.Elm(define, reference),
      EvaluationContext(
        subject = patient("s2"),
        variables =
          mapOf(
            "observations" to bundleOf(observations),
            "conditions" to bundleOf(conditions),
          ),
        today = today,
      ),
    )

  private suspend fun statusOf(
    observations: List<Resource> = emptyList(),
    conditions: List<Resource> = emptyList(),
  ): String {
    val result = evaluate("Screening recommendation status", observations, conditions)
    return (result as? EvaluationResult.Values)?.value?.singleOrNull() as? String
      ?: error("Unexpected result: $result")
  }

  // ---- The CXCA.S2.DT truth table, with S1 resolved through the include ----

  @Test
  fun shouldBeNotApplicableWhenIneligible() = runTest {
    assertEquals(
      "Not applicable",
      statusOf(
        observations = listOf(screenObservation("s2", "2020-05-01")),
        conditions = listOf(hysterectomyCondition("s2")),
      ),
    )
  }

  @Test
  fun shouldBeDueWhenEligibleAndNeverScreened() = runTest {
    assertEquals("Due", statusOf())
  }

  @Test
  fun shouldBeDueForGeneralPopulationScreenedOverFiveYearsAgo() = runTest {
    assertEquals("Due", statusOf(observations = listOf(screenObservation("s2", "2021-05-01"))))
  }

  @Test
  fun shouldBeNotDueForGeneralPopulationScreenedFourYearsAgo() = runTest {
    assertEquals("Not due", statusOf(observations = listOf(screenObservation("s2", "2022-01-15"))))
  }

  /** The discriminating pair: the same screen date flips Due/Not due on HIV status alone. */
  @Test
  fun shouldBeDueForWlhivScreenedFourYearsAgo() = runTest {
    assertEquals(
      "Due",
      statusOf(observations = listOf(hivObservation("s2"), screenObservation("s2", "2022-01-15"))),
    )
  }

  @Test
  fun shouldGiveWlhivGuidanceThroughTheInclude() = runTest {
    val result =
      evaluate(
        "Guidance",
        observations = listOf(hivObservation("s2"), screenObservation("s2", "2022-01-15")),
      )
    assertEquals(
      "Women living with HIV: offer HPV-DNA screening and re-screen every 3 years.",
      (result as? EvaluationResult.Values)?.value?.singleOrNull(),
    )
  }

  // ---- Expression.reference addresses the entry library per expression ----

  @Test
  fun shouldStillFailForAnIncludedDefineWithoutAReference() = runTest {
    // Without a reference the constructor's entry library holds, and "Eligibility status" is
    // not among its defines.
    assertIs<EvaluationResult.Failure>(evaluate("Eligibility status"))
  }

  @Test
  fun shouldEvaluateAnIncludedLibrarysDefineThroughItsReference() = runTest {
    val result =
      evaluate(
        "Eligibility status",
        reference = "http://smart.who.int/cxca/Library/CXCAEligibilityLogic",
      )
    assertEquals("Eligible", (result as? EvaluationResult.Values)?.value?.singleOrNull())
  }

  @Test
  fun shouldFailWithTheKnownLibrariesForAnUnknownReference() = runTest {
    val result =
      evaluate("Anything", reference = "http://smart.who.int/cxca/Library/NoSuchLibrary")
    val failure = assertIs<EvaluationResult.Failure>(result)
    check("NoSuchLibrary" in failure.message && "CXCAEligibilityLogic" in failure.message) {
      "Failure should name the unknown library and the known ones: ${failure.message}"
    }
  }

  @Test
  fun shouldHoldAllThreeTablesInOneEvaluatorRoutedByReference() = runTest {
    // S3 is neither the entry library nor included by anything — only the reference reaches it.
    // One evaluator, the whole DAK, PlanDefinitions choosing their library per expression.
    val hpvPositive =
      resource(
        """{"resourceType":"Observation","id":"obs-hpv-s2","status":"final",
           "code":{"coding":[{"system":"http://snomed.info/sct","code":"1269497006"}]},
           "subject":{"reference":"Patient/s2"}}"""
      )
    val result =
      evaluate(
        "Screening result recommendation status",
        observations = listOf(hpvPositive),
        reference = "http://smart.who.int/cxca/Library/CXCAScreeningResultLogic",
      )
    assertEquals(
      "Refer for triage/treatment",
      (result as? EvaluationResult.Values)?.value?.singleOrNull(),
    )
  }

  // ---- End to end: $apply carries Expression.reference through the patched seam ----

  @Test
  fun shouldRouteAcrossLibrariesInsideApply() = runTest {
    val planDefinition =
      """{"resourceType":"PlanDefinition","id":"CXCAS2DTCql","status":"active",
         "url":"http://hopena.health/cxca-kmp/PlanDefinition/CXCAS2DTCql",
         "action":[{"id":"s2-communicate",
           "definitionCanonical":"http://hopena.health/cxca-kmp/ActivityDefinition/CommunicateDue",
           "dynamicValue":[{"path":"reasonCode[0].text",
             "expression":{"language":"text/cql","expression":"Eligibility status",
               "reference":"http://smart.who.int/cxca/Library/CXCAEligibilityLogic"}}]}]}"""
    val activityDefinition =
      """{"resourceType":"ActivityDefinition","id":"CommunicateDue","status":"active",
         "url":"http://hopena.health/cxca-kmp/ActivityDefinition/CommunicateDue",
         "kind":"CommunicationRequest","intent":"proposal"}"""

    val repository = MapRepository()
    repository.create(resource(planDefinition))
    repository.create(resource(activityDefinition))

    val carePlan =
      dev.ohs.fhir.workflow.operation
        .FhirOperator(
          repository,
          dev.ohs.fhir.workflow.expression.ExpressionEvaluatorRouter(elm = s2Evaluator),
        )
        .generateCarePlan(
          planDefinitionCanonical = "http://hopena.health/cxca-kmp/PlanDefinition/CXCAS2DTCql",
          subject = patient("s2"),
          variables = mapOf("observations" to bundleOf(emptyList())),
          today = today,
        )

    val request =
      carePlan.contained
        .filterIsInstance<dev.ohs.fhir.model.r4.CommunicationRequest>()
        .firstOrNull() ?: error("no CommunicationRequest generated by \$apply")
    // The define lives in the INCLUDED library; only the carried reference makes this resolve.
    assertEquals("Eligible", request.reasonCode.firstOrNull()?.text?.value)
  }
}
