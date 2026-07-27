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
import dev.ohs.fhir.model.r4.CommunicationRequest
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.workflow.expression.EvaluationContext
import dev.ohs.fhir.workflow.expression.EvaluationResult
import dev.ohs.fhir.workflow.expression.ExpressionEvaluatorRouter
import dev.ohs.fhir.workflow.expression.ProtocolExpression
import dev.ohs.fhir.workflow.operation.FhirOperator
import dev.ohs.fhir.workflow.repository.WorkflowRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json

/** Near-verbatim CXCAEligibilityLogic — same text the probe runs (see dhes/cql-v5-probe). */
private val ELIGIBILITY_CQL =
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
  """
    .trimIndent()

private val VALUE_SETS =
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
  )

class CqlExpressionEvaluatorTest {

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
  }

  private fun resource(text: String): Resource = json.decodeFromString(Resource.serializer(), text)

  private fun modelInfoXml(): String =
    checkNotNull(javaClass.classLoader.getResourceAsStream("fhir-modelinfo-4.0.1.xml")) {
        "fhir-modelinfo-4.0.1.xml not on test classpath"
      }
      .bufferedReader()
      .readText()

  private fun evaluator() = CqlExpressionEvaluator(ELIGIBILITY_CQL, VALUE_SETS, modelInfoXml())

  private fun patient(id: String, birthDate: String) =
    resource(
      """{"resourceType":"Patient","id":"$id","gender":"female","birthDate":"$birthDate"}"""
    )

  private fun hivObservation(patientId: String) =
    resource(
      """{"resourceType":"Observation","id":"obs-hiv-$patientId","status":"final",
         "code":{"coding":[{"system":"http://snomed.info/sct","code":"165816005"}]},
         "subject":{"reference":"Patient/$patientId"}}"""
    )

  private fun hysterectomyCondition(patientId: String) =
    resource(
      """{"resourceType":"Condition","id":"cond-hyst-$patientId",
         "code":{"coding":[{"system":"http://snomed.info/sct","code":"428078001"}]},
         "subject":{"reference":"Patient/$patientId"}}"""
    )

  private fun bundleOf(vararg resources: Resource) =
    Bundle(
      type = Enumeration(value = Bundle.BundleType.Collection),
      entry = resources.map { Bundle.Entry(resource = it) },
    )

  private suspend fun eligibilityOf(
    subject: Resource,
    observations: List<Resource> = emptyList(),
    conditions: List<Resource> = emptyList(),
  ): String {
    val result =
      evaluator()
        .evaluate(
          ProtocolExpression.Elm("Eligibility status"),
          EvaluationContext(
            subject = subject,
            variables =
              mapOf(
                "observations" to bundleOf(*observations.toTypedArray()),
                "conditions" to bundleOf(*conditions.toTypedArray()),
              ),
            today = LocalDate(2026, 7, 26),
          ),
        )
    return (result as? EvaluationResult.Values)?.value?.singleOrNull() as? String
      ?: error("Unexpected result: $result")
  }

  // ---- The CXCA.S1.DT truth table, evaluated by REAL CQL through the seam ----

  @Test
  fun shouldBeEligibleWhenWlhivAge27() = runTest {
    val amina = patient("cxca-wlhiv-27", "1999-01-15")
    assertEquals("Eligible", eligibilityOf(amina, observations = listOf(hivObservation("cxca-wlhiv-27"))))
  }

  @Test
  fun shouldNotBeEligibleWhenGeneralPopulationAge27() = runTest {
    assertEquals("Not eligible", eligibilityOf(patient("cxca-gen-27", "1999-03-10")))
  }

  @Test
  fun shouldBeEligibleWhenGeneralPopulationAge45() = runTest {
    assertEquals("Eligible", eligibilityOf(patient("cxca-gen-45", "1981-02-02")))
  }

  @Test
  fun shouldNotBeEligibleWhenPostHysterectomy() = runTest {
    val dineo = patient("cxca-hyst-45", "1981-05-20")
    assertEquals(
      "Not eligible",
      eligibilityOf(dineo, conditions = listOf(hysterectomyCondition("cxca-hyst-45"))),
    )
  }

  @Test
  fun shouldNotBeEligibleWhenOver65() = runTest {
    assertEquals("Not eligible", eligibilityOf(patient("cxca-elder-70", "1956-02-01")))
  }

  @Test
  fun shouldReturnBoolForBooleanDefines() = runTest {
    val amina = patient("cxca-wlhiv-27", "1999-01-15")
    val result =
      evaluator()
        .evaluate(
          ProtocolExpression.Elm("Living with HIV"),
          EvaluationContext(
            subject = amina,
            variables = mapOf("observations" to bundleOf(hivObservation("cxca-wlhiv-27"))),
            today = LocalDate(2026, 7, 26),
          ),
        )
    assertEquals(true, result.asBoolean())
  }

  @Test
  fun shouldStillEvaluateFhirPathOnThisClasspath() = runTest {
    // Both parsers share one classpath in the router; the forced antlr-kotlin 1.0.3 (which the
    // CQL compiler needs) must not break the FHIRPath engine.
    val result =
      dev.ohs.fhir.workflow.expression.FhirPathExpressionEvaluator()
        .evaluate(
          ProtocolExpression.FhirPath("gender = 'female'"),
          EvaluationContext(
            subject = patient("cxca-wlhiv-27", "1999-01-15"),
            today = LocalDate(2026, 7, 26),
          ),
        )
    assertEquals(true, result.asBoolean())
  }

  // ---- End to end: PlanDefinition/$apply with text/cql dynamicValues through FhirOperator ----

  private val planDefinition =
    """{"resourceType":"PlanDefinition","id":"CXCAS1DTCql","status":"active",
       "url":"http://hopena.health/cxca-kmp/PlanDefinition/CXCAS1DTCql",
       "action":[{"id":"s1-communicate",
         "definitionCanonical":"http://hopena.health/cxca-kmp/ActivityDefinition/CommunicateEligibility",
         "dynamicValue":[{"path":"reasonCode[0].text",
           "expression":{"language":"text/cql","expression":"Eligibility status"}}]}]}"""

  private val activityDefinition =
    """{"resourceType":"ActivityDefinition","id":"CommunicateEligibility","status":"active",
       "url":"http://hopena.health/cxca-kmp/ActivityDefinition/CommunicateEligibility",
       "kind":"CommunicationRequest","intent":"proposal"}"""

  @Test
  fun shouldCarryCqlEligibilityThroughApply() = runTest {
    val repository = MapRepository()
    repository.create(resource(planDefinition))
    repository.create(resource(activityDefinition))

    val operator =
      FhirOperator(repository, ExpressionEvaluatorRouter(elm = evaluator()))

    val carePlan =
      operator.generateCarePlan(
        planDefinitionCanonical = "http://hopena.health/cxca-kmp/PlanDefinition/CXCAS1DTCql",
        subject = patient("cxca-wlhiv-27", "1999-01-15"),
        variables = mapOf("observations" to bundleOf(hivObservation("cxca-wlhiv-27"))),
        today = LocalDate(2026, 7, 26),
      )

    val request = carePlan.contained.filterIsInstance<CommunicationRequest>().firstOrNull()
    assertNotNull(request, "no CommunicationRequest generated by \$apply")
    assertEquals("Eligible", request.reasonCode.firstOrNull()?.text?.value)
  }
}

/** Minimal in-memory [WorkflowRepository] — just what CanonicalResolver needs for these tests. */
private class MapRepository : WorkflowRepository {
  private val resources = mutableMapOf<String, Resource>()

  private fun typeOf(resource: Resource) =
    resource::class.simpleName ?: error("Unable to determine resource type")

  override suspend fun read(type: String, id: String): Resource? = resources["$type/$id"]

  override suspend fun create(resource: Resource): String {
    val id = requireNotNull(resource.id)
    resources["${typeOf(resource)}/$id"] = resource
    return id
  }

  override suspend fun update(resource: Resource) {
    create(resource)
  }

  override suspend fun delete(type: String, id: String) {
    resources.remove("$type/$id")
  }

  override suspend fun searchByReferenceParam(
    type: String,
    param: String,
    referenceValue: String,
  ): List<Resource> = emptyList()

  override suspend fun searchByUri(type: String, param: String, uri: String): List<Resource> =
    resources.values.filter { r ->
      typeOf(r) == type &&
        when (r) {
          is dev.ohs.fhir.model.r4.PlanDefinition -> r.url?.value == uri
          is dev.ohs.fhir.model.r4.ActivityDefinition -> r.url?.value == uri
          else -> false
        }
    }
}
