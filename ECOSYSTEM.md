# Ecosystem

Where `dhes/smart-cxca-kmp` sits, and what it pulls from. Repos are cited as
`owner/repo` (role).

## The KMP stack this app runs on

Four Open Health Stack Foundation repos, published under the Maven group
`dev.ohs.fhir`, layered bottom-up:

| Layer | Repo | Artifact | Version here |
|---|---|---|---|
| Resource model | `ohs-foundation/kotlin-fhir` (model library) | `fhir-model` | 1.0.0-beta05 |
| Expression evaluation | `ohs-foundation/kotlin-fhirpath` (FHIRPath evaluation) | `fhir-path` | 1.0.0-beta03 |
| Data | `ohs-foundation/kotlin-fhir-engine` (persist/sync) | `fhir-engine` | 2.0.0-alpha01 |
| Orchestration | `ohs-foundation/kotlin-fhir-workflow` (PlanDefinition `$apply` + CPG ActivityFlow) | `workflow` | 2.0.0-alpha01 * |

One sentence each: **kotlin-fhir** gives Kotlin classes for FHIR R4 resources;
**kotlin-fhirpath** evaluates expressions over them; **kotlin-fhir-engine**
stores them (SQLite on Android/desktop/iOS; a wasm target landed in
2.0.0-alpha02); **kotlin-fhir-workflow** applies PlanDefinitions and walks
CPG request/event lifecycles.

\* `workflow` is not yet on Maven Central: it is built from the
[`expression-reference` branch of `dhes/kotlin-fhir-workflow`](https://github.com/dhes/kotlin-fhir-workflow/tree/expression-reference)
and published to mavenLocal (`./gradlew :workflow:publishToMavenLocal` in that
repo). That branch is upstream's `workflow-kmp-migration` plus one seam patch —
`ProtocolExpression.Elm` carries `Expression.reference` so a multi-library
evaluator can address the entry library (needed by the S2 decision table;
upstream PR pending).

## The content this app executes

- `dhes/smart-cxca` (a skeletal WHO-style cervical-cancer SMART IG) — the source of truth:
  decision tables authored in CQL (e.g. `CXCAEligibilityLogic`).
- This repo re-authors the CXCA.S1.DT eligibility logic as **FHIRPath**
  (`cxca-demo/src/commonMain/composeResources/files/pd/CXCAS1DT.json`),
  because the KMP workflow library evaluates `text/fhirpath` only — there is
  no KMP CQL/ELM evaluator yet. The re-authoring is verified against the CQL
  semantics by the truth-table tests in `ActivityFlowDemoModelTest`.

## The legacy stack (for comparison)

- google android-fhir (legacy JVM-only Android SDK) — runs the same DAK
  content with full CQL, via `dhes/smart-cxca-android` (legacy demo app).
- `cqframework/clinical_quality_language` (CQL spec + cql-to-elm translator)
  and `cqframework/clinical-reasoning` (CQL/measure/`$apply` operations) —
  the JVM libraries that give the legacy stack its CQL execution. A planned
  Phase 2 plugs them into the workflow library's `ExpressionEvaluator` seam
  so verbatim WHO CQL runs on JVM targets (Android/desktop), while FHIRPath
  remains the all-platform path.
