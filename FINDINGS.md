# Findings: executing WHO-style DAK content on the KMP stack

What this repository's demos surfaced, layer by layer, while porting a WHO
SMART-style cervical-cancer DAK (three decision tables, verbatim CQL,
questionnaire capture) to the Open Health Stack / cqframework Kotlin
Multiplatform stack. Each finding records its status and the requirement it
implies for whoever builds the permanent version of that layer.

Method note: the instrument here is **verbatim execution** — running published
content byte-for-byte and treating every deviation a port forces as a finding
about either the stack or the content. Both kinds appear below.

Last updated: 2026-08-17. Maintained by [@dhes](https://github.com/dhes);
demos: this repo, [smart-cxca-android](https://github.com/dhes/smart-cxca-android),
[km-probe](https://github.com/dhes/km-probe),
[cql-v5-probe](https://github.com/dhes/cql-v5-probe).

---

## kotlin-fhir (model)

**F1 — Closed `ExpressionLanguage` enum rejects `text/cql-identifier`; every
published WHO PlanDefinition and Measure fails to deserialize.**
The R4 binding is extensible; the generated enum throws on off-list codes, so
one unrecognized `Expression.language` makes the whole document unreadable
(138 PDs + 41 Measures in `smart.who.int.immunizations`; 3 of 4 PDs in the
CXCA IG).
*Status:* filed as [kotlin-fhir#123](https://github.com/ohs-foundation/kotlin-fhir/issues/123);
release-slotted for rc03/v1; fix-shape comment on record (open code
representation vs enum stopgap — the v1 freeze makes the choice durable);
acceptance harness pre-staged (km-probe `accept-123.sh`).
*Requirement:* extensibly-bound coded elements must tolerate conformant
off-list codes; this now binds cqframework too (see F22).

**F2 — Aggregate `fhir-model`/`fhir-path` bundles drag R4B + R5 into R4-only
consumers.**
*Status:* propagating upstream (player-client PR applies the `-r4` split);
workflow and data-capture still on umbrellas.
*Requirement:* R4-only consumers should depend on `-r4` artifacts; libraries
with `Resource` in their public API should declare the model as `api`.

## kotlin-fhirpath (fhir-path)

**F3 — The engine derives reserved environment variables itself, silently
masking caller-supplied bindings: `%resource` is unreachable at item focus.**
Same variables map: `%resource.subject.reference` → empty;
`%custom.subject.reference` → the value. SDC *requires* (`SHALL`) `%resource`
= the QuestionnaireResponse root in extraction expressions.
*Status:* filed as [kotlin-fhir-data-capture#48](https://github.com/ohs-foundation/kotlin-fhir-data-capture/issues/48)
(manifests there; root here); pinned by a failing-when-fixed test
(`TemplateCaptureTest.reservedVariableMaskingIsWhyTemplatesLiveAtQuestionnaireLevel`).
*Requirement:* caller bindings must override reserved names, or the engine
must derive `%resource` from a supplied root; a contract change best made
before the fhir-path family's 1.0.

**F4 — Version diamond across the family.** data-capture pins umbrella
fhir-path beta02, workflow beta03; mixing in `fhir-path-r4` beta05 duplicates
`FhirPathEngine` classes on one classpath (first-loaded wins,
`NoSuchMethodError`s downstream).
*Status:* observed, worked around; the `-r4` split (F2) is the cure.
*Requirement:* one lineage per classpath; retire the umbrellas.

## kotlin-fhir-data-capture

**F5 — Item-scope templates cannot reach the QuestionnaireResponse** (the F3
manifestation): `subject` cannot be set from `QR.subject` in item-level
template extraction.
*Status:* filed (#48, above) with the working workaround: questionnaire-level
templates (QR = focus, plain relative paths) + `$this`-preserving `iif`
conditional contexts. Working example in `TemplateCaptureTest`.

**F6 — Positive: template extraction replaces StructureMap extraction
cleanly, and the date→dateTime coercion shim dies on KMP.** The android app's
`CxcaExtractor` exists solely because HAPI rejects date-precision
`effective[x]`; kotlin-fhir honors FHIR's date-precision dateTime, so the
answer flows straight through. Truth-table equivalence with the FML map
demonstrated in `TemplateCaptureTest` (3 rules, 4/4 tests).

## kotlin-fhir-workflow

**F7 — `text/cql-identifier` is not routed (processor throws), and cannot be
until F1 lands** — the router switches on the same closed enum, so the code
dies in deserialization before routing. One dependency chain, F1 the lynchpin.
*Status:* worked around by the "tunnel" (define names carried under
`text/cql`); retirement = F1 fix + a one-line mapping.

**F8 — The expression seam carries no library addressing.** With one library
the tunnel works; at two libraries define names collide ("Guidance" exists in
S1, S2, and S3). FHIR's own answer is `Expression.reference` (the Library
canonical).
*Status:* patched on the [`expression-reference` branch](https://github.com/dhes/kotlin-fhir-workflow/tree/expression-reference)
(`ProtocolExpression.Elm` carries `reference`; PR-ready); proven end-to-end —
including one evaluator holding all three tables with S3 reachable *only* by
reference.
*Requirement:* the seam must carry `Expression.reference`; both workflow
futures (seam-based and CR-backed) need the same routing semantics.

**F9 — `$apply` silently skips nested-PlanDefinition actions.** The CPG/DAK
pathway pattern (parent PD composing child tables via
`action.definitionCanonical`) resolves the canonical as an ActivityDefinition
only → null → skipped; the parent "applies" successfully with an empty
CarePlan. `CanonicalResolver.resolvePlanDefinition` already exists, unused.
*Status:* pinned by `NestedPlanDefinitionApplyTest` (fails loudly when fixed);
workaround: sequential applies.
*Requirement:* nesting support (a contained recursion) or a loud error.

**F10 — `EvaluationContext.today` is carried but nothing makes evaluators
honor it.** Our own CQL evaluator ignored it (falling back to the machine
clock) until wired to `evaluationDateTime`; date logic was untestable and
tests latently time-dependent.
*Requirement:* the seam contract should state that `today` is the evaluation
clock (and ideally verify it).

**F11 — Seam contract gaps (accumulated):** no error contract (throw vs
`EvaluationResult.Failure`), no context/concurrency requirements documented;
`Elm.elmJson` field name vs `text/cql` routing mismatch;
`CarePlan.instantiatesCanonical` not populated (traceability); R4
CommunicationRequest's missing `intent` needs the request-intent extension
stamp. *Status:* feedback list, held for the maintainer's review pass.

## kotlin-fhir-knowledge (Knowledge Manager)

Try-out of [PR #2](https://github.com/ohs-foundation/kotlin-fhir-knowledge/pull/2)
against real packages (712-resource WHO immunizations CI build), findings
posted on the PR:

**F12 — Re-import with a persistent index crashes** (`FOREIGN KEY constraint
failed`): the IG insert is `OnConflict.IGNORE`, returns -1, and `import()`
passes -1 as the FK. KDoc says already-present packages are skipped; only
`install()` guards. An app calling `import()` at startup works once and
crashes every launch thereafter.

**F13 — Parse failures are silent at the API level.** 179 dropped resources
(from F1) are log-only; `import()` reports nothing; no non-deprecated census
API. *Requirement:* import should return counts (indexed/skipped/failed) —
with clinical content, silently missing decision tables is the worst failure
mode.

**F14 — `import()` doesn't skip subdirectories** (standard IG package layout:
`example/`, `other/`, `xml/`) — stack traces per directory. **F15 —** non-JSON
files (`.index.db`, `contents.txt`) are skipped correctly but each logs a full
JSON-decode stack trace.

**F16 — Desktop storage is not app-scoped as documented** — fixed
`~/.fhir-knowledge` for all apps, contradicting the class KDoc and compounding
F12 across applications.

**F17 — Consumer friction:** unconditional `signAllPublications()` blocks
`publishToMavenLocal` for anyone trying the branch; `fhir-model` is
`implementation`-scoped with `Resource` in the public API (see F2).

## cqframework engine (KMP line)

**F18 — The `Simple*` FHIR providers had no tests.** *Status:* first suite
contributed as [cql#1823](https://github.com/cqframework/clinical_quality_language/pull/1823)
(value-set filtering, subject-context filtering, choice-cast under
aggregates, anniversary boundaries, `evaluationDateTime`, null propagation) —
also the regression floor under the announced data-access rework.

**F19 — Precompiled-ELM loading is blocked by three independent defects.**
Version gate comparing `translatorVersion` to `compatibilityLevel`
([cql#1827](https://github.com/cqframework/clinical_quality_language/issues/1827));
default options round-tripping as null and failing the options match
([cql#1828](https://github.com/cqframework/clinical_quality_language/issues/1828));
fatally, `generateCompiledLibrary` requiring resolved `resultType`s that
deserialization never rehydrates
([cql#1829](https://github.com/cqframework/clinical_quality_language/issues/1829)).
*Consequence:* every KMP consumer ships CQL source and compiles at init; the
`Library.content` model CR depends on cannot work until these land.

**F20 — `SystemModelInfoProvider` only auto-registers on the JVM**
(ServiceLoader); non-JVM targets must register it explicitly. *Status:*
worked around in `cxca-cql`; documentation candidate.

**F21 — antlr-kotlin diamond:** cql-to-elm needs 1.0.3; fhir-path pulls
binary-incompatible 1.0.10; forced resolution to 1.0.3 with a coexistence
test. *Requirement:* upstream version alignment.

**F22 — Positives:** FHIRHelpers 4.0.1's implicit FHIR→System conversions
compile and execute on the KMP engine (desktop + wasm; first known);
date-precision dateTimes flow through retrieve and `Max()`/`years between`
correctly, including exact-anniversary boundaries. Note the F1 coupling: as
the engine adopts kotlin-fhir as its KMP data layer, kotlin-fhir's codegen
contracts (enum policy included) become engine-facing contracts.

**F23 — Pattern warning for any library-resolution layer:** a naive
`library <name>` regex matches prose ("This library defines functions…" in
FHIRHelpers' header) — declaration parsing must be comment-aware. Found by,
and fixed in, our own evaluator.

## Content (the DAK itself)

**F24 — Verbatim execution exposed a ValueSet defect:** `absence-of-cervix`'s
description promised the total-hysterectomy code; its compose lacked it. The
demo's earlier two-ValueSet port had silently compensated.
*Status:* fixed upstream in the IG (`b3aefb9`).
*Principle:* deviations — even correct ones — hide source defects; port
verbatim, then fix the source.

---

## Cross-cutting themes

1. **Silent failure is the stack's besetting sin.** F9, F12, F13, F14/15, and
   the engine's swallowed FHIRPath errors (behind F3's discovery) are one
   pattern: layers that skip, drop, or empty-out instead of failing loudly.
   For clinical decision support, a silent wrong answer is worse than a crash.
2. **Freeze windows decide fix shapes.** F1's enum policy, F3's variable
   override semantics, and the seam contracts (F8, F10, F11) are all cheap to
   fix before their libraries' 1.0s and breaking changes after. Most of the
   filings above exist to land inside those windows.
3. **The wire format bridged the stacks; native bindings are replacing it.**
   The JSON + ModelInfo bridge (this repo's `cxca-cql`) proved the
   cqframework↔kotlin-fhir join; the engine's announced kotlin-fhir data
   layer is that join being made permanent. The seam architecture survives;
   the bridge interior is interim by design.
4. **One evaluator per DAK, addressed by `Expression.reference`,** is the
   working end-state this repo demonstrates: multi-library include graphs
   (which the CQL 3.0 ballot's common-library patterns will make universal),
   define-name collisions resolved by canonical, FHIRHelpers included.
