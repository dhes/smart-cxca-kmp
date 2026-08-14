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

import dev.ohs.fhir.model.r4.CommunicationRequest
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.workflow.operation.FhirOperator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json

/**
 * Probes whether `$apply` composes nested PlanDefinitions — the pattern the CPG IG and the WHO
 * DAKs use for pathways: a parent PlanDefinition whose `action.definitionCanonical` references a
 * CHILD PlanDefinition (e.g. CXCAScreeningPathway composing the S1/S2 decision tables), as the
 * legacy android-fhir/clinical-reasoning stack supports.
 *
 * Current kotlin-fhir-workflow behavior, pinned here: the processor resolves every action
 * definition as an ActivityDefinition only, so a child-PlanDefinition reference resolves to null
 * and the action is skipped — the parent "applies" successfully with an EMPTY CarePlan. No
 * error, no warning: a silent no-op. (The CanonicalResolver already exposes
 * resolvePlanDefinition; the processor just never calls it for actions.)
 *
 * When upstream adds nesting, shouldSilentlySkip... breaks loudly and both tests should be
 * rewritten to assert the composed result.
 */
class NestedPlanDefinitionApplyTest {

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
  }

  private fun resource(text: String): Resource = json.decodeFromString(Resource.serializer(), text)

  private val childPlanDefinition =
    """{"resourceType":"PlanDefinition","id":"ChildTable","status":"active",
       "url":"http://hopena.health/cxca-kmp/PlanDefinition/ChildTable",
       "action":[{"id":"child-communicate",
         "definitionCanonical":"http://hopena.health/cxca-kmp/ActivityDefinition/CommunicateChild"}]}"""

  private val parentPlanDefinition =
    """{"resourceType":"PlanDefinition","id":"PathwayParent","status":"active",
       "url":"http://hopena.health/cxca-kmp/PlanDefinition/PathwayParent",
       "action":[{"id":"parent-step-1",
         "definitionCanonical":"http://hopena.health/cxca-kmp/PlanDefinition/ChildTable"}]}"""

  private val activityDefinition =
    """{"resourceType":"ActivityDefinition","id":"CommunicateChild","status":"active",
       "url":"http://hopena.health/cxca-kmp/ActivityDefinition/CommunicateChild",
       "kind":"CommunicationRequest","intent":"proposal"}"""

  private val patient =
    """{"resourceType":"Patient","id":"nest-probe","gender":"female","birthDate":"1981-02-02"}"""

  private suspend fun applyOf(canonical: String): dev.ohs.fhir.model.r4.CarePlan {
    val repository = MapRepository()
    repository.create(resource(childPlanDefinition))
    repository.create(resource(parentPlanDefinition))
    repository.create(resource(activityDefinition))
    return FhirOperator(repository)
      .generateCarePlan(
        planDefinitionCanonical = canonical,
        subject = resource(patient),
        today = LocalDate(2026, 8, 14),
      )
  }

  @Test
  fun shouldInstantiateTheChildsRequestWhenAppliedDirectly() = runTest {
    // Harness sanity: the child table works on its own.
    val carePlan = applyOf("http://hopena.health/cxca-kmp/PlanDefinition/ChildTable")
    assertEquals(1, carePlan.contained.filterIsInstance<CommunicationRequest>().size)
  }

  @Test
  fun shouldSilentlySkipAChildPlanDefinitionAction() = runTest {
    // The pathway pattern: parent action -> child PlanDefinition. Documented CURRENT behavior:
    // the action's canonical only resolves as an ActivityDefinition, so the child is skipped
    // and the CarePlan comes back empty — successfully, with no error. If this test fails,
    // upstream has (deliberately or not) changed nesting behavior: rewrite it.
    val carePlan = applyOf("http://hopena.health/cxca-kmp/PlanDefinition/PathwayParent")
    assertTrue(
      carePlan.contained.filterIsInstance<CommunicationRequest>().isEmpty(),
      "nested child PlanDefinition unexpectedly produced a request — nesting support has arrived",
    )
  }
}
