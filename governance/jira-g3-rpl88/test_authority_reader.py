import copy, json, unittest
from pathlib import Path
from authority_reader import resolve, Denied, simulate_rollback

ROOT=Path(__file__).parent
M=json.loads((ROOT/'precutover_manifest.json').read_text())
J=json.loads((ROOT/'observed_jira_fixture.json').read_text())

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
        j=copy.deepcopy(J);j['key']='RPL-128';self.denial('ISSUE_KEY_MISMATCH',j=j)
    def test_wrong_type(self):
        j=copy.deepcopy(J);j['fields']['issuetype']['id']='10009';self.denial('ISSUE_TYPE_MISMATCH',j=j)
    def test_target_drift(self):
        j=copy.deepcopy(J);j['fields']['customfield_10108']['id']='10022';self.denial('TARGET_VALUE_DRIFT',j=j)
    def test_legacy_drift(self):
        j=copy.deepcopy(J);j['fields']['customfield_10042']=['finance'];self.denial('LEGACY_VALUE_DRIFT',j=j)
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