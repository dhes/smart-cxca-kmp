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

import dev.ohs.fhir.workflow.activity.ActivityFlow
import dev.ohs.fhir.workflow.activity.phase.Phase
import dev.ohs.fhir.workflow.activity.resource.event.CPGEventResource
import dev.ohs.fhir.workflow.activity.resource.event.CPGCommunicationEvent
import dev.ohs.fhir.workflow.activity.resource.request.CPGCommunicationRequest
import dev.ohs.fhir.workflow.activity.resource.request.CPGRequestResource
import dev.ohs.fhir.workflow.activity.resource.request.Status

/**
 * Drives an [ActivityFlow] through its plan/order/perform transitions.
 *
 * Mirrors android-fhir's ActivityHandler: before each `prepareX`/`initiateX` pair, the current
 * request phase must first be activated (its resource flipped to [Status.ACTIVE] and persisted),
 * since every phase's `prepare` requires the incoming request to already be active.
 */
class ActivityHandler(
  private val activityFlow: ActivityFlow<CPGCommunicationRequest, CPGCommunicationEvent>
) {
  suspend fun prepareAndInitiatePlan(): Result<Unit> = runCatching {
    activateCurrentRequest()
    val preparedPlan = activityFlow.preparePlan().getOrThrow()
    activityFlow.initiatePlan(preparedPlan).getOrThrow()
  }

  suspend fun prepareAndInitiateOrder(): Result<Unit> = runCatching {
    activateCurrentRequest()
    val preparedOrder = activityFlow.prepareOrder().getOrThrow()
    activityFlow.initiateOrder(preparedOrder).getOrThrow()
  }

  suspend fun prepareAndInitiatePerform(): Result<Unit> = runCatching {
    activateCurrentRequest()
    val preparedEvent =
      activityFlow
        .preparePerform<CPGCommunicationEvent>("CPGCommunicationEvent")
        .getOrThrow()
    activityFlow.initiatePerform(preparedEvent).getOrThrow()
  }

  @Suppress("UNCHECKED_CAST")
  private suspend fun activateCurrentRequest() {
    val currentPhase = activityFlow.getCurrentPhase() as Phase.RequestPhase<CPGRequestResource<*>>
    currentPhase
      .update(currentPhase.getRequestResource().apply { setStatus(Status.ACTIVE) })
      .getOrThrow()
  }
}
