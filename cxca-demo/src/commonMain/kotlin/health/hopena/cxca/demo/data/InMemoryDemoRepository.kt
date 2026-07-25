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
package health.hopena.cxca.demo.data

import dev.ohs.fhir.model.r4.ActivityDefinition
import dev.ohs.fhir.model.r4.Communication
import dev.ohs.fhir.model.r4.CommunicationRequest
import dev.ohs.fhir.model.r4.Condition
import dev.ohs.fhir.model.r4.Observation
import dev.ohs.fhir.model.r4.PlanDefinition
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.workflow.repository.WorkflowRepository

/**
 * Pure-KMP [WorkflowRepository] backed by an in-memory map, for platforms without
 * `dev.ohs.fhir:fhir-engine` (e.g. wasmJs).
 *
 * Without the engine's indexer there is no generic way to read a search parameter off an arbitrary
 * resource, so search covers exactly what the demo needs: resolving the PlanDefinition and
 * ActivityDefinition the proposal is generated from, and finding a patient's MedicationRequests to
 * feed `$apply`'s applicability condition.
 */
class InMemoryDemoRepository : WorkflowRepository {
  private val resources = mutableMapOf<String, Resource>()

  override suspend fun read(type: String, id: String): Resource? = resources["$type/$id"]

  override suspend fun create(resource: Resource): String {
    val id = requireNotNull(resource.id) { "Resource must have an id to be created" }
    resources["${typeOf(resource)}/$id"] = resource
    return id
  }

  override suspend fun update(resource: Resource) {
    val id = requireNotNull(resource.id) { "Resource must have an id to be updated" }
    resources["${typeOf(resource)}/$id"] = resource
  }

  override suspend fun delete(type: String, id: String) {
    resources.remove("$type/$id")
  }

  override suspend fun searchByReferenceParam(
    type: String,
    param: String,
    referenceValue: String,
  ): List<Resource> = ofType(type).filter { referenceParam(it, param) == referenceValue }

  override suspend fun searchByUri(type: String, param: String, uri: String): List<Resource> =
    ofType(type).filter { uriParam(it, param) == uri }

  private fun typeOf(resource: Resource) =
    resource::class.simpleName ?: error("Unable to determine resource type")

  private fun ofType(type: String) = resources.values.filter { typeOf(it) == type }

  private fun referenceParam(resource: Resource, param: String): String? =
    when {
      param != "subject" -> null
      resource is CommunicationRequest -> resource.subject?.reference?.value
      resource is Communication -> resource.subject?.reference?.value
      resource is Observation -> resource.subject?.reference?.value
      resource is Condition -> resource.subject?.reference?.value
      else -> null
    }

  private fun uriParam(resource: Resource, param: String): String? =
    when {
      resource is PlanDefinition && param == "url" -> resource.url?.value
      resource is ActivityDefinition && param == "url" -> resource.url?.value
      else -> null
    }
}
