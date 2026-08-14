/**
Library: CXCA.S2.DT Due-for-Screening Logic

@DecisionID: CXCA.S2.DT
@BusinessRule: Determine whether cervical cancer screening is due
@Trigger: CXCA.S2 Determine whether cervical cancer screening is due
@HitPolicy: First
@Description: GIVEN a client in the screening-eligible cohort (the Denominator -- see
CXCA.S1 CXCAEligibilityLogic, which already applies the cervix and HIV-dependent age
criteria), determine whether cervical cancer screening is due now, per the WHO 2021
screen-triage-treat guideline. 'Due' is the care gap = Denominator AND NOT Numerator: the
client is eligible AND has NOT been screened within the recommended interval. The interval
is the only condition decided here -- 5 years for the general population, 3 years for women
living with HIV (the most aggressive WHO-recommended intervals). A non-eligible client
yields 'Not applicable'.

Data Concepts:
 * CXCA.DE.HIVStatus: HIV status | Whether the client is living with HIV (sets the re-screen interval: 3y WLHIV, 5y general)
 * CXCA.DE.LastScreenDate: Date of last cervical cancer screening test

WHO guideline for screening and treatment of cervical pre-cancer lesions for cervical cancer prevention, 2nd ed. (2021).
*/
library CXCADueForScreeningLogic

using FHIR version '4.0.1'

include FHIRHelpers version '4.0.1'
include CXCAEligibilityLogic called Eligibility

// Terminology is bound in the DAK terminology layer; referenced here by canonical.
valueset "HIV-positive status": 'http://smart.who.int/cxca/ValueSet/hiv-positive-status'
valueset "Cervical cancer screening test": 'http://smart.who.int/cxca/ValueSet/cervical-cancer-screening-test'

parameter "Today" default Today()

context Patient

// ---- Eligibility gate (from the included CXCA.S1 library) ----

define "Eligible":
  Eligibility."Eligible"

// ---- Inputs ----

/*
 @input: "HIV status"
 @pseudocode: 'HIV status' = 'HIV-positive'
 */
define "Living with HIV":
  exists (
    [Observation: "HIV-positive status"] O
      where O.status in { 'final', 'amended' }
  )

/*
 @input: "Time since last cervical screen"
 @pseudocode: latest 'Date of cervical cancer screening test'
 */
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

// Interval is met (i.e. screening IS due) when there is no previous screen
// or the last screen was at/over the recommended interval for the client's HIV status.
define "Screening Interval Elapsed":
  if "Living with HIV"
    then "No Previous Screen" or "Years Since Last Screen" >= 3
    else "No Previous Screen" or "Years Since Last Screen" >= 5

// ---- Output: screening recommendation status (hitPolicy FIRST) ----

/*
 @output: "Screening recommendation status"
 */
define "Screening recommendation status":
  if not "Eligible" then 'Not applicable'
  else if "Screening Interval Elapsed" then 'Due'
  else 'Not due'

/*
 @output: "Guidance displayed to health worker"
 */
define "Guidance":
  case
    when not "Eligible" then 'Client is not in the screening target population; due-for-screening does not apply.'
    when "Living with HIV" and "Screening Interval Elapsed" then 'Women living with HIV: offer HPV-DNA screening and re-screen every 3 years.'
    when "Screening Interval Elapsed" then 'Offer HPV-DNA testing as the primary screening test (general population interval: every 5 years).'
    else 'Client was screened within the recommended interval; no screening required at this visit.'
  end
