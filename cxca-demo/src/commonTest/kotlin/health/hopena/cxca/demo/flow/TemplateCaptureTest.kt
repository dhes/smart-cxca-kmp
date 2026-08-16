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

import dev.ohs.fhir.datacapture.extraction.template.TemplateExtractionEngine
import dev.ohs.fhir.fhirpath.FhirPathEngine
import dev.ohs.fhir.model.r4.Questionnaire
import dev.ohs.fhir.model.r4.QuestionnaireResponse
import dev.ohs.fhir.model.r4.Resource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The capture-leg walking skeleton: SDC TEMPLATE-based extraction of the CXCA screening form,
 * re-authoring the android app's StructureMap (CXCAScreeningDataQRToBundle FML) in the idiom the
 * KMP data-capture library implements. Same three rules, same truth table:
 * - hivStatus answered        -> Observation carrying the answer's SNOMED coding (final)
 * - hasCervix = false         -> Condition SNOMED 37687000 (absence of cervix)
 * - lastScreenDate answered   -> Observation SNOMED 171149006 with effective = the date answer
 *
 * Two findings shaped this file:
 * 1. The date answer flows STRAIGHT into effectiveDateTime — FHIR dateTime permits date
 *    precision and kotlin-fhir honors it — so the android app's pre-extraction date->dateTime
 *    coercion (CxcaExtractor) has no KMP counterpart.
 * 2. The FHIRPath engine derives the RESERVED environment variables itself, so the
 *    data-capture layer's `%resource` (= the QuestionnaireResponse, per SDC) binding is
 *    silently masked; at item scope it evaluates to empty (see
 *    [reservedVariableMaskingIsWhyTemplatesLiveAtQuestionnaireLevel]). The templates therefore
 *    live at QUESTIONNAIRE level, where the QR is the focus and plain relative paths reach
 *    subject and items; conditional emission uses `$this`-preserving iif contexts.
 */
class TemplateCaptureTest {

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
  }

  private val templateExtract =
    "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtract"
  private val templateExtractContext =
    "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtractContext"
  private val templateExtractValue =
    "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtractValue"

  private val questionnaire =
    """
    {
      "resourceType": "Questionnaire",
      "id": "QCXCAScreeningDataTemplated",
      "url": "http://smart.who.int/cxca/Questionnaire/QCXCAScreeningDataTemplated",
      "status": "active",
      "extension": [
        { "url": "$templateExtract",
          "extension": [{ "url": "template", "valueReference": { "reference": "#hiv-obs-template" } }] },
        { "url": "$templateExtract",
          "extension": [{ "url": "template", "valueReference": { "reference": "#cervix-absence-template" } }] },
        { "url": "$templateExtract",
          "extension": [{ "url": "template", "valueReference": { "reference": "#screen-obs-template" } }] }
      ],
      "item": [
        { "linkId": "hasCervix", "text": "Cervix present?", "type": "boolean", "required": true },
        { "linkId": "hivStatus", "text": "HIV status", "type": "choice" },
        { "linkId": "lastScreenDate", "text": "Date of last cervical screening", "type": "date" }
      ],
      "contained": [
        {
          "resourceType": "Observation",
          "id": "hiv-obs-template",
          "extension": [
            { "url": "$templateExtractContext",
              "valueString": "iif(item.where(linkId = 'hivStatus').answer.exists(), ${'$'}this, {})" }
          ],
          "status": "final",
          "code": {
            "coding": [
              {
                "extension": [
                  { "url": "$templateExtractValue",
                    "valueString": "item.where(linkId = 'hivStatus').answer.value.first()" }
                ]
              }
            ]
          },
          "subject": {
            "reference": "",
            "_reference": {
              "extension": [{ "url": "$templateExtractValue", "valueString": "subject.reference" }]
            }
          }
        },
        {
          "resourceType": "Condition",
          "id": "cervix-absence-template",
          "extension": [
            { "url": "$templateExtractContext",
              "valueString": "iif(item.where(linkId = 'hasCervix').answer.value.first() = false, ${'$'}this, {})" }
          ],
          "code": {
            "coding": [
              { "system": "http://snomed.info/sct", "code": "37687000", "display": "Congenital absence of cervix" }
            ]
          },
          "subject": {
            "reference": "",
            "_reference": {
              "extension": [{ "url": "$templateExtractValue", "valueString": "subject.reference" }]
            }
          }
        },
        {
          "resourceType": "Observation",
          "id": "screen-obs-template",
          "extension": [
            { "url": "$templateExtractContext",
              "valueString": "iif(item.where(linkId = 'lastScreenDate').answer.exists(), ${'$'}this, {})" }
          ],
          "status": "final",
          "code": {
            "coding": [
              { "system": "http://snomed.info/sct", "code": "171149006", "display": "Screening for malignant neoplasm of cervix" }
            ]
          },
          "effectiveDateTime": "1900-01-01",
          "_effectiveDateTime": {
            "extension": [
              { "url": "$templateExtractValue",
                "valueString": "item.where(linkId = 'lastScreenDate').answer.value.first()" }
            ]
          },
          "subject": {
            "reference": "",
            "_reference": {
              "extension": [{ "url": "$templateExtractValue", "valueString": "subject.reference" }]
            }
          }
        }
      ]
    }
    """
      .trimIndent()

  private fun response(vararg items: String) =
    """
    {
      "resourceType": "QuestionnaireResponse",
      "id": "qr-capture",
      "status": "completed",
      "questionnaire": "http://smart.who.int/cxca/Questionnaire/QCXCAScreeningDataTemplated",
      "subject": { "reference": "Patient/cxca-capture" },
      "item": [ ${items.joinToString(",")} ]
    }
    """
      .trimIndent()

  private val hasCervixYes =
    """{ "linkId": "hasCervix", "answer": [{ "valueBoolean": true }] }"""
  private val hasCervixNo =
    """{ "linkId": "hasCervix", "answer": [{ "valueBoolean": false }] }"""
  private val hivPositive =
    """{ "linkId": "hivStatus", "answer": [{ "valueCoding":
       { "system": "http://snomed.info/sct", "code": "165816005", "display": "Human immunodeficiency virus positive" } }] }"""
  private val screened2023 =
    """{ "linkId": "lastScreenDate", "answer": [{ "valueDate": "2023-05-01" }] }"""

  private fun extract(qrJson: String): List<JsonObject> {
    val q = json.decodeFromString(Resource.serializer(), questionnaire) as Questionnaire
    val qr = json.decodeFromString(Resource.serializer(), qrJson) as QuestionnaireResponse
    val bundle = TemplateExtractionEngine.extract(q, qr)
    return bundle.entry.mapNotNull { entry ->
      entry.resource?.let {
        json.parseToJsonElement(json.encodeToString(Resource.serializer(), it)).jsonObject
      }
    }
  }

  private fun JsonObject.type() = get("resourceType")!!.jsonPrimitive.content

  private fun JsonObject.firstCode() =
    get("code")!!.jsonObject["coding"]!!.jsonArray.first().jsonObject["code"]!!.jsonPrimitive.content

  private fun JsonObject.subjectReference() =
    get("subject")!!.jsonObject["reference"]!!.jsonPrimitive.content

  // ---- The FML map's truth table, replayed through template extraction ----

  @Test
  fun shouldExtractHivObservationAndScreeningObservation() {
    val resources = extract(response(hasCervixYes, hivPositive, screened2023))

    assertEquals(
      listOf("Observation", "Observation"),
      resources.map { it.type() },
      "cervix=true must emit no Condition; got $resources",
    )

    val hivObs = resources.first { it.firstCode() == "165816005" }
    assertEquals("final", hivObs["status"]!!.jsonPrimitive.content)
    assertEquals("Patient/cxca-capture", hivObs.subjectReference())

    val screenObs = resources.first { it.firstCode() == "171149006" }
    // The date answer lands in effectiveDateTime at date precision — no coercion shim needed
    // (the android app's CxcaExtractor exists solely to work around HAPI rejecting this).
    assertEquals("2023-05-01", screenObs["effectiveDateTime"]!!.jsonPrimitive.content)
    assertEquals("Patient/cxca-capture", screenObs.subjectReference())
  }

  @Test
  fun shouldExtractAbsenceOfCervixConditionWhenCervixAbsent() {
    val resources = extract(response(hasCervixNo))
    assertEquals(1, resources.size, "cervix=false alone must emit exactly the Condition")
    assertEquals("Condition", resources.first().type())
    assertEquals("37687000", resources.first().firstCode())
    assertEquals("Patient/cxca-capture", resources.first().subjectReference())
  }

  @Test
  fun shouldExtractNothingWhenCervixPresentAndNothingElseAnswered() {
    assertTrue(extract(response(hasCervixYes)).isEmpty())
  }

  // ---- Pinned upstream finding ----

  /**
   * kotlin-fhirpath derives the reserved environment variables itself, masking caller-supplied
   * bindings: with the SAME variables map, `%resource` (which SDC defines as the
   * QuestionnaireResponse in extraction expressions) evaluates to empty at item focus, while a
   * non-reserved name bound to the same object navigates fine. This is why
   * kotlin-fhir-data-capture's item-scope templates cannot reach the QR — and why this file's
   * templates live at questionnaire level. If this test FAILS, the masking has been fixed
   * upstream: item-level templates using `%resource` become viable and this file simplifies.
   */
  @Test
  fun reservedVariableMaskingIsWhyTemplatesLiveAtQuestionnaireLevel() {
    val qr =
      json.decodeFromString(Resource.serializer(), response(hasCervixNo)) as QuestionnaireResponse
    val item = qr.item.first()
    val engine = FhirPathEngine.forR4()
    val variables = mapOf("resource" to qr, "custom" to qr)

    val reserved = engine.evaluateExpression("%resource.subject.reference", item, variables)
    val custom = engine.evaluateExpression("%custom.subject.reference", item, variables)

    assertTrue(reserved.isEmpty(), "masking fixed upstream? %resource returned $reserved")
    assertEquals("Patient/cxca-capture", custom.single().toString())
  }
}
