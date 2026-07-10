# Governance

`cloud-itonami-isic-4772` is an OSS open-business blueprint. Governance
covers both code and the operator model.

## Maintainers

Maintainers may merge changes that preserve these invariants:

- PharmacyOrder-LLM cannot directly dispense, refill, disclose or resolve
  a dispute.
- PharmacyGovernor remains independent of the advisor.
- hard governor violations (prescription-verification-gate,
  restricted-quantity-gate, source-provenance-gate, licensed-disclosure)
  cannot be overridden by human approval.
- a dispensing dispute never auto-resolves, at any rollout phase.
- `:rx/dispense`/`:rx/refill` never auto-commit at any phase — a licensed
  pharmacist's sign-off is structural, not a rollout-maturity gate.
- every commit, hold and disclosure event is auditable.
- no schema field exists for order-fulfillment/shipping/payment logistics
  — scope is structural, not a runtime filter someone could forget to
  call.
- real patient/prescriber/network credentials stay outside Git.

## Decision Records

Architecture decisions live in `docs/adr/`. Changes to the trust model,
storage contract, disclosure scope, public business model, operator
certification or license should add or update an ADR.

## Operator Governance

Anyone may fork and operate independently. itonami.cloud certification is a
separate trust mark and should require security, audit, support and
data-flow review, plus a real pharmacist-licensing/liability review before
any production deployment.

Certified operators can lose certification for:

- bypassing governor checks
- disclosing patient data to an uncontracted party
- dispensing a controlled/Rx item without a licensed pharmacist's
  approval
- misrepresenting certification status
- failing to respond to security incidents or dispensing disputes
- hiding material changes to customer-facing operation
