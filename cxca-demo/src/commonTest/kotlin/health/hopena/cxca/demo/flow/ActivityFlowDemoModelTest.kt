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

import dev.ohs.fhir.model.r4.CommunicationRequest
import dev.ohs.fhir.workflow.repository.WorkflowRepository
import health.hopena.cxca.demo.data.InMemoryDemoRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ActivityFlowDemoModelTest {

  private fun newModel(
    repository: WorkflowRepository = InMemoryDemoRepository(),
    configuration: DemoConfiguration = WLHIV_27,
  ) = ActivityFlowDemoModel(repository, configuration)

  private fun cards(model: ActivityFlowDemoModel) = model.cards.value.associateBy { it.phase }

  @Test
  fun shouldStartAtInitializeWhenDependenciesAreNotInstalled() = runTest {
    val model = newModel()
    model.refresh()

    assertEquals(FlowPhase.INITIALIZE, model.phase.value)
    assertFalse(model.initialized.value)
    cards(model).values.forEach { assertFalse(it.isActive) }
  }

  @Test
  fun shouldEnableProposalWhenDependenciesAreInstalled() = runTest {
    val model = newModel()
    model.installDependencies()

    assertTrue(model.initialized.value)
    assertEquals(FlowPhase.PROPOSAL, model.phase.value)
    assertTrue(cards(model).getValue(FlowPhase.PROPOSAL).isActive)
  }

  @Test
  fun shouldGenerateEligibilityProposalWhenProposalPhaseStarts() = runTest {
    val model = newModel()
    model.installDependencies()

    model.start(FlowPhase.PROPOSAL)

    val proposal = cards(model).getValue(FlowPhase.PROPOSAL)
    assertTrue(proposal.details.contains("Intent : proposal"))
    assertTrue(proposal.details.contains("Status : ACTIVE"))
    assertTrue(proposal.details.contains("Eligibility: Eligible"))
    assertTrue(proposal.details.contains("Proceed to determine whether screening is due"))

    assertEquals(FlowPhase.PLAN, model.phase.value)
    assertTrue(cards(model).getValue(FlowPhase.PLAN).isActive)
  }

  @Test
  fun shouldWalkProposalThroughPlanOrderAndPerform() = runTest {
    val model = newModel()
    model.installDependencies()
    model.start(FlowPhase.PROPOSAL)

    model.start(FlowPhase.PLAN)
    var phaseCards = cards(model)
    assertTrue(phaseCards.getValue(FlowPhase.PLAN).details.contains("Intent : plan"))
    assertTrue(phaseCards.getValue(FlowPhase.PROPOSAL).details.contains("Status : COMPLETED"))

    model.start(FlowPhase.ORDER)
    phaseCards = cards(model)
    assertTrue(phaseCards.getValue(FlowPhase.ORDER).details.contains("Intent : order"))
    assertTrue(phaseCards.getValue(FlowPhase.PLAN).details.contains("Status : COMPLETED"))

    // The Communication event is initiated, not carried through to completion — as the upstream
    // demo leaves it. The eligibility payload rides along onto the event.
    model.start(FlowPhase.PERFORM)
    phaseCards = cards(model)
    assertTrue(phaseCards.getValue(FlowPhase.PERFORM).details.contains("Status : PREPARATION"))
    assertTrue(phaseCards.getValue(FlowPhase.PERFORM).details.contains("Eligibility: Eligible"))
    assertTrue(phaseCards.getValue(FlowPhase.ORDER).details.contains("Status : COMPLETED"))
    assertEquals(FlowPhase.NONE, model.phase.value)
  }

  @Test
  fun shouldResumeAHalfFinishedFlowWhenRelaunched() = runTest {
    val repository = InMemoryDemoRepository()
    val abandoned = newModel(repository)
    abandoned.installDependencies()
    abandoned.start(FlowPhase.PROPOSAL)
    abandoned.start(FlowPhase.PLAN)

    // A fresh model over the same repository, as a relaunched app would be.
    val relaunched = newModel(repository)
    relaunched.refresh()

    assertEquals(FlowPhase.ORDER, relaunched.phase.value)
    val resumed = cards(relaunched)
    assertTrue(resumed.getValue(FlowPhase.PROPOSAL).details.contains("Intent : proposal"))
    assertTrue(resumed.getValue(FlowPhase.PLAN).details.contains("Intent : plan"))
    assertTrue(resumed.getValue(FlowPhase.ORDER).isActive)

    relaunched.start(FlowPhase.ORDER)
    assertTrue(cards(relaunched).getValue(FlowPhase.ORDER).details.contains("Intent : order"))
  }

  @Test
  fun shouldNotResumeARestartedFlowWhenRelaunched() = runTest {
    val repository = InMemoryDemoRepository()
    val abandoned = newModel(repository)
    abandoned.installDependencies()
    abandoned.start(FlowPhase.PROPOSAL)
    abandoned.start(FlowPhase.PLAN)
    abandoned.restart()

    val relaunched = newModel(repository)
    relaunched.refresh()

    assertEquals(FlowPhase.PROPOSAL, relaunched.phase.value)
    cards(relaunched).values.forEach { assertEquals("—", it.details) }
  }

  @Test
  fun shouldDeleteTheFlowsResourcesWhenRestarted() = runTest {
    val repository = InMemoryDemoRepository()
    val model = newModel(repository)
    model.installDependencies()
    model.start(FlowPhase.PROPOSAL)
    model.start(FlowPhase.PLAN)
    model.start(FlowPhase.ORDER)
    val orderId = idOf(model, FlowPhase.ORDER)

    model.restart()

    assertEquals(FlowPhase.PROPOSAL, model.phase.value)
    cards(model).values.forEach { assertEquals("—", it.details) }
    assertEquals(null, repository.read("CommunicationRequest", orderId))

    model.start(FlowPhase.PROPOSAL)
    assertTrue(cards(model).getValue(FlowPhase.PROPOSAL).details.contains("Intent : proposal"))
  }

  // ---- The CXCA.S1.DT truth table, mirroring CXCAEligibilityLogic.cql ----

  @Test
  fun shouldDetermineEligibleWhenWlhivAge27() = runTest {
    // WLHIV lowers the screening start age to 25; 27 >= 25 and <= 65.
    val (status, guidance) = determination(WLHIV_27)
    assertEquals("Eligible", status)
    assertTrue(guidance.contains("Proceed to determine whether screening is due"))
  }

  @Test
  fun shouldDetermineNotEligibleWhenGeneralPopulationAge27() = runTest {
    // General population start age is 30; 27 is below it.
    val (status, guidance) = determination(GENERAL_27)
    assertEquals("Not eligible", status)
    assertTrue(guidance.contains("not yet reached the screening start age"))
  }

  @Test
  fun shouldDetermineEligibleWhenGeneralPopulationAge45() = runTest {
    val (status, guidance) = determination(GENERAL_45)
    assertEquals("Eligible", status)
    assertTrue(guidance.contains("Proceed to determine whether screening is due"))
  }

  @Test
  fun shouldDetermineNotEligibleWhenPostHysterectomy() = runTest {
    // Total hysterectomy (SNOMED 428078001) removes the client from the screening population.
    val (status, guidance) = determination(HYSTERECTOMY_45)
    assertEquals("Not eligible", status)
    assertTrue(guidance.contains("no cervix on record"))
  }

  /** Runs `$apply` for the scenario and returns its (eligibility status, guidance) strings. */
  private suspend fun determination(configuration: DemoConfiguration): Pair<String, String> {
    val repository = InMemoryDemoRepository()
    val handler = ProposalCreationHandler(repository)
    handler.installDependencies(configuration)

    val proposal = assertNotNull(handler.generateProposal(configuration))
    val status = assertNotNull(proposal.resource.reasonCode.firstOrNull()?.text?.value)
    val guidance =
      assertNotNull(
        proposal.resource.payload
          .map { it.content }
          .filterIsInstance<CommunicationRequest.Payload.Content.String>()
          .firstOrNull()
          ?.value
          ?.value
      )
    return status to guidance
  }

  private fun idOf(model: ActivityFlowDemoModel, phase: FlowPhase) =
    model.cards.value
      .first { it.phase == phase }
      .details
      .substringAfter("CommunicationRequest/")
      .substringBefore("\n")
}
