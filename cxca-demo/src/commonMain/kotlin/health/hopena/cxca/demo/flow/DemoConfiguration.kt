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
  /** Present when the scenario's PlanDefinition expressions are text/cql — see [CqlScenario]. */
  val cql: CqlScenario? = null,
)

/**
 * The assets a CQL-evaluated scenario needs: the entry library the PlanDefinition's text/cql
 * define names resolve against, the libraries it `include`s, the ValueSets their `valueset`
 * declarations expand from, and the FHIR modelinfo the compiler and data providers share.
 */
data class CqlScenario(
  val cqlLibraryPath: String,
  val valueSetPaths: List<String>,
  val includedLibraryPaths: List<String> = emptyList(),
  val modelInfoPath: String = "files/modelinfo/fhir-modelinfo-4.0.1.xml",
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

private val CXCA_S1_CQL_SCENARIO =
  CqlScenario(
    cqlLibraryPath = "files/cql/CXCAEligibilityLogic.cql",
    valueSetPaths =
      listOf(
        "files/vs/hiv-positive-status.json",
        "files/vs/absence-of-cervix.json",
        "files/vs/total-hysterectomy.json",
      ),
  )

private const val CXCA_S1_CQL_PD = "files/pd/CXCAS1DTCql.json"
private const val CXCA_S1_CQL_PD_CANONICAL =
  "http://hopena.health/cxca-kmp/PlanDefinition/CXCAS1DTCql"

/** WLHIV, 27 — the same determination as [WLHIV_27], evaluated as REAL CQL. */
val WLHIV_27_CQL =
  DemoConfiguration(
    id = "id_cxca_cql_wlhiv_27",
    description = "WLHIV, 27 — Eligible (CQL)",
    patientId = "cxca-cql-wlhiv-27",
    patientName = "Amina Achieng (27, WLHIV) — CQL",
    patientPath = "files/patient/PatientWlhivCql.json",
    planDefinitionPath = CXCA_S1_CQL_PD,
    planDefinitionCanonical = CXCA_S1_CQL_PD_CANONICAL,
    activityDefinitionPath = CXCA_S1_AD,
    additionalResourcePaths = listOf("files/patient/ObservationHivWlhivCql.json"),
    cql = CXCA_S1_CQL_SCENARIO,
  )

/** Post-hysterectomy, 45 — the cervix-exclusion arm, evaluated as REAL CQL. */
val HYSTERECTOMY_45_CQL =
  DemoConfiguration(
    id = "id_cxca_cql_hyst_45",
    description = "Post-hysterectomy, 45 — Not eligible (CQL)",
    patientId = "cxca-cql-hyst-45",
    patientName = "Dineo Dlamini (45, post-hysterectomy) — CQL",
    patientPath = "files/patient/PatientHystCql.json",
    planDefinitionPath = CXCA_S1_CQL_PD,
    planDefinitionCanonical = CXCA_S1_CQL_PD_CANONICAL,
    activityDefinitionPath = CXCA_S1_AD,
    additionalResourcePaths = listOf("files/patient/ConditionHystCql.json"),
    cql = CXCA_S1_CQL_SCENARIO,
  )

// ---- CXCA.S2.DT (due for screening) — the first multi-library CQL table ----

private val CXCA_S2_CQL_SCENARIO =
  CqlScenario(
    cqlLibraryPath = "files/cql/CXCADueForScreeningLogic.cql",
    includedLibraryPaths = listOf("files/cql/CXCAEligibilityLogic.cql"),
    valueSetPaths =
      listOf(
        "files/vs/hiv-positive-status.json",
        "files/vs/absence-of-cervix.json",
        "files/vs/total-hysterectomy.json",
        "files/vs/cervical-cancer-screening-test.json",
      ),
  )

private const val CXCA_S2_CQL_PD = "files/pd/CXCAS2DTCql.json"
private const val CXCA_S2_CQL_PD_CANONICAL =
  "http://hopena.health/cxca-kmp/PlanDefinition/CXCAS2DTCql"
private const val CXCA_S2_AD = "files/ad/CommunicateDueForScreening.json"

/** General, 45, last screened 2019: the 5-year interval elapsed long ago — Due. */
val DUE_45_CQL =
  DemoConfiguration(
    id = "id_cxca_cql_due_45",
    description = "General, 45, screened 2019 — Due (CQL, S2)",
    patientId = "cxca-cql-due-45",
    patientName = "Efua Mensah (45, screened 2019) — CQL S2",
    patientPath = "files/patient/PatientDueCql.json",
    planDefinitionPath = CXCA_S2_CQL_PD,
    planDefinitionCanonical = CXCA_S2_CQL_PD_CANONICAL,
    activityDefinitionPath = CXCA_S2_AD,
    additionalResourcePaths = listOf("files/patient/ObservationScreenDueCql.json"),
    cql = CXCA_S2_CQL_SCENARIO,
  )

/** General, 45, last screened 2025: within the 5-year interval — Not due (until 2030). */
val NOT_DUE_45_CQL =
  DemoConfiguration(
    id = "id_cxca_cql_notdue_45",
    description = "General, 45, screened 2025 — Not due (CQL, S2)",
    patientId = "cxca-cql-notdue-45",
    patientName = "Folasade Adeyemi (45, screened 2025) — CQL S2",
    patientPath = "files/patient/PatientNotDueCql.json",
    planDefinitionPath = CXCA_S2_CQL_PD,
    planDefinitionCanonical = CXCA_S2_CQL_PD_CANONICAL,
    activityDefinitionPath = CXCA_S2_AD,
    additionalResourcePaths = listOf("files/patient/ObservationScreenNotDueCql.json"),
    cql = CXCA_S2_CQL_SCENARIO,
  )

/**
 * WLHIV, 45, last screened 2023-05: the differentiated interval in one patient — Due on the
 * 3-year WLHIV interval where the general population would not be. (The contrast holds until
 * 2028-05, when 5 years elapse and the general population comes due too.)
 */
val WLHIV_DUE_45_CQL =
  DemoConfiguration(
    id = "id_cxca_cql_wlhiv_due_45",
    description = "WLHIV, 45, screened 2023 — Due on the 3y interval (CQL, S2)",
    patientId = "cxca-cql-wlhiv-due-45",
    patientName = "Grace Banda (45, WLHIV, screened 2023) — CQL S2",
    patientPath = "files/patient/PatientWlhivDueCql.json",
    planDefinitionPath = CXCA_S2_CQL_PD,
    planDefinitionCanonical = CXCA_S2_CQL_PD_CANONICAL,
    activityDefinitionPath = CXCA_S2_AD,
    additionalResourcePaths =
      listOf(
        "files/patient/ObservationHivWlhivDueCql.json",
        "files/patient/ObservationScreenWlhivDueCql.json",
      ),
    cql = CXCA_S2_CQL_SCENARIO,
  )

/** CQL scenarios appear only where the platform has the engine (see [cqlSupported]). */
val DEMO_CONFIGURATIONS: List<DemoConfiguration> =
  listOf(WLHIV_27, GENERAL_27, GENERAL_45, HYSTERECTOMY_45) +
    (if (cqlSupported) {
      listOf(WLHIV_27_CQL, HYSTERECTOMY_45_CQL, DUE_45_CQL, NOT_DUE_45_CQL, WLHIV_DUE_45_CQL)
    } else {
      emptyList()
    })
