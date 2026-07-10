# Security Policy

This project handles prescription, controlled-substance and patient
allergy/interaction data. Treat vulnerabilities as potentially high impact
even when the demo data is synthetic — bad dispensing decisions reaching a
real fulfillment system have direct patient-safety consequences.

## Do Not Disclose Publicly

Report privately before opening public issues for:

- credential or erx-network-key exposure
- PharmacyGovernor bypass (prescription-verification-gate,
  restricted-quantity-gate, source-provenance-gate, licensed-disclosure)
- audit-ledger tampering
- over-disclosure beyond a subscriber contract's tier
- tenant isolation failures
- dispensing of a controlled/Rx item through an undocumented path
- an interaction/allergy flag being suppressed instead of escalated

## Reporting

Use GitHub private vulnerability reporting when available for the
repository. If that is unavailable, contact the repository maintainers
through the cloud-itonami organization before publishing details.

Include:

- affected commit or version
- reproduction steps
- expected and actual behavior
- impact on patient data, governor enforcement or audit logging
- suggested fix, if known

## Production Guidance

- Store secrets and erx-network keys outside Git.
- Run governor tests before deployment.
- Export and review audit logs regularly.
- Use least privilege for pharmacists, clerks and service accounts.
- Alert on any prescription-verification-gate or restricted-quantity-gate
  HOLD spike — it may indicate a compromised or malfunctioning upstream
  e-prescribing feed.
