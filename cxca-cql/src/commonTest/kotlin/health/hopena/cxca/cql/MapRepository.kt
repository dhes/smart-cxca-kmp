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

import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.workflow.repository.WorkflowRepository

/** Minimal in-memory [WorkflowRepository] — just what CanonicalResolver needs for these tests. */
internal class MapRepository : WorkflowRepository {
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
