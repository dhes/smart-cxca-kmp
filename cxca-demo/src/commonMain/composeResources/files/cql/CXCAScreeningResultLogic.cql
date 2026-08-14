/*
 CXCA.S3.DT — Determine the action indicated by a cervical cancer screening result.

 Near-verbatim from the DAK source (dhes/smart-cxca input/cql/CXCAScreeningResultLogic.cql),
 with the same deviation as the S1/S2 ports: explicit .value access in place of the
 FHIRHelpers include. Acts on an explicit HPV-DNA RESULT: positive -> refer for
 triage/treatment; negative -> re-screen at interval; invalid -> repeat the test; no result
 on record -> 'Not applicable' (whether screening is due is CXCA.S2's decision).
*/
library CXCAScreeningResultLogic version '0.1.0'

using FHIR version '4.0.1'

valueset "HPV-positive result": 'http://smart.who.int/cxca/ValueSet/hpv-positive-result'
valueset "HPV-negative result": 'http://smart.who.int/cxca/ValueSet/hpv-negative-result'
valueset "HPV-invalid result": 'http://smart.who.int/cxca/ValueSet/hpv-invalid-result'

context Patient

// ---- Inputs ----

define "HPV Positive":
  exists (
    [Observation: "HPV-positive result"] O
      where O.status.value in { 'final', 'amended' }
  )

define "HPV Negative":
  exists (
    [Observation: "HPV-negative result"] O
      where O.status.value in { 'final', 'amended' }
  )

define "HPV Invalid":
  exists (
    [Observation: "HPV-invalid result"] O
      where O.status.value in { 'final', 'amended' }
  )

// ---- Output: screening-result recommendation status (hitPolicy FIRST) ----

define "Screening result recommendation status":
  if "HPV Positive" then 'Refer for triage/treatment'
  else if "HPV Negative" then 'Rescreen at interval'
  else if "HPV Invalid" then 'Repeat test'
  else 'Not applicable'

define "Guidance":
  case
    when "HPV Positive" then 'HPV screening test positive (high-risk HPV DNA detected) — refer the client for triage and treatment (screen-triage-treat).'
    when "HPV Negative" then 'HPV screening test negative (high-risk HPV DNA not detected) — re-screen at the recommended interval (3 years for women living with HIV, otherwise 5 years).'
    when "HPV Invalid" then 'HPV screening test result invalid/inadequate — repeat the HPV-DNA test.'
    else 'No HPV screening result on record; the result-action decision does not apply.'
  end
