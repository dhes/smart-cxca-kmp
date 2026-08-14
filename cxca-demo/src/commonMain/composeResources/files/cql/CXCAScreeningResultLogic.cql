/**
Library: CXCA.S3.DT Screening-Result Logic

@DecisionID: CXCA.S3.DT
@BusinessRule: Determine the action indicated by a cervical cancer screening result
@Trigger: CXCA.S3 Act on a cervical cancer screening result
@HitPolicy: First
@Description: Given a recorded HPV-DNA primary screening RESULT, determine the indicated
action, per the WHO 2021 screen-triage-treat guideline. The decision acts on an explicit
result: a POSITIVE result (high-risk HPV DNA detected) indicates referral for triage and
treatment; a NEGATIVE result (high-risk HPV DNA not detected) indicates re-screening at the
recommended interval; an INVALID result indicates repeating the HPV-DNA test. When no HPV
result is on record the decision is 'Not applicable' (there is nothing to act on; whether
screening is due is decided separately in CXCA.S2). NB: the decision keys on the RESULT, not
on whether a screening event exists -- a recorded screening date with no result (e.g. a
historical screen known only by date) does NOT by itself trigger an action; only an explicit
invalid result drives 'Repeat test'. (Distinguishing a current screen awaiting its result
from a historical one would require encounter/visit awareness this stateless logic does not
have; that, and a single value-based screening Observation, are deferred to the structural
harmonization with smart-hiv.)

Data Concepts:
 * CXCA.DE.HpvResult: HPV screening result | Result of the HPV-DNA primary screening test

WHO guideline for screening and treatment of cervical pre-cancer lesions for cervical cancer prevention, 2nd ed. (2021).
*/
library CXCAScreeningResultLogic

using FHIR version '4.0.1'

include FHIRHelpers version '4.0.1'

// Terminology is bound in the DAK terminology layer; referenced here by canonical.
valueset "HPV-positive result": 'http://smart.who.int/cxca/ValueSet/hpv-positive-result'
valueset "HPV-negative result": 'http://smart.who.int/cxca/ValueSet/hpv-negative-result'
valueset "HPV-invalid result": 'http://smart.who.int/cxca/ValueSet/hpv-invalid-result'

context Patient

// ---- Inputs ----

/*
 @input: "HPV screening result"
 @pseudocode: 'HPV screening result' = 'High-risk HPV DNA detected'
 */
define "HPV Positive":
  exists (
    [Observation: "HPV-positive result"] O
      where O.status in { 'final', 'amended' }
  )

/*
 @input: "HPV screening result"
 @pseudocode: 'HPV screening result' = 'High-risk HPV DNA not detected'
 */
define "HPV Negative":
  exists (
    [Observation: "HPV-negative result"] O
      where O.status in { 'final', 'amended' }
  )

/*
 @input: "HPV screening result"
 @pseudocode: 'HPV screening result' = 'Invalid'
 */
define "HPV Invalid":
  exists (
    [Observation: "HPV-invalid result"] O
      where O.status in { 'final', 'amended' }
  )

// ---- Output: screening-result recommendation status (hitPolicy FIRST) ----

/*
 @output: "Screening-result recommendation status"
 */
define "Screening result recommendation status":
  if "HPV Positive" then 'Refer for triage/treatment'
  else if "HPV Negative" then 'Rescreen at interval'
  else if "HPV Invalid" then 'Repeat test'
  else 'Not applicable'

/*
 @output: "Guidance displayed to health worker"
 */
define "Guidance":
  case
    when "HPV Positive" then 'HPV screening test positive (high-risk HPV DNA detected) — refer the client for triage and treatment (screen-triage-treat).'
    when "HPV Negative" then 'HPV screening test negative (high-risk HPV DNA not detected) — re-screen at the recommended interval (3 years for women living with HIV, otherwise 5 years).'
    when "HPV Invalid" then 'HPV screening test result invalid/inadequate — repeat the HPV-DNA test.'
    else 'No HPV screening result on record; the result-action decision does not apply.'
  end
