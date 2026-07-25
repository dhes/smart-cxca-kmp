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

import dev.ohs.fhir.model.r4.CodeableConcept
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.MedicationRequest
import dev.ohs.fhir.model.r4.Reference
import dev.ohs.fhir.model.r4.String as FhirString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class EngineWorkflowRepositoryTest {
  @Test
  fun shouldReturnResourceWhenReadAfterCreate() = runTest {
    val repo = EngineWorkflowRepository(fhirEngine())
    repo.create(
      MedicationRequest(
        id = "mr-1",
        status = Enumeration(value = MedicationRequest.MedicationrequestStatus.Active),
        intent = Enumeration(value = MedicationRequest.MedicationRequestIntent.Proposal),
        medication = MedicationRequest.Medication.CodeableConcept(CodeableConcept()),
        subject = Reference(reference = FhirString(value = "Patient/p1")),
      )
    )
    assertEquals("mr-1", repo.read("MedicationRequest", "mr-1")?.id)
  }
}
