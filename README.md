# smart-cxca-kmp

A Kotlin Multiplatform demo that runs a WHO SMART Guidelines cervical-cancer
screening decision table — CXCA.S1.DT, *determine screening eligibility* — on
the Open Health Stack KMP stack, on four platforms, two ways:

- **FHIRPath** (all platforms): the decision logic re-authored as `text/fhirpath`
  expressions in a PlanDefinition, evaluated by `ohs-foundation/kotlin-fhirpath`
  (FHIRPath evaluation).
- **CQL** (Android, desktop, wasm): the WHO DAK's own CQL library, near-verbatim,
  compiled and evaluated on device by the KMP CQL engine from
  `cqframework/clinical_quality_language` (CQL spec + translator + evaluator),
  plugged into the `ExpressionEvaluator` seam of
  `ohs-foundation/kotlin-fhir-workflow` (PlanDefinition `$apply` + CPG ActivityFlow).

As far as we know this is the first working glue between the cqframework KMP
engine and the OHS workflow layer — the demo exists to prove the two meet, and
to generate concrete feedback on both while their APIs are still pre-alpha.

## What it does

`PlanDefinition/$apply` runs the S1 eligibility gate over bundled test patients
(age bands, HIV status, prior hysterectomy) and writes the outcome — an
eligibility status plus WHO guidance text — into a CommunicationRequest. Ten
scenarios ship: five evaluated via FHIRPath, the same five via CQL, with
matching truth tables enforced by tests.

## The tunnel (a deliberate, temporary hack)

kotlin-fhir-workflow does not yet map `text/cql-identifier`, the standard DAK
idiom for "this expression names a define in the logic library." Until it
does, our Phase-2 PlanDefinition carries define names under the `text/cql`
label, and `cxca-cql` treats the expression text as a define name — the
semantics of `$cql`'s `expression` parameter wearing another label's clothes.
One upstream mapping retires the hack. Details in [ECOSYSTEM.md](ECOSYSTEM.md).

## Platform matrix

| Platform | FHIRPath | CQL | Why the gap |
|---|---|---|---|
| Android | ✅ | ✅ | |
| Desktop (JVM) | ✅ | ✅ | |
| Web (wasmJs) | ✅ | ✅ | |
| iOS | ⚠️ | ➖ | shared modules compile for iOS targets, but no Xcode host app is bundled here yet; CQL additionally waits on cqframework Kotlin/Native targets |

## Modules

- **`cxca-demo`** — Compose Multiplatform app: scenario menu, `$apply` driver,
  bundled IG content (PlanDefinitions, ActivityDefinition, ValueSets, CQL
  source, test patients). CQL scenarios appear only on platforms where
  `cxca-cql` is available (expect/actual injection).
- **`cxca-cql`** — the bridge: implements kotlin-fhir-workflow's
  `ExpressionEvaluator` over the cqframework v5 engine. Ships CQL source and
  compiles at evaluator construction (no precompiled ELM); FHIR data crosses
  the boundary as JSON plus ModelInfo — no object-model coupling between
  `ohs-foundation/kotlin-fhir` (model library) and the engine.

## Building and running

Two dependencies are not yet on Maven Central and must be published to
mavenLocal first:

```
# 1. kotlin-fhir-workflow (branch: workflow-kmp-migration)
cd ../kotlin-fhir-workflow && ./gradlew :workflow:publishToMavenLocal

# 2. cqframework KMP engine (branch: kmp-fhir-providers, PR #1815)
cd ../clinical_quality_language && ./gradlew publishToMavenLocal
```

The cqframework pin in `cxca-cql/build.gradle.kts` names the exact snapshot
version; if you publish from a newer commit of that branch, update the pin to
match.

Then:

```
./gradlew :cxca-demo:run                          # desktop
./gradlew :cxca-demo:installDebug                 # android (device/emulator)
./gradlew :cxca-demo:wasmJsBrowserDevelopmentRun  # web
./gradlew :cxca-cql:desktopTest :cxca-cql:wasmJsNodeTest   # truth tables, both engines
```

There is no bundled iOS host app yet — the iOS source sets compile, and a host
scaffold is planned; CQL scenarios will remain hidden on iOS either way until
the engine gains native targets.

## Status and roadmap

- ✅ **Phase 1** — S1 in FHIRPath, all platforms, truth-table verified
- ✅ **Phase 2** — S1 in near-verbatim WHO CQL through the workflow seam
  (Android/desktop/wasm)
- ⏭️ **CXCA.S3.DT** (act on screening result) — standalone library, next up
- ⏭️ **CXCA.S2.DT** (screening due) — exercises multi-library `include`, the
  next seam question for upstream
- ⏭️ Knowledge-manager integration once `ohs-foundation/kotlin-fhir-knowledge`
  (KMP Knowledge Manager) ships — canonical resolution replaces bundled-asset
  lookup
- ⏭️ iOS CQL when the cqframework engine grows native targets

## Upstream threads this work feeds

- `ohs-foundation/kotlin-fhir` (model library)
  [#123](https://github.com/ohs-foundation/kotlin-fhir/issues/123) — closed
  `ExpressionLanguage` enum rejects `text/cql-identifier`, making published
  WHO decision tables undeserializable (found probing the KM draft with
  [dhes/km-probe](https://github.com/dhes/km-probe))
- `ohs-foundation/kotlin-fhir-knowledge`
  [PR #2](https://github.com/ohs-foundation/kotlin-fhir-knowledge/pull/2) —
  try-out findings on the KMP Knowledge Manager draft
- `cqframework/clinical_quality_language`
  [#1815](https://github.com/cqframework/clinical_quality_language/pull/1815)
  — the KMP FHIR data providers this demo depends on; when it merges, the
  snapshot pin retires
- kotlin-fhir-workflow seam feedback (error contracts, `text/cql-identifier`
  mapping, `Expression.reference` in the seam) — tracked for the pre-alpha
  API review

## Where this sits in the landscape

The full map of repos, layers, and the legacy-stack comparison — including
`dhes/smart-cxca-android` (legacy demo app), which runs the same DAK content
with full CQL on the JVM-only Android SDK — lives in
[ECOSYSTEM.md](ECOSYSTEM.md).
