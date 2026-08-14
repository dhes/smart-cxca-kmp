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

- `dhes/smart-cxca` (a skeletal WHO-style cervical-cancer SMART IG, now public) — the
  source of truth: the three decision tables authored in CQL
  (`CXCAEligibilityLogic`, `CXCADueForScreeningLogic`, `CXCAScreeningResultLogic`).
- All three tables run here as **near-verbatim CQL** through `cxca-cql`, the
  `ExpressionEvaluator` implementation over the cqframework v5 KMP engine. S2
  `include`s S1 (multi-library resolution), and every CQL dynamicValue carries
  `Expression.reference` so the evaluator addresses the right library.
- The CXCA.S1.DT logic ALSO exists re-authored as **FHIRPath**
  (`cxca-demo/src/commonMain/composeResources/files/pd/CXCAS1DT.json`) —
  originally a workaround from when no KMP CQL evaluator existed, kept as the
  all-platform path (iOS has no CQL engine yet) and as a side-by-side
  comparison. Truth-table tests in `ActivityFlowDemoModelTest` hold both
  evaluations to the same semantics.

## The legacy stack (for comparison)

- google android-fhir (legacy JVM-only Android SDK) — runs the same DAK
  content with full CQL, via `dhes/smart-cxca-android` (legacy demo app).
- `cqframework/clinical_quality_language` (CQL spec + translator + evaluator) —
  no longer legacy-only: the translator and engine went Kotlin Multiplatform in
  v5, and this repo consumes them directly (jvm/wasmJs targets, via the PR
  #1815 `kmp-fhir-providers` branch). What was once "a planned Phase 2" is
  done: `cxca-cql` plugs the v5 engine into the workflow seam, and verbatim
  WHO-idiom CQL runs on Android, desktop, and wasm — FHIRPath remains the
  all-platform path.
- `cqframework/clinical-reasoning` (CQL/measure/`$apply` operations) — still
  the JVM/HAPI operations layer behind the legacy stack, but in motion: its
  `cqf-fhir-cql` module is being converted to Kotlin upstream (PR #1080),
  wired to the KMP engine's `engine-fhir` module. The two stacks this repo
  bridges by hand are beginning to converge upstream.
