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

import dev.ohs.fhir.workflow.expression.ExpressionEvaluator

/**
 * Whether this platform can evaluate CQL: the cqframework KMP engine publishes jvm/js/wasmJs
 * targets, so Android, desktop, and wasm say true; iOS says false until upstream adds
 * Kotlin/Native targets. CQL scenarios are hidden from the menu where this is false.
 */
expect val cqlSupported: Boolean

/**
 * Creates the CQL [ExpressionEvaluator] (cxca-cql's CqlExpressionEvaluator) on platforms that
 * have it, or null on platforms that do not. Inputs are the app-bundled assets: the entry CQL
 * library source, the sources of libraries it `include`s, the ValueSet JSONs their valuesets
 * resolve against, and the FHIR modelinfo XML.
 */
expect fun createCqlEvaluator(
  cqlLibrarySource: String,
  valueSetJsons: List<String>,
  modelInfoXml: String,
  includedLibrarySources: List<String> = emptyList(),
): ExpressionEvaluator?
