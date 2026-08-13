/*
 CXCA.S2.DT — Determine whether cervical cancer screening is due.

 Near-verbatim from the DAK source (dhes/smart-cxca input/cql/CXCADueForScreeningLogic.cql),
 with the same two deviations as the S1 port: explicit .value access in place of the
 FHIRHelpers include, and nothing else. 'Due' is the care gap: eligible (per the included
 CXCA.S1 library) AND not screened within the recommended interval — 3 years for women
 living with HIV, 5 years for the general population (WHO 2021 screen-triage-treat).
*/
library CXCADueForScreeningLogic version '0.1.0'

using FHIR version '4.0.1'

include CXCAEligibilityLogic version '1.0.0' called Eligibility

valueset "HIV-positive status": 'http://smart.who.int/cxca/ValueSet/hiv-positive-status'
valueset "Cervical cancer screening test": 'http://smart.who.int/cxca/ValueSet/cervical-cancer-screening-test'

parameter "Today" default Today()

context Patient

// ---- Eligibility gate (from the included CXCA.S1 library) ----

define "Eligible":
  Eligibility."Eligible"

// ---- Inputs ----

define "Living with HIV":
  exists (
    [Observation: "HIV-positive status"] O
      where O.status.value in { 'final', 'amended' }
  )

define "Last Cervical Screen Date":
  Max(
    [Observation: "Cervical cancer screening test"] O
      return (O.effective as FHIR.dateTime).value
  )

define "Years Since Last Screen":
  if "Last Cervical Screen Date" is null then null
  else years between (date from "Last Cervical Screen Date") and (date from Today)

define "No Previous Screen":
  "Last Cervical Screen Date" is null

define "Screening Interval Elapsed":
  if "Living with HIV"
    then "No Previous Screen" or "Years Since Last Screen" >= 3
    else "No Previous Screen" or "Years Since Last Screen" >= 5

// ---- Output: screening recommendation status (hitPolicy FIRST) ----

define "Screening recommendation status":
  if not "Eligible" then 'Not applicable'
  else if "Screening Interval Elapsed" then 'Due'
  else 'Not due'

define "Guidance":
  case
    when not "Eligible" then 'Client is not in the screening target population; due-for-screening does not apply.'
    when "Living with HIV" and "Screening Interval Elapsed" then 'Women living with HIV: offer HPV-DNA screening and re-screen every 3 years.'
    when "Screening Interval Elapsed" then 'Offer HPV-DNA testing as the primary screening test (general population interval: every 5 years).'
    else 'Client was screened within the recommended interval; no screening required at this visit.'
  end
