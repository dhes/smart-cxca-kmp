# smart-cxca-kmp

A Kotlin Multiplatform demo that runs the WHO SMART-style cervical-cancer
screening decision tables — CXCA.S1 (*screening eligibility*), S2 (*due for
screening*), and S3 (*act on the screening result*), the full
screen-triage-treat arc — on the Open Health Stack KMP stack, on four
platforms, two ways:

- **FHIRPath** (all platforms; S1): the eligibility logic re-authored as
  `text/fhirpath` expressions in a PlanDefinition, evaluated by
  `ohs-foundation/kotlin-fhirpath` (FHIRPath evaluation).
- **CQL** (Android, desktop, wasm): the CQL libraries from
  [`dhes/smart-cxca`](https://github.com/dhes/smart-cxca) (a skeletal
  WHO-style cervical-cancer SMART IG), **verbatim — byte-for-byte DAK
  source, FHIRHelpers 4.0.1 included** — compiled and evaluated on device
  by the KMP CQL engine from
  `cqframework/clinical_quality_language` (CQL spec + translator + evaluator),
  plugged into the `ExpressionEvaluator` seam of
  `ohs-foundation/kotlin-fhir-workflow` (PlanDefinition `$apply` + CPG ActivityFlow).

As far as we know this is the first working glue between the cqframework KMP
engine and the OHS workflow layer — the demo exists to prove the two meet, and
to generate concrete feedback on both while their APIs are still pre-alpha.

## What it does

`PlanDefinition/$apply` runs the decision tables over bundled test patients
and writes each outcome — a status plus WHO guidance text — into a
CommunicationRequest. Twelve scenarios ship: four via FHIRPath (S1), eight via
CQL across the three tables — eligibility (age bands, HIV status, prior
hysterectomy), due-for-screening (the differentiated 3-year WLHIV vs 5-year
general interval), and result action (positive/negative/invalid HPV-DNA) —
with truth tables enforced by tests. S2 `include`s the S1 library; every CQL
dynamicValue carries `Expression.reference` so one evaluator can hold several
libraries and address the right one per expression.

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
| iOS | ✅ | ➖ | FHIRPath scenarios run in the bundled `iosApp` Xcode host; CQL waits on cqframework Kotlin/Native targets (CQL scenarios are hidden on iOS) |

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

iOS: open `iosApp/iosApp.xcodeproj` in Xcode and Run, or from the CLI:

```
cd iosApp && xcodebuild -project iosApp.xcodeproj -scheme iosApp \
  -destination 'generic/platform=iOS Simulator' build
```

The Xcode build embeds the `CxcaDemoKit` framework via a Gradle run-script
phase. The simulator build is arm64-only (Compose Multiplatform publishes no
iosX64 artifacts). CQL scenarios are hidden on iOS until the cqframework
engine gains Kotlin/Native targets — the FHIRPath scenarios run in full.

## Status and roadmap

- ✅ **Phase 1** — S1 in FHIRPath, all platforms, truth-table verified
- ✅ **Phase 2** — S1 in WHO-idiom CQL through the workflow seam
  (Android/desktop/wasm); since upgraded to verbatim DAK source with real
  FHIRHelpers (no `.value` rewrites, no deviations)
- ✅ **CXCA.S2.DT** (screening due) — multi-library `include` resolved by the
  evaluator; entry library addressed per expression via `Expression.reference`
  carried through a patched workflow seam (branch `expression-reference`,
  upstream PR candidate)
- ✅ **CXCA.S3.DT** (act on screening result) — standalone result
  interpretation; all three DAK tables now run as CQL
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
