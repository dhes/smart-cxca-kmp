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

/**
 * A selectable activity flow: the knowledge artifacts to install and the patient to run them
 * against. Extends the upstream demo Configuration with [additionalResourcePaths] — the clinical
 * inputs (Observations, Conditions) the S1 eligibility expressions read via prefetch variables.
 */
data class DemoConfiguration(
  val id: String,
  val description: String,
  val patientId: String,
  val patientName: String,
  val patientPath: String,
  val planDefinitionPath: String,
  val planDefinitionCanonical: String,
  val activityDefinitionPath: String,
  val additionalResourcePaths: List<String> = emptyList(),
)

private const val CXCA_S1_PD = "files/pd/CXCAS1DT.json"
private const val CXCA_S1_PD_CANONICAL = "http://hopena.health/cxca-kmp/PlanDefinition/CXCAS1DT"
private const val CXCA_S1_AD = "files/ad/CommunicateEligibility.json"

/** WLHIV, age 27: HIV-positive lowers the screening start age to 25 — Eligible. */
val WLHIV_27 =
  DemoConfiguration(
    id = "id_cxca_wlhiv_27",
    description = "WLHIV, 27 — Eligible",
    patientId = "cxca-wlhiv-27",
    patientName = "Amina Achieng (27, WLHIV)",
    patientPath = "files/patient/PatientWlhiv27.json",
    planDefinitionPath = CXCA_S1_PD,
    planDefinitionCanonical = CXCA_S1_PD_CANONICAL,
    activityDefinitionPath = CXCA_S1_AD,
    additionalResourcePaths = listOf("files/patient/ObservationHivWlhiv27.json"),
  )

/** General population, age 27: start age is 30 — Not yet eligible. */
val GENERAL_27 =
  DemoConfiguration(
    id = "id_cxca_gen_27",
    description = "General, 27 — Not yet eligible",
    patientId = "cxca-gen-27",
    patientName = "Beatrice Wanjiru (27)",
    patientPath = "files/patient/PatientGen27.json",
    planDefinitionPath = CXCA_S1_PD,
    planDefinitionCanonical = CXCA_S1_PD_CANONICAL,
    activityDefinitionPath = CXCA_S1_AD,
  )

/** General population, age 45 — Eligible. */
val GENERAL_45 =
  DemoConfiguration(
    id = "id_cxca_gen_45",
    description = "General, 45 — Eligible",
    patientId = "cxca-gen-45",
    patientName = "Chidinma Okonkwo (45)",
    patientPath = "files/patient/PatientGen45.json",
    planDefinitionPath = CXCA_S1_PD,
    planDefinitionCanonical = CXCA_S1_PD_CANONICAL,
    activityDefinitionPath = CXCA_S1_AD,
  )

/** Total hysterectomy, age 45: no residual cervix on record — Not eligible. */
val HYSTERECTOMY_45 =
  DemoConfiguration(
    id = "id_cxca_hyst_45",
    description = "Post-hysterectomy, 45 — Not eligible",
    patientId = "cxca-hyst-45",
    patientName = "Dineo Dlamini (45, post-hysterectomy)",
    patientPath = "files/patient/PatientHyst45.json",
    planDefinitionPath = CXCA_S1_PD,
    planDefinitionCanonical = CXCA_S1_PD_CANONICAL,
    activityDefinitionPath = CXCA_S1_AD,
    additionalResourcePaths = listOf("files/patient/ConditionHystHyst45.json"),
  )

val DEMO_CONFIGURATIONS = listOf(WLHIV_27, GENERAL_27, GENERAL_45, HYSTERECTOMY_45)
