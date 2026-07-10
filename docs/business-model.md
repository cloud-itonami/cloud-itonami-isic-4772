# Open Business Blueprint: cloud-itonami-isic-4772

This repository publishes an OSS business model for operating an
online/retail pharmacy and OTC medical-goods storefront on itonami.cloud,
with a governed-dispensing operating model: an LLM drafts, an independent
Governor and a licensed pharmacist decide.

## Classification

- Repository name: `cloud-itonami-isic-4772`
- Primary classification: ISIC Rev.4 4772
- Activity: retail sale of pharmaceutical and medical goods, cosmetic and
  toilet articles
- Served domain: OTC dispensing, Rx dispensing/refill against a verified
  prescription, restricted-OTC compliance, licensed subscriber
  disclosure

The pharmacy actor is the sixth `:spec → real repo` promotion in
`kotoba-lang/industry`'s registry (after `M6910`, `isic-8291`,
`isic-4690`, `isic-4610`, `isic-6311`).

## Customer

Primary customers (contracted, licensed access only — never public/
anonymous):

- independent and chain retail pharmacies wanting a governed, audit-ready
  dispensing system without building compliance tooling themselves
- telehealth/online-pharmacy operators needing a licensed e-prescribing
  integration point
- other `cloud-itonami-{ISIC}` blueprint operators needing prescription-
  status data as a licensed capability

## Problem

Pharmacy point-of-sale and dispensing systems are closed, expensive, and
give operators no way to inspect *why* a fill was allowed or blocked.
Compliance failures (controlled-substance over-dispensing, missed
interaction flags, underage restricted-OTC sales) carry real regulatory
and patient-safety risk, and vendors offer no structural guarantee that
an LLM-assisted workflow can't silently bypass a legal requirement.

## Offer

Operators provide an OSS actor for governed pharmacy dispensing:

- OTC dispensing with restricted-category age/quantity enforcement
- Rx dispensing/refill against a verified, unexpired prescription
- controlled-substance schedule ceilings (per-fill quantity, Schedule II
  never-refillable)
- drug-interaction/allergy-flag escalation to a licensed pharmacist
- governed, tier-scoped disclosure (never a public/anonymous query
  surface)
- a dispensing-dispute/adverse-event channel, always human-reviewed
- immutable audit ledger of every dispense/refill/disclosure event

The core promise: PharmacyOrder-LLM can draft normalization and column
proposals, but it cannot dispense, refill, disclose, or resolve a dispute
unless the independent PharmacyGovernor allows it — and Rx dispensing
never bypasses a licensed pharmacist, at any rollout phase.

## Revenue

- per-transaction or monthly platform fee (contract tenant × tier)
- tiered subscriptions: `:tier/basic` (status only) → `:tier/network`
  (+ prescriber/refill detail, for chain-pharmacy portals)
- wholesale API access to other `cloud-itonami-{ISIC}` blueprint
  operators
- managed hosting: monthly subscription per tenant
- e-prescribing network integration: onboarding a real PDMP/erx network
- compliance package: audit export, dispute-handling SLA, security review

## Unit Economics

Track these numbers for every operator:

- erx-network integration hours per new network/jurisdiction
- monthly infrastructure cost
- LLM cost per operation (dispense/refill/disclosure)
- dispute/adverse-event handling hours per tenant
- gross margin after infrastructure, pharmacist labor and support
- churn and expansion revenue per contract tier

## Open Participation

Anyone may fork, run the demo, deploy self-hosted, submit patches,
publish compatible source-catalog extensions (real, citable sources
only), or create a local operator business.

itonami.cloud should require certification — including real pharmacist-
licensing and liability review — before listing an operator as trusted,
routing customer leads, or allowing managed dispensing under the platform
brand.

## Marketplace Metadata

```edn
{:itonami.blueprint/id "cloud-itonami-isic-4772"
 :itonami.blueprint/name "Governed Pharmacy Dispensing Actor"
 :itonami.blueprint/isic-rev4 "4772"
 :itonami.blueprint/domain :health/pharmacy-retail
 :itonami.blueprint/license "AGPL-3.0-or-later"
 :itonami.blueprint/operator-model :certified-open-business
 :itonami.blueprint/repo "https://github.com/cloud-itonami/cloud-itonami-isic-4772"
 :itonami.blueprint/status :public-oss
 :itonami.blueprint/required-technologies [:identity :forms :audit-ledger]
 :itonami.blueprint/optional-technologies [:dmn :bpmn]}
```

## Non-Negotiables

- Do not commit real patient/prescriber data.
- Do not add a schema field for order-fulfillment/shipping/payment
  logistics.
- Do not bypass the PharmacyGovernor for production dispensing.
- Do not let `:rx/dispense`/`:rx/refill` auto-commit at any phase.
- Do not fabricate a source-catalog entry or an erx-network record.
- Do not market an uncertified deployment as an itonami.cloud certified
  operator.
