# Contributing

`cloud-itonami-isic-4772` accepts contributions to the OSS actor, governor
tests, documentation, examples and open business blueprint.

## Development

```bash
kbb -M:dev:test
kbb -M:lint
```

Keep changes small and include tests for governor, audit, store or
disclosure behavior.

## Rules

- Do not commit real patient data, real prescriber NPIs, real prescription
  records or real network credentials.
- Keep production dispensing, refills and disclosures behind
  PharmacyGovernor.
- Treat every new item/schedule/jurisdiction as high-risk: add tests for
  prescription-verification-gate, restricted-quantity-gate,
  source-provenance-gate, licensed-disclosure, confidence floor and audit
  logging.
- Never fabricate a source-catalog entry or an erx-network record to
  expand apparent coverage.
- Document any new business-model or operator assumption in `docs/`.

## Pull Requests

PRs should describe:

- what behavior changed
- which governor invariant is affected
- how it was tested
- whether operator or certification docs need updates
