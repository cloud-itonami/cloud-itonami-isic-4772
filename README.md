# cloud-itonami-isic-4772

Open Business Blueprint for **ISIC Rev.4 4772**: retail sale of
pharmaceutical and medical goods, cosmetic and toilet articles — an
online/retail pharmacy and OTC medical-goods storefront, published as an
OSS business that any qualified operator can fork, deploy, run, improve
and sell.

Handles OTC dispensing, Rx dispensing/refills against a verified
prescription, restricted-OTC age/quantity limits, and licensed subscriber
disclosure. Built on this workspace's
[`langgraph`](https://github.com/kotoba-lang/langgraph) StateGraph
runtime (portable `.cljc`, supervised superstep loop, interrupts,
Datomic/in-mem checkpoints) — the same actor pattern as
[`cloud-itonami-isic-6311`](https://github.com/cloud-itonami/cloud-itonami-isic-6311)
and [`cloud-itonami-isic-7820`](https://github.com/cloud-itonami/cloud-itonami-isic-7820).

> **Why an actor layer at all?** A PharmacyOrder-LLM is great at
> normalizing dispense/refill requests and proposing subscriber column
> sets — but it has **no notion of prescription validity, controlled-
> substance ceilings, restricted-OTC age/quantity limits, e-prescribing
> network licensing, or drug-interaction risk**. Letting it dispense
> directly invites a prescription-only item leaving without a valid
> script, a Schedule II refill that is illegal regardless of what a
> record says, an age-restricted OTC sale to a minor, or an allergy-
> flagged interaction reaching a patient unreviewed. This project seals
> the PharmacyOrder-LLM into a single node and wraps it with an
> independent **PharmacyGovernor**, a human (licensed pharmacist) **review
> workflow**, and an immutable **audit ledger**.

## Scope (deliberately narrow — read this before anything else)

This actor decides **whether a dispense/refill is allowed** and records
that it happened. It never handles order-routing, payment or shipping
fulfillment — there is no field anywhere in this schema for those (see
`docs/adr/0001-architecture.md`). Provenance is limited to three real,
citable public reference sources (`src/pharmacy/facts.cljk`: FDA NDC
Directory, DEA Controlled Substance Schedules, NPPES NPI Registry) or an
operator-registered `:licensed-erx-network` — every prescription-exists
fact must resolve to one of these, never a bare "the LLM inferred it".

**`:rx/dispense`/`:rx/refill` never auto-commit at any rollout phase** —
even a fully governor-clean fill requires an explicit licensed
pharmacist's approval, because that sign-off is a legal requirement, not
a maturity gate the actor can grow out of.

## Consuming this actor from another blueprint

`:report/query` is the governed read surface (prescription/dispensing
status, columns limited to your contract tier) — it always runs through
the PharmacyGovernor's licensed-disclosure check.

See [`docs/DESIGN.md`](docs/DESIGN.md) for the full architecture and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
decision record. See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md) to start this as an open
business on itonami.cloud.

## The core contract

```
request + injected role/tenant/phase context
        │
        ▼
   ┌───────────────┐    proposal      ┌──────────────────────────┐
   │ PharmacyOrder- │ ────────────────▶│ PharmacyGovernor          │ (independent system)
   │ LLM (sealed)   │  draft + source  │  Rx validity · quantity   │
   └───────────────┘                  │  ceilings · license ·     │
                                       │  interaction · human      │
                                       └──────────────────────────┘
                                              │
                                   commit / dispense only if allowed
                                              ▼
                                    append-only audit ledger
```

**Single invariant**: PharmacyOrder-LLM never dispenses, refills,
discloses, or resolves a dispute the PharmacyGovernor would reject.

## Run

```bash
clojure -M:dev:test   # governor contract · store parity · phases · facts
clojure -M:dev:run    # 10-operation demo through one OperationActor
clojure -M:lint
```

## Non-Negotiables

- Do not commit real patient data, prescriber NPIs, or prescription
  records.
- Do not add a schema field for order-fulfillment/shipping/payment
  logistics.
- Do not bypass the PharmacyGovernor for production dispensing,
  refills or disclosures.
- Do not let `:rx/dispense`/`:rx/refill` auto-commit at any phase.
- Do not fabricate a source-catalog entry or an erx-network record.

License: AGPL-3.0-or-later.
