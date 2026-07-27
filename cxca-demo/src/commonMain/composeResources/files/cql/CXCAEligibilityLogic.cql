/*
 * Near-verbatim CXCAEligibilityLogic from dhes/smart-cxca (the WHO SMART cervical-cancer DAK).
 * Differs from the DAK source only in explicit `.value` primitive access (no FHIRHelpers
 * include) and AgeInYears() (the engine supplies today).
 */
library CXCAEligibilityLogic version '1.0.0'

using FHIR version '4.0.1'

valueset "HIV-positive status": 'http://smart.who.int/cxca/ValueSet/hiv-positive-status'
valueset "Congenital absence of cervix": 'http://smart.who.int/cxca/ValueSet/absence-of-cervix'
valueset "Total hysterectomy": 'http://smart.who.int/cxca/ValueSet/total-hysterectomy'

context Patient

define "Female Sex": Patient.gender.value = 'female'
define "Congenital Absence of Cervix": exists ([Condition: "Congenital absence of cervix"])
define "Acquired Absence of Cervix": exists ([Condition: "Total hysterectomy"])
define "Has Cervix": "Female Sex" and not "Congenital Absence of Cervix" and not "Acquired Absence of Cervix"
define "No Cervix": not "Has Cervix"

define "Living with HIV":
  exists ([Observation: "HIV-positive status"] O where O.status.value in { 'final', 'amended' })

define "Current Patient Age In Years": AgeInYears()
define "Screening Start Age": if "Living with HIV" then 25 else 30
define "In Screening Age Range":
  "Current Patient Age In Years" >= "Screening Start Age" and "Current Patient Age In Years" <= 65
define "Below Start Age": "Current Patient Age In Years" < "Screening Start Age"
define "Age over 65": "Current Patient Age In Years" > 65

define "Eligibility status":
  if "No Cervix" then 'Not eligible'
  else if "Below Start Age" then 'Not eligible'
  else if "Age over 65" then 'Not eligible'
  else if "In Screening Age Range" then 'Eligible'
  else 'Not eligible'

define "Eligible":
  "Eligibility status" = 'Eligible'

define "Guidance":
  case
    when "No Cervix" then 'Client is not in the cervical cancer screening population (no cervix on record); screening is not applicable.'
    when "Below Start Age" then 'Client has not yet reached the screening start age for their risk group (25 years for women living with HIV, otherwise 30 years); they become eligible at that age. Not yet eligible.'
    when "Age over 65" then 'Client is above the recommended screening age range; routine screening is not indicated (assuming adequate prior screening).'
    when "Eligible" then 'Client is in the cervical cancer screening target population. Proceed to determine whether screening is due.'
    else 'Client is not eligible for cervical cancer screening.'
  end
