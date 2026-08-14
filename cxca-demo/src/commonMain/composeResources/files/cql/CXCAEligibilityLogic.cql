/**
Library: CXCA.S1.DT Eligibility Logic

@DecisionID: CXCA.S1.DT
@BusinessRule: Determine cervical cancer screening eligibility
@Trigger: CXCA.S1 Determine cervical cancer screening eligibility
@HitPolicy: First
@Description: Determine whether a client is in the cervical cancer screening target
population -- the screening-eligible cohort (= the coverage Denominator, per WHO 2021
guideline section 5.2.8). Eligibility depends on the presence of a cervix and the
recommended screening age range, whose LOWER bound is HIV-dependent: women living with HIV
are eligible from age 25, the general population from age 30; the upper bound is 65 for
both. The interval / "is screening due now?" decision is factored into the separate CXCA.S2
DueForScreening library, which consumes this library's "Eligibility status" output.

Data Concepts:
 * CXCA.DE.HasCervix: Has cervix | Whether the client has a cervix (absent if prior total hysterectomy / congenital absence) -- the CMS124 Denominator Exclusion
 * CXCA.DE.HIVStatus: HIV status | Whether the client is living with HIV (sets the lower screening start age: 25 if WLHIV, otherwise 30)
 * CXCA.DE.Age: Age | Calculated age (completed years) of the client based on date of birth

WHO guideline for screening and treatment of cervical pre-cancer lesions for cervical cancer prevention, 2nd ed. (2021).
*/
library CXCAEligibilityLogic

using FHIR version '4.0.1'

include FHIRHelpers version '4.0.1'

// Terminology is bound in the DAK terminology layer; referenced here by canonical.
valueset "Absence of cervix": 'http://smart.who.int/cxca/ValueSet/absence-of-cervix'
valueset "HIV-positive status": 'http://smart.who.int/cxca/ValueSet/hiv-positive-status'

parameter "Today" default Today()

context Patient

// ---- Inputs ----

/*
 @input: "Has cervix"
 @pseudocode: NOT (prior total hysterectomy with no residual cervix OR congenital absence of cervix)
 CMS124 Denominator Exclusion (absence of cervix). Modelled as a recorded Condition;
 a Terminologist must confirm the value set + resource type before clinical use.
 */
define "No Cervix":
  exists ([Condition: "Absence of cervix"])

define "Has Cervix":
  not "No Cervix"

/*
 @input: "HIV status"
 @pseudocode: 'HIV status' = 'HIV-positive'
 HIV status sets the lower screening start age: women living with HIV are eligible from 25,
 the general population from 30 (WHO 2021). Negative or unknown -> general (start age 30).
 */
define "Living with HIV":
  exists (
    [Observation: "HIV-positive status"] O
      where O.status in { 'final', 'amended' }
  )

/*
 @input: "Age"
 @pseudocode: Today's date - 'Date of birth'
 Cast Today to a date: Patient.birthDate is a date (no time), so comparing it against a
 dateTime Today yields an UNCERTAIN age ([n-1, n]) on the exact birthday, which collapses
 the eligibility decision to 'Not eligible' for someone turning the start age that very day.
 date-vs-date is definite. (CXCADueForScreeningLogic already casts `date from Today`.)
 */
define "Current Patient Age In Years":
  AgeInYearsAt(date from Today)

/*
 @input: "Age"
 @pseudocode: lower screening start age for the client's risk group (25 if living with HIV, otherwise 30)
 */
define "Screening Start Age":
  if "Living with HIV" then 25 else 30

/*
 @input: "Age"
 @pseudocode: 'Age' >= risk-group start age AND 'Age' <= 65
 */
define "In Screening Age Range":
  "Current Patient Age In Years" >= "Screening Start Age"
    and "Current Patient Age In Years" <= 65

/*
 @input: "Age"
 @pseudocode: 'Age' < risk-group start age (25 if living with HIV, otherwise 30)
 */
define "Below Start Age":
  "Current Patient Age In Years" < "Screening Start Age"

/*
 @input: "Age"
 @pseudocode: 'Age' > 65 years
 */
define "Age over 65":
  "Current Patient Age In Years" > 65

// ---- Output: eligibility status (hitPolicy FIRST) ----

/*
 @output: "Eligibility status"
 */
define "Eligibility status":
  if "No Cervix" then 'Not eligible'
  else if "Below Start Age" then 'Not eligible'
  else if "Age over 65" then 'Not eligible'
  else if "In Screening Age Range" then 'Eligible'
  else 'Not eligible'

define "Eligible":
  "Eligibility status" = 'Eligible'

/*
 @output: "Guidance displayed to health worker"
 */
define "Guidance":
  case
    when "No Cervix" then 'Client has no cervix (prior total hysterectomy or congenital absence); cervical cancer screening is not applicable.'
    when "Below Start Age" then 'Client has not yet reached the screening start age for their risk group (25 years for women living with HIV, otherwise 30 years); they become eligible at that age. Not yet eligible.'
    when "Age over 65" then 'Client is above the recommended screening age range; routine screening is not indicated (assuming adequate prior screening).'
    when "Eligible" then 'Client is in the cervical cancer screening target population. Proceed to determine whether screening is due.'
    else 'Client is not eligible for cervical cancer screening.'
  end
