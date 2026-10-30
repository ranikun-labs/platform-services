# Jira Migration — G3/G4 Native Operational Closure / Owner Gate

**Status:** PROPOSED_OWNER_DECISION / NOT_APPROVED / NO_CUTOVER  
**Prepared:** 2026-10-31 KST  
**Ownership:** Jira Migration ONLY, audit-only. No effect on Jira authority, Git main, accepted Foundation policy, or Developer AI Platform.  
**Sources:** 2026-10-31 Jira Migration Work read-only consumer audit (user-supplied result); direct connected Jira RPL-88 GET/JQL; `ranikun-labs/harness-foundation-docs/main/docs/governance/jira-field-taxonomy.md` (accepted, blob `0c3bc9d7a7b71ff072696c33d21e372d20d76b29`); approved Confluence 07-1 Owner R&R (2026-10-30).

## 1. F1 result

G0/G1/G2 PASS; G3 Native Create Required types 5/5 PASS (RPL-128~132 Origin verified); G3 existing-issue authority NOT_CUTOVER. In-scope G4 NO_REFERENCED_LEGACY_CONSUMER FOUND; global visibility and any private user-owned filters UNKNOWN. Existing RPL-88 remains `Origin=legacy_migration / Phase=verified / ReadAuthority=legacy / WriteAuthority=frozen / CutoverApprovalRef=null`. Do not upgrade this to full G3/G4 PASS.

## 2. Operational consumer matrix — evidence provenance explicit

The following entries are **reported from Work UI read-only inspection**, not independently obtained from Jira Board/Filter API in this audit branch:
- RPL Board #2 internal shared Filter ID `10001`, JQL `project = RPL ORDER BY cf[10019] ASC` (`cf[10019]` Rank). NO_CLASSIFICATION_CONSUMER. Filter owner is active Jira account; `isWritable=false`.
- Accessible active Automation one RPL due-date checking rule; JQL depends on status and due date only. NO_CLASSIFICATION_CONSUMER. Global list accessible to Work reported one active rule.
- Currently saved and globally shared Filter lists: no results within inspected UI. Does **not** prove no other user's private filters.
- Feature/Task Create active field contexts: Target Product/Component/Concern, not Legacy Product/Component. TARGET_REFERENCE. Other Issue Type Create metadata had been inspected separately.
- External Manifest-aware classification consumer: **not identified**. There is only nondeployed `authority_reader.py` offline verifier with MAJOR-01/02 open.

Separate direct connected Jira JQL confirmed RPL-88 Legacy Product/Component and Target Product/Component/Concern queries individually resolve; this proves field queryability, not consumer adoption or Origin enforcement.

## 3. Senior architecture recommendation — Native first, no ghost Reader

- **Native operations (recommended primary operating surface):** Admin-screen, Create, native Board/JQL/Filter/Automation, with legacy preserved. **No change** to inspected Board/Automation is needed since they do not read either classification field. Do not build a new Reader server to satisfy a consumer that has not been identified.
- **Per-Issue Origin authority (separate contract):** Accepted taxonomy §8.0/§8.1 still says existing `legacy_migration` Issue ReadAuthority remains `legacy` until issue-specific evidence, actual consumer/restore verification and separate `CutoverApprovalRef`. Native JQL does not enforce an external Manifest. Claiming Native Board PASS automatically means RPL-88 authority Cutover is an invalid inference.
- **Do not force redundant deployment or a synthetic "live" rollback:** when no classification consumer or mutable Board filter is in scope, no active consumer can be toggled and restored. A synthetic manifest switch cannot pass as an operational drill. Existence of a consumer needs evidence; absence across all private/external scopes cannot be inferred from this UI alone.
- **Reader MAJOR-01/02:** remain OPEN on dormant offline prototype and become a live/merge blocker **only if the prototype is selected as an official operational consumer**. Do not mark the findings fixed or delete their tests.

## 4. Explicit Owner choices / source of authority

**Decision D1 — Operating authority architecture, Owner approval required.**
- **Recommendation:** Adopt Jira Native as the supported **user-visible operating interface** for this single-project migration. Preserve Git as the history of issue-specific Origin/Cutover approvals for audit and possible future AI integrations. Do not assert Native enforces or consults Git.
- **Must decide additionally:** Is Origin-aware *user-visible filtering and aggregation* actually a product requirement for Jira Native users? If YES, identify the real Reader/Adapter consumer, authorise scoped implementation and test, or admit the capability is not yet supported. If NO, propose a **reviewed and Owner-approved canonical taxonomy addendum** that scopes the runtime Reader/restore integration AC to consumers actually in use and defines the visible limitations; do not silently rewrite effective §8.0/§8.1. Until the Owner decision is accepted and effective, G3 per-issue Cutover remains BLOCKED.

**Decision D2 — Jira Primary and Writer boundary.**
- RPL-88 is an existing **Carelog Architecture** Jira Task and only Pilot candidate, not automatically the umbrella Jira Migration Primary.
- Confirm existing migration Primary Jira if any; if none, explicitly approve how this Objective is tracked (existing relevant issue or new bounded Task) in Governance v1.3. Do not create or relabel RPL-88 as manager ticket.
- Confirm the designated single supervised Writer and actual authenticated Jira account. Assignee does not by itself prove Sole Writer. The existing private audit branch `audit/jira-g3-rpl88-pilot-20261030` remains audit evidence only.

**Decision D3 — private/third-party consumer scope.**
- Request other owners' private filter JQL only **if such filters are actually used for this RPL classification**. If they cannot be inspected, report `ACCESS_UNKNOWN` rather than assuming nonexistence.
- Use the Work UI evidence of shared Board Filter 10001 and active Automation as a bounded no-op impact assessment, not proof of all hidden/external consumers.

**Decision D4 — Legacy-less historical exceptions (independent of RPL-88 pilot).**
- RPL-83/94/115/116/117/118: confirmed historical Target backfill Changelogs `11063,11066-11070` but no approved G3 Origin promotion. Do not falsely map to `target_create` or `legacy_migration` without accepted exception.
- RPL-122/124/126/127: Target present, pre-Create approval/Intent not found. Remain Origin unverified. Do not retroactively fabricate Intent.
- RPL-128~132: verified original pending→Target-origin 5/5 PASS, CLOSED.

## 5. Practical execution acceptance, no artificial gates

- **READY and reusable:** No-change assessment of accessible Board/Automation/Filter; 5/5 Native manual Create; RPL-88 GET/JQL six-field evidence; immutable G2 snapshots.
- **NEXT technical gate:** Decide Native-only supported classifications vs requirement for manifest-aware runtime enforcement, identify the actual operational classification consumer (if any) and specify the exact supported, observable before/after behavior. For Native-only no classification consumer, propose a formally approved **NO_CLASSIFICATION_CONSUMER acceptance exception** with documented preservation and a non-destructive rollback plan, rather than fake a consumer.
- **Owner-only after actual prerequisites:** RPL-88 `CutoverApprovalRef` issued *separately* after acceptance, scope, change window, Writer and rollback; update one issue's authoritative manifest record, validate results. Do not bulk promote other 31 Legacy+Target issues.
- **G4 completion within known scope:** retain original Legacy fields/values and audit history; any future hide/remove/delete needs distinct approval. Unknown private consumers cannot be declared globally checked.

## 6. Do not do

No RPL-88 Cutover, no Legacy hiding/deletion, no unapproved Admin or Jira writes, no edits to Developer AI Platform PR #83/#84, no invented approval refs, no auto-Origin promotion, no source Merge/deploy, no generic Reader server.

## F4 checkpoint

- **DONE:** Native consumer inspection within accessible scope, 5/5 required Create and durable Origin proof, RPL-88 values/JQL/history preservation, no-op impact list.
- **WAITING_OWNER_DECISION:** Native-only authority/exception D1, Primary Jira+Writer D2, private consumer scope D3, 10 historical exception policy D4.
- **BLOCKED:** Existing issue target read authority and real consumer recovery drill, by explicit accepted policy. Do not mark "PASS" without actual required evidence.
- **NEXT SINGLE ACTION:** Owner decides D1 first; then the Jira Migration Work uses the chosen actual consumer to implement only needed work and request exact final Cutover permission.
