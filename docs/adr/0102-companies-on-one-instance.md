# ADR-0102: The companies of one instance share one operator, who vouches for all their admins

- Status: Accepted
- Date: 2026-10-27
- Decided by: the owner, 2026-10-27
- Sources: [the security review of 2026-10-27](../reviews/2026-10-27-security-review.md), D4 and its symptoms H1, H4, M16 and L5, and §8.6 point 3; [ADR-0075](0075-companies-and-sign-in-from-the-deployment.md) (companies and sign-in from the deployment, one user table); [ADR-0086](0086-saml-sign-in.md) (SAML); [ADR-0087](0087-scim-provisioning.md) (SCIM); [ADR-0092](0092-personal-runner-hosts-in-every-company.md) (a personal host in each of its owner's companies); the [threat model](../threat-model.md), trust zone 1 (§2.2), §2.4, R3, R7, R59 to R62
- Changes: the threat model's trust zone 1, its rows 16.9, 17.2 to 17.4, R3 and R7 restated, R59 to R62; `docs/operations/companies-and-sign-in.md` and the public sign-in guide; the hardening of WP1 of the fix round (the SCIM binding by the provisioning company, never an instance admin; SCIM deactivating on the instance only its own users; the SAML entity ID pinned, `V075`)

## Context

- ADR-0075 declares companies in the deployment and keeps one user table for the instance: a person is one row, a member of each company that adds them. The design never said whether the admins of two companies on one instance trust each other, and the threat model's rows, written per boundary, never named that adversary (the review's D4).
- Several instance-wide objects cross the line between companies: a company's SCIM adopts any user of the instance by email (ADR-0087 decision 7); an OWNER or ADMIN adds any user by email and learns from `USER_NOT_FOUND` whether an address has an account; a SAML provider's entity ID, which keys every SAML user, came from its own metadata document; a personal access token reached every company of its user; a personal runner host is present in every company of its owner (ADR-0092).
- With admins who do not trust each other, these give account takeover across companies (H1, H4, M16) and an existence oracle (R3, L5). With one operator who vouches for every admin, they are what one organisation's administrators can already do.

## Decision

**The companies of one instance are declared by one operator who vouches for all their admins. An instance never hosts companies that do not trust each other; an operator who serves several customers gives each customer an instance of its own.**

1. **One operator per instance.** The operator who declares the companies of an instance (ADR-0075) vouches for every OWNER and ADMIN of each of them: the companies of one group, or a company and its test companies. Companies whose admins do not trust each other run on separate instances. The threat model says so in trust zone 1, and the operator guide where companies are declared.
2. **A user is one row of the instance.** An admin of any company may add any user by email, which attaches them without their consent, and learns whether an address has an account (`USER_NOT_FOUND`); a company's SCIM adopts users by email and its answer names them. No invitation flow and no generic answer are built. These are accepted risks with this reason (R59 to R61; R3 and R7 restated).
3. **The cheap checks are built anyway, as hardening, not as a boundary.** A first sign-in binds a SCIM user only through a provider of the company whose SCIM created them (`scim_company_id`), and never an OWNER or an instance admin; a company's SCIM deactivates on the instance only users it created, and otherwise ends the membership; a SAML provider's entity ID is pinned by `_ENTITY_ID` or by its first good metadata document, and no two providers that read different metadata, nor a SAML provider and an OIDC issuer, share one (R62 for the first document). They close the takeovers on an instance that was declared wrongly.
4. **A personal access token names its companies** (the owner's decision Q6, D9): a user of several companies chooses which ones a token reaches.

## Consequences

- The positions table of the threat model (§2.4) lists "a company on a shared instance" as accepted on this rule, with the hardening as its control.
- An operator who wants to offer Tinfirm to companies that do not know each other runs one instance per company, the simpler security story the review's §8.6 point 3 points to.
- After the fix round the operator reviews, on an instance with several companies, the SCIM users who never signed in and hold memberships in more than one company, and moves any company that does not share the operator to its own instance ([the record's §4](../reviews/2026-10-27-security-review-fixed.md#4-settings-and-steps-outside-the-code)).
- Nothing in the code enforces the rule: it is the operator's declaration, as the companies themselves are.

## Alternatives considered

- **Companies that do not trust each other on one instance.** It needs an invitation the user accepts instead of an add by email, a generic answer for an unknown address, SCIM and SAML identities bound to their company, and the instance-wide objects reviewed one by one. Rejected by the owner: companies that do not trust each other are better served by an instance each.
- **One company per instance.** Simplest, but the companies of one group, or a company and its test companies, would need an instance each, with their users signed up several times.
