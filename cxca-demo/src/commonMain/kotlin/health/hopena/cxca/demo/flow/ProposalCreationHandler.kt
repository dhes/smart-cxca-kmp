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
package health.hopena.cxca.demo.flow

import dev.ohs.fhir.model.r4.Bundle
import dev.ohs.fhir.model.r4.CommunicationRequest
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.Extension
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.fhir.workflow.activity.resource.request.CPGCommunicationRequest
import dev.ohs.fhir.workflow.expression.ExpressionEvaluatorRouter
import dev.ohs.fhir.workflow.operation.FhirOperator
import dev.ohs.fhir.workflow.repository.WorkflowRepository
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlinx.serialization.json.Json

/**
 * Installs a [DemoConfiguration]'s knowledge artifacts and clinical inputs, then generates the S1
 * eligibility proposal by running `PlanDefinition/$apply` over them.
 *
 * The S1 FHIRPath expressions cannot retrieve data themselves (no CQL retrieve), so this handler
 * prefetches the patient's Observations and Conditions and binds them as the collection Bundles
 * the PlanDefinition reads through `%observations` and `%conditions`.
 */
class ProposalCreationHandler(
  private val repository: WorkflowRepository,
  private val assets: AssetReader = bundledAssets,
  private val today: LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault()),
) {
  private val operators = mutableMapOf<String, FhirOperator>()

  /**
   * FHIRPath scenarios use the default router; CQL scenarios route text/cql to the platform's
   * CQL evaluator (cxca-cql over the cqframework v5 engine), built once per configuration —
   * constructing it compiles the CQL library and parses the FHIR modelinfo.
   */
  private suspend fun operatorFor(configuration: DemoConfiguration): FhirOperator =
    operators.getOrPut(configuration.id) {
      val cqlEvaluator =
        configuration.cql?.let { scenario ->
          createCqlEvaluator(
            cqlLibrarySource = assets(scenario.cqlLibraryPath),
            valueSetJsons = scenario.valueSetPaths.map { assets(it) },
            modelInfoXml = assets(scenario.modelInfoPath),
          )
        }
      if (cqlEvaluator != null) {
        FhirOperator(repository, ExpressionEvaluatorRouter(elm = cqlEvaluator))
      } else {
        FhirOperator(repository)
      }
    }

  /**
   * True once [installDependencies] has put the configuration's PlanDefinition AND its patient in
   * the repository. All scenarios share one PlanDefinition, so the patient must be checked too —
   * a different scenario may have installed the plan already.
   */
  suspend fun checkInstalledDependencies(configuration: DemoConfiguration): Boolean =
    repository.read("Patient", configuration.patientId) != null &&
      repository
        .searchByUri("PlanDefinition", "url", configuration.planDefinitionCanonical)
        .isNotEmpty()

  /** Installs each of the configuration's resources that is not already in the repository. */
  suspend fun installDependencies(configuration: DemoConfiguration) {
    (listOf(
        configuration.patientPath,
        configuration.planDefinitionPath,
        configuration.activityDefinitionPath,
      ) + configuration.additionalResourcePaths)
      .forEach { path ->
        val resource = parse(assets(path))
        val type = resource::class.simpleName ?: return@forEach
        val installed = resource.id?.let { repository.read(type, it) } != null
        if (!installed) repository.create(resource)
      }
  }

  /**
   * Runs `$apply` for the configuration's PlanDefinition and persists the CommunicationRequest it
   * generates — the S1 eligibility determination and its guidance, as a CPG communication proposal.
   */
  suspend fun generateProposal(configuration: DemoConfiguration): CPGCommunicationRequest? {
    val patient =
      repository.read("Patient", configuration.patientId) as? Patient
        ?: error("Patient/${configuration.patientId} is not installed")

    val carePlan =
      operatorFor(configuration).generateCarePlan(
        planDefinitionCanonical = configuration.planDefinitionCanonical,
        subject = patient,
        variables =
          mapOf(
            "observations" to collectionBundle("Observation", configuration.patientId),
            "conditions" to collectionBundle("Condition", configuration.patientId),
          ),
        today = today,
      )

    val generated =
      carePlan.contained.filterIsInstance<CommunicationRequest>().firstOrNull() ?: return null

    // $apply derives a stable id from the plan and action, so give each proposal a fresh one:
    // restarting the flow generates the same request again, and the repository would collide.
    // The processor does not stamp the request-intent extension on CommunicationRequests (no
    // native intent element); ActivityFlow phase detection needs it, so stamp it here in the
    // same shape the library's CPGCommunicationRequest reads back.
    val intentExtension =
      Extension(
        url = "http://hl7.org/fhir/StructureDefinition/request-intent",
        value = Extension.Value.String(FhirString(value = "proposal")),
      )
    val proposal =
      CPGCommunicationRequest(
        generated.copy(
          id = Uuid.random().toString(),
          extension = generated.extension + intentExtension,
        )
      )
    repository.create(proposal.resource)
    return proposal
  }

  /**
   * The patient's resources of [type], as the collection Bundle the S1 expressions read through
   * `%observations` / `%conditions`.
   */
  private suspend fun collectionBundle(type: String, patientId: String): Bundle {
    val resources = repository.searchByReferenceParam(type, "subject", "Patient/$patientId")
    return Bundle(
      type = Enumeration(value = Bundle.BundleType.Collection),
      entry = resources.map { Bundle.Entry(resource = it) },
    )
  }

  private fun parse(json: String): Resource = demoJson.decodeFromString(Resource.serializer(), json)
}

private val demoJson = Json {
  ignoreUnknownKeys = true
  encodeDefaults = false
  explicitNulls = false
}
