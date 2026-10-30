# Jira Migration G3 — RPL-88 Reader Independent Semantics Review

**2026-10-30 KST · READ-ONLY REVIEW · No Jira Cutover · No Code Correction · No Deploy**

Reviewed exact current private audit branch `authority_reader.py`, blob `c1f69c53f2207af668a9b30a6dee31fc8212fd5e`, and `test_authority_reader.py`, blob `ace7e56818b6d558de3722b88ec89a2226b0434d`.
Not a deployed production consumer. This review is a **source-level finding**, not a production incident.

## MAJOR-01 — Return classification and declared ReadAuthority disagree on verified legacy path

- `resolve()` chooses `authority='legacy'` when `Phase='verified'`/`ReadAuthority='legacy'`/`WriteAuthority='frozen'`.
- It validates the legacy field hash, but on **both** legacy and cutover paths returns `classification={product: TargetOptionIDs.product,component: TargetOptionIDs.component,concern: TargetOptionIDs.concern}`.
- This is ambiguous as an *effective classification read*: a consumer can receive `ReadAuthority=legacy` together with Target-derived classification. If the return is intended as *Target comparison/preview*, the field must be named/separated as such and not claimed as an authoritative classification.
- Current tests assert the authority string for legacy but do **not** assert the classification source in either phase.

**Required contract decision before deployment**:
1. If `classification` means **effective source of truth**, in verified legacy mode it must be built from the actual verified Legacy fields (Product/Component/Labels), in cutover mode from Target values. Contract type may differ between Legacy multi-valued strings and Target single/multi Option IDs; specify a tagged type, not a silent semantic conversion.
2. If `classification` is **Target staging preview**, rename it `target_preview` and provide a separately typed effective Legacy classification. Consumers must not treat `target_preview` as the authoritative read.
3. Mixed Origin must be proven to reject UNKNOWN and distinguish verified `target_create` from verified `legacy_migration` without fallback. The current Reader supports `legacy_migration` only; do not infer mixed Origin support.

Acceptance: tests for both states assert **actual source**, non-empty/empty Legacy, Target drift, UNKNOWN Origin, stale or missing approval/Reader/Drill ref, wrong Issue/Type/Changelog, and no accidental Target leakage in pre-cutover effective read.

## MAJOR-02 — Current rollback simulation proves state toggle, not effective data restoration

`simulate_rollback()` applies synthetic `CutoverApprovalRef`, `ConsumerIntegrationRef`, `RestoreDrillRef`, calls `resolve()` with target phase and legacy phase, then only asserts returned **ReadAuthority labels**.

The `classification` payload remains Target-derived across both states per MAJOR-01; thus the simulation does **not** prove that a user-visible or live consumer's classification actually returns to Legacy. It explicitly says `production_rollback_verified=False`, correctly.

**Acceptance**: non-mutating dry-run can remain as a unit test, but a controlled authorized recovery drill must demonstrate a real consumer's before→cutover→restore effective classification, or document precisely why no manifest-aware consumer exists and what user-visible contract is authoritative. Preserve Jira values/snapshot. Do not create synthetic approval refs in a production ledger.

## G4 / Owner boundary

Native Jira Board/Automation checks already found no Legacy classification references in audited scope; other-user private filters remain UNKNOWN. The existing `authority_reader.py` is **not** an integrated Board/Filter/Automation Reader. Do not build a new server without identifying a real consumer. First decide whether migration's operational classification authority is Jira Native JQL/filters or a manifest-aware application-level Reader, and map required operational evidence accordingly.

**Overall verdict: CHANGES_REQUIRED_FOR_LIVE_READER_USE / CURRENT_AUDIT_ONLY**. RPL-88 remains `verified/legacy/frozen`, `CutoverApprovalRef=null`. All Jira Creates, source merges, cutovers, and deletions out of scope.
