# Operator Guide

This guide is for people who want to start an open business from
`cloud-itonami-isic-4772`.

## 1. Fork and Run

```bash
git clone https://github.com/cloud-itonami/cloud-itonami-isic-4772
cd cloud-itonami-isic-4772
clojure -M:dev:test
clojure -M:dev:run
```

The default demo uses entirely fictitious patients, prescribers and
items. Production patient/prescription records must stay outside the
repository and be injected through a store adapter.

## 2. Choose an Operating Mode

| Mode | Use when |
|---|---|
| Demo | validating the actor and governor contract |
| Self-host | one pharmacy owns infrastructure and data |
| Managed tenant | an operator hosts for a customer pharmacy |
| Certified operator | itonami.cloud has reviewed security, process AND pharmacist-licensing controls |

## 3. Production Checklist

- register a real, licensed `erx-network` integration (a real PDMP/
  e-prescribing network — never fabricate one; free public sources only
  ground drug identity, DEA schedule and prescriber NPI, not the
  prescription-exists fact itself)
- configure Datomic Local, kotoba-server or an equivalent durable SSoT
- configure the LLM adapter through environment variables or secret
  manager
- define subscriber contract tenants/tiers and RBAC rules
- run `clojure -M:dev:test`
- run `clojure -M:lint`
- verify audit-ledger export
- document backup and restore, incident response, and the dispensing-
  dispute/adverse-event handling SLA
- get written legal review for every jurisdiction served — controlled-
  substance schedules, refill limits and restricted-OTC rules vary by
  jurisdiction, and R0 covers exactly 3 free reference sources, honestly
  reported by `pharmacy.facts/coverage`, never oversold
- confirm every `:rx/dispense`/`:rx/refill` reaches a real licensed
  pharmacist for final sign-off in your deployment — this is a
  structural property of the phase table, not a configuration a deployer
  can accidentally disable

## 4. Sales Motion

Start with a narrow offer:

1. onboard one real, licensed erx-network integration
2. prove governed OTC dispensing end to end
3. run one Rx-dispense workflow with a real pharmacist reviewer
4. export the audit ledger for review
5. convert to a metered or subscription contract

## 5. Certification Requirements

itonami.cloud certification should require:

- passing tests and lint on the published version
- written data-flow diagram (erx-network → governor → pharmacist →
  dispense)
- proof that every Rx dispense/refill reaches a real licensed pharmacist
- proof that real patient/prescription data is not stored in Git
- proof that a dispensing-dispute/adverse-event channel exists and is
  human-reviewed
- customer-facing support and licensing terms
- jurisdiction-specific pharmacy-licensing and liability documentation

## 6. Operator Responsibilities

Operators are responsible for:

- lawful pharmacy licensing in every jurisdiction served
- real pharmacist staffing and liability coverage for every dispense
- secure infrastructure and tenant isolation
- honest source-catalog and erx-network maintenance
- data-retention policy consistent with health-record regulations
- security updates

The OSS project provides software and an operating blueprint. It does
not make an operator a licensed pharmacy by itself, and it does not
substitute for real pharmacist judgment or licensing.
