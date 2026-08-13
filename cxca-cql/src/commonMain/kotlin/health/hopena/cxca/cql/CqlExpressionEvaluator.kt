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

import dev.ohs.fhir.model.r4.Bundle
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.workflow.expression.EvaluationContext
import dev.ohs.fhir.workflow.expression.EvaluationResult
import dev.ohs.fhir.workflow.expression.ExpressionEvaluator
import dev.ohs.fhir.workflow.expression.ProtocolExpression
import kotlinx.datetime.number
import kotlinx.io.Buffer
import kotlinx.io.writeString
import kotlinx.serialization.json.Json
import org.cqframework.cql.cql2elm.CqlCompilerOptions
import org.cqframework.cql.cql2elm.LibraryContentType
import org.cqframework.cql.cql2elm.LibraryManager
import org.cqframework.cql.cql2elm.LibrarySourceProvider
import org.cqframework.cql.cql2elm.ModelManager
import org.hl7.cql.model.ModelIdentifier
import org.hl7.cql.model.ModelInfoProvider
import org.hl7.elm.r1.VersionedIdentifier
import org.hl7.elm_modelinfo.r1.ModelInfo
import org.hl7.elm_modelinfo.r1.serializing.parseModelInfoXml
import org.opencds.cqf.cql.engine.data.CompositeDataProvider
import org.opencds.cqf.cql.engine.data.DataProvider
import org.opencds.cqf.cql.engine.execution.CqlEngine
import org.opencds.cqf.cql.engine.execution.Environment
import org.opencds.cqf.cql.engine.fhir.fhirModelNamespaceUri
import org.opencds.cqf.cql.engine.fhir.model.SimpleFhirModelResolver
import org.opencds.cqf.cql.engine.fhir.parser.fhirResourceJsonToCqlValue
import org.opencds.cqf.cql.engine.fhir.retrieve.SimpleFhirRetrieveProvider
import org.opencds.cqf.cql.engine.fhir.terminology.SimpleFhirTerminologyProvider
import org.opencds.cqf.cql.engine.runtime.Value
import org.opencds.cqf.cql.engine.util.localDateOf
import org.opencds.cqf.cql.engine.util.zoneIdOf

/**
 * A CQL implementation of the workflow library's [ExpressionEvaluator] seam, backed by the
 * cqframework v5 KMP engine and the PR #1815 FHIR providers.
 *
 * Contract with the PlanDefinition author: expressions use language `text/cql` and their text is
 * the NAME OF A DEFINE in [cqlLibrarySource] (kotlin-fhir-workflow's processor routes `text/cql`
 * text to the ELM seam verbatim; `text/cql-identifier` is not yet mapped upstream). The library
 * is compiled once and cached; per evaluation, the subject and every Bundle-typed prefetch
 * variable in the [EvaluationContext] are merged into one data bundle the engine retrieves from.
 *
 * Libraries the entry library `include`s are passed as [includedLibrarySources] and resolved by
 * name at compile time. Their defines are not directly evaluable through the seam — only entry
 * -library define names are; the entry library reaches included logic via its alias.
 */
class CqlExpressionEvaluator(
  private val cqlLibrarySource: String,
  valueSetJsons: List<String>,
  modelInfoXml: String,
  includedLibrarySources: List<String> = emptyList(),
) : ExpressionEvaluator {

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
  }

  private val libraryIdentifier: VersionedIdentifier =
    parseLibraryDeclaration(cqlLibrarySource)

  /**
   * Every source the compiler may ask for, keyed by library name: the entry library plus the
   * libraries it (transitively) `include`s. Define names in [evaluate] still resolve against the
   * entry library only; an included library's defines are reachable through its local alias
   * (`Eligibility."Eligible"`), exactly as in CQL source. A version declared in an `include` is
   * checked by the compiler against the source's own declaration, not by this lookup.
   */
  private val librarySourcesByName: Map<String, String> = buildMap {
    (listOf(cqlLibrarySource) + includedLibrarySources).forEach { source ->
      val name = parseLibraryDeclaration(source).id!!
      check(put(name, source) == null) { "Two library sources declare the name $name" }
    }
  }

  private fun parseLibraryDeclaration(source: String): VersionedIdentifier {
    val match =
      Regex("""library\s+(\w+)(?:\s+version\s+'([^']+)')?""").find(source)
        ?: error("CQL source has no library declaration")
    return VersionedIdentifier().withId(match.groupValues[1]).withVersion(
      match.groupValues[2].ifEmpty { null }
    )
  }

  private val modelManager =
    ModelManager().apply {
      // On the JVM the System model provider auto-registers via ServiceLoader; wasm/js have no
      // ServiceLoader, so register it explicitly (harmless where it is already present).
      modelInfoLoader.registerModelInfoProvider(org.hl7.cql.model.SystemModelInfoProvider())
      modelInfoLoader.registerModelInfoProvider(
        object : ModelInfoProvider {
          override fun load(modelIdentifier: ModelIdentifier): ModelInfo? =
            if (modelIdentifier.id == "FHIR") {
              parseModelInfoXml(Buffer().apply { writeString(modelInfoXml) })
            } else {
              null
            }
        }
      )
    }

  private val libraryManager =
    LibraryManager(
        modelManager,
        CqlCompilerOptions().apply {
          setOptions(CqlCompilerOptions.Options.EnableResultTypes)
        },
      )
      .apply {
        librarySourceLoader.registerProvider(
          object : LibrarySourceProvider {
            override fun getLibrarySource(libraryIdentifier: VersionedIdentifier) =
              librarySourcesByName[libraryIdentifier.id]?.let { source ->
                Buffer().apply { writeString(source) }
              }

            override fun getLibraryContent(
              libraryIdentifier: VersionedIdentifier,
              type: LibraryContentType,
            ) =
              if (type == LibraryContentType.CQL) getLibrarySource(libraryIdentifier) else null
          }
        )
      }

  private val fhirModel = modelManager.resolveModel("FHIR", "4.0.1")

  private val terminologyProvider =
    SimpleFhirTerminologyProvider(
      parseBundleJson(
        """{"resourceType":"Bundle","type":"collection","entry":[""" +
          valueSetJsons.joinToString(",") { """{"resource":$it}""" } +
          """]}"""
      )
    )

  override suspend fun evaluate(
    expression: ProtocolExpression,
    context: EvaluationContext,
  ): EvaluationResult {
    if (expression !is ProtocolExpression.Elm) {
      return EvaluationResult.Failure("CqlExpressionEvaluator cannot evaluate $expression")
    }
    val defineName = expression.elmJson.trim()

    // Expression.reference (the Library canonical) picks the entry library. Convention, as in
    // the DAK content: the canonical's tail segment IS the CQL library name, an optional
    // `|version` suffix its version. Absent a reference, the constructor's entry library holds.
    val entryLibrary =
      when (val reference = expression.reference) {
        null -> libraryIdentifier
        else -> {
          val name = reference.substringAfterLast('/').substringBefore('|')
          if (name !in librarySourcesByName) {
            return EvaluationResult.Failure(
              "Expression.reference $reference names no library this evaluator holds " +
                "(known: ${librarySourcesByName.keys})"
            )
          }
          VersionedIdentifier()
            .withId(name)
            .withVersion(reference.substringAfterLast('|', "").ifEmpty { null })
        }
      }

    return try {
      val subjectId =
        context.subject.id
          ?: return EvaluationResult.Failure("CQL evaluation requires a subject with an id")

      val dataProvider: DataProvider =
        CompositeDataProvider(
          SimpleFhirModelResolver(fhirModel),
          SimpleFhirRetrieveProvider(dataBundle(context), terminologyProvider),
        )
      val engine =
        CqlEngine(
          Environment(
            libraryManager,
            mutableMapOf<String?, DataProvider?>(fhirModelNamespaceUri to dataProvider),
            terminologyProvider,
          )
        )

      val results =
        engine
          .evaluate {
            library(entryLibrary) { expressions(defineName) }
            contextParameter = "Patient" to subjectId
            // The seam's `today` is the evaluation clock: without this, Today()/AgeInYears()
            // read the machine clock and date logic is untestable.
            evaluationDateTime =
              localDateOf(context.today.year, context.today.month.number, context.today.day)
                .atStartOfDay(zoneIdOf("UTC"))
          }
          .onlyResultOrThrow

      toEvaluationResult(results[defineName]?.value)
    } catch (t: Throwable) {
      EvaluationResult.Failure(t.message ?: "CQL evaluation failed for define: $defineName")
    }
  }

  /** The subject plus every Bundle-typed prefetch variable, merged into one collection bundle. */
  private fun dataBundle(context: EvaluationContext): org.opencds.cqf.cql.engine.runtime.ClassInstance {
    val resources = buildList {
      add(context.subject)
      context.variables.values.filterIsInstance<Bundle>().forEach { bundle ->
        bundle.entry.forEach { entry -> entry.resource?.let { add(it) } }
      }
    }
    val bundleJson =
      """{"resourceType":"Bundle","type":"collection","entry":[""" +
        resources.joinToString(",") {
          """{"resource":${json.encodeToString(Resource.serializer(), it)}}"""
        } +
        """]}"""
    return parseBundleJson(bundleJson)
  }

  private fun parseBundleJson(bundleJson: String) =
    fhirResourceJsonToCqlValue(Buffer().apply { writeString(bundleJson) }, fhirModel)

  /** Maps engine values onto the seam's [EvaluationResult]; the write-back accepts Kotlin primitives. */
  private fun toEvaluationResult(value: Value?): EvaluationResult =
    when (val unwrapped = unwrap(value)) {
      null -> EvaluationResult.Values(emptyList())
      is Boolean -> EvaluationResult.Bool(unwrapped)
      is List<*> -> EvaluationResult.Values(unwrapped.filterNotNull().map { unwrap0(it) })
      else -> EvaluationResult.Values(listOf(unwrapped))
    }

  private fun unwrap(value: Value?): Any? =
    when (value) {
      null -> null
      is org.opencds.cqf.cql.engine.runtime.String -> value.value
      is org.opencds.cqf.cql.engine.runtime.Boolean -> value.value
      is org.opencds.cqf.cql.engine.runtime.Integer -> value.value
      is org.opencds.cqf.cql.engine.runtime.List -> value.map { unwrap(it) }
      else -> value.toString()
    }

  private fun unwrap0(item: Any): Any = item
}
