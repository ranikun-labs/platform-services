"""Jira G3 legacy-migration audit reader (private, fail-closed).

A portable verifier for the RPL-88 pilot, NOT a deployed consumer.
It reads Jira issue JSON supplied by the caller; no credentials, networking or Jira mutations.
The *effective* runtime/Board integration is a separate acceptance gate.
"""
import argparse
import hashlib
import json
from pathlib import Path

LEGACY_FIELDS = ('customfield_10042', 'customfield_10043', 'customfield_10044')
TARGET_FIELDS = ('customfield_10108', 'customfield_10109', 'customfield_10110')


class Denied(Exception):
    def __init__(self, reason):
        self.reason = reason
        super().__init__(reason)


def jira_fields(payload):
    if isinstance(payload.get('issues'), dict):
        nodes = payload['issues'].get('nodes', [])
        if len(nodes) != 1:
            raise Denied('JIRA_RESPONSE_AMBIGUOUS')
        return nodes[0]
    return payload


def _ids(value):
    if value is None:
        return []
    if isinstance(value, list):
        return sorted(str(x['id']) for x in value)
    if not isinstance(value, dict) or 'id' not in value:
        raise Denied('INVALID_TARGET_OPTION')
    return [str(value['id'])]


def resolve(manifest, raw_issue):
    issue = jira_fields(raw_issue)
    key = manifest.get('IssueKey')
    if not key or issue.get('key') != key:
        raise Denied('ISSUE_KEY_MISMATCH')
    fields = issue.get('fields', {})
    if str(fields.get('issuetype', {}).get('id')) != str(manifest.get('IssueTypeId')):
        raise Denied('ISSUE_TYPE_MISMATCH')
    if manifest.get('Origin') != 'legacy_migration':
        raise Denied('UNKNOWN_OR_UNSUPPORTED_ORIGIN')
    phase = manifest.get('Phase')
    if phase == 'verified' and manifest.get('ReadAuthority') == 'legacy' and manifest.get('WriteAuthority') == 'frozen':
        authority = 'legacy'
    elif phase == 'cutover' and manifest.get('ReadAuthority') == 'target' and manifest.get('WriteAuthority') == 'target':
        if not manifest.get('CutoverApprovalRef'):
            raise Denied('MISSING_CUTOVER_APPROVAL')
        if not manifest.get('ConsumerIntegrationRef'):
            raise Denied('MISSING_CONSUMER_INTEGRATION')
        if not manifest.get('RestoreDrillRef'):
            raise Denied('MISSING_RESTORE_DRILL')
        authority = 'target'
    else:
        raise Denied('INVALID_LEGACY_PHASE_OR_AUTHORITY')
    expected = manifest.get('TargetOptionIDs', {})
    actual = [_ids(fields.get(f)) for f in TARGET_FIELDS]
    want = [[str(expected.get('product'))], [str(expected.get('component'))], sorted(map(str, expected.get('concern', [])))]
    if actual != want:
        raise Denied('TARGET_VALUE_DRIFT')
    legacy = {key: list(fields.get(key) or []) for key in LEGACY_FIELDS}
    canonical = {'product': legacy[LEGACY_FIELDS[0]], 'component': legacy[LEGACY_FIELDS[1]], 'labels': legacy[LEGACY_FIELDS[2]]}
    legacy_sha = hashlib.sha256(json.dumps(canonical, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode('utf8')).hexdigest()
    if manifest.get('ExpectedLegacyHash') != legacy_sha:
        raise Denied('LEGACY_VALUE_DRIFT')
    if str(manifest.get('JiraChangelogId')) not in [str(c.get('id')) for c in issue.get('changelog', {}).get('histories', [])]:
        raise Denied('HISTORICAL_CHANGELOG_MISSING')
    return {'IssueKey': key, 'Origin': manifest['Origin'], 'ReadAuthority': authority, 'classification': {"product": expected['product'], "component": expected['component'], "concern": expected['concern']}, 'verified': True}


def simulate_rollback(manifest, issue):
    """Offline drill only: must not change Jira or a live manifest."""
    import copy
    candidate = copy.deepcopy(manifest)
    candidate.update({'Phase': 'cutover', 'ReadAuthority': 'target', 'WriteAuthority': 'target',
                      'CutoverApprovalRef': 'SYNTHETIC_DRILL_ONLY', 'ConsumerIntegrationRef': 'SYNTHETIC_DRILL_ONLY', 'RestoreDrillRef': 'SYNTHETIC_DRILL_ONLY'})
    target = resolve(candidate, issue)
    candidate.update({'Phase': 'verified', 'ReadAuthority': 'legacy', 'WriteAuthority': 'frozen',
                      'CutoverApprovalRef': None, 'ConsumerIntegrationRef': None, 'RestoreDrillRef': None})
    rollback = resolve(candidate, issue)
    assert target['ReadAuthority'] == 'target' and rollback['ReadAuthority'] == 'legacy'
    return {'simulation': 'PASS', 'production_rollback_verified': False, 'JiraMutations': 0}


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--manifest', required=True)
    p.add_argument('--issue-json', required=True)
    p.add_argument('--rollback-simulation', action='store_true')
    args = p.parse_args()
    m = json.loads(Path(args.manifest).read_text(encoding='utf8'))
    j = json.loads(Path(args.issue_json).read_text(encoding='utf8'))
    try:
        result = simulate_rollback(m, j) if args.rollback_simulation else resolve(m, j)
        print(json.dumps(result, ensure_ascii=False))
    except Denied as exc:
        print(json.dumps({'status': 'DENY', 'reason': exc.reason}))
        raise SystemExit(2)


if __name__ == '__main__':
    main()