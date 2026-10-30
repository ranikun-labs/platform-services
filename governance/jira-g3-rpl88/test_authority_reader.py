import copy, json, unittest
from pathlib import Path
from authority_reader import resolve, Denied, simulate_rollback

# Isolated synthetic fixtures: real private G2 manifests are not committed to GitHub.
_base_legacy={"product":["demo"],"component":["documentation"],"labels":["demo"]}
import hashlib
M={"IssueKey":"TEST-1","IssueTypeId":"10010","Origin":"legacy_migration","Phase":"verified","ReadAuthority":"legacy","WriteAuthority":"frozen","CutoverApprovalRef":None,"TargetOptionIDs":{"product":"20001","component":"20002","concern":["20003"]},"JiraChangelogId":"CHANGE-1","ExpectedLegacyHash":hashlib.sha256(json.dumps(_base_legacy,sort_keys=True,ensure_ascii=False,separators=(",",":")).encode()).hexdigest()}
J={"key":"TEST-1","fields":{"issuetype":{"id":"10010"},"customfield_10042":["demo"],"customfield_10043":["documentation"],"customfield_10044":["demo"],"customfield_10108":{"id":"20001"},"customfield_10109":{"id":"20002"},"customfield_10110":[{"id":"20003"}]},"changelog":{"histories":[{"id":"CHANGE-1"}]}}

class ReaderTest(unittest.TestCase):
    def denial(self, error, m=None, j=None):
        with self.assertRaises(Denied) as ctx:
            resolve(m or M, j or J)
        self.assertEqual(ctx.exception.reason, error)
    def test_valid_pre_cutover_resolves_to_legacy(self):
        self.assertEqual(resolve(M,J)['ReadAuthority'],'legacy')
    def test_unknown_origin(self):
        m=copy.deepcopy(M);m['Origin']='unknown';self.denial('UNKNOWN_OR_UNSUPPORTED_ORIGIN',m)
    def test_wrong_key(self):
        j=copy.deepcopy(J);j['key']='TEST-2';self.denial('ISSUE_KEY_MISMATCH',j=j)
    def test_wrong_type(self):
        j=copy.deepcopy(J);j['fields']['issuetype']['id']='10009';self.denial('ISSUE_TYPE_MISMATCH',j=j)
    def test_target_drift(self):
        j=copy.deepcopy(J);j['fields']['customfield_10108']['id']='90001';self.denial('TARGET_VALUE_DRIFT',j=j)
    def test_legacy_drift(self):
        j=copy.deepcopy(J);j['fields']['customfield_10042']=['altered'];self.denial('LEGACY_VALUE_DRIFT',j=j)
    def test_changelog_missing(self):
        j=copy.deepcopy(J);j['changelog']['histories']=[];self.denial('HISTORICAL_CHANGELOG_MISSING',j=j)
    def test_phase_without_authority(self):
        m=copy.deepcopy(M);m['Phase']='cutover';self.denial('INVALID_LEGACY_PHASE_OR_AUTHORITY',m=m)
    def test_missing_owner_approval(self):
        m=copy.deepcopy(M);m.update({'Phase':'cutover','ReadAuthority':'target','WriteAuthority':'target'});self.denial('MISSING_CUTOVER_APPROVAL',m=m)
    def test_missing_consumer(self):
        m=copy.deepcopy(M);m.update({'Phase':'cutover','ReadAuthority':'target','WriteAuthority':'target','CutoverApprovalRef':'SYNTHETIC_ONLY'});self.denial('MISSING_CONSUMER_INTEGRATION',m=m)
    def test_missing_restore(self):
        m=copy.deepcopy(M);m.update({'Phase':'cutover','ReadAuthority':'target','WriteAuthority':'target','CutoverApprovalRef':'SYNTHETIC_ONLY','ConsumerIntegrationRef':'SYNTHETIC_ONLY'});self.denial('MISSING_RESTORE_DRILL',m=m)
    def test_rollback_simulation(self):
        self.assertEqual(simulate_rollback(M,J)['simulation'],'PASS')

if __name__=='__main__': unittest.main(verbosity=2)