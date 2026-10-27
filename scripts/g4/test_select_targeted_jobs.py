import unittest
from select_targeted_jobs import select

class SelectorTests(unittest.TestCase):
    def test_build_config(self):
        self.assertTrue(select(['gateway-app/build.gradle.kts'])['gateway'])
        self.assertTrue(select(['platform-core/identity/build.gradle.kts'])['identity'])
    def test_platform_core_app_main_source(self):
        self.assertEqual(
            select(["platform-core/app/src/main/java/PlatformCoreApplication.java"]),
            {"gateway": False, "identity": True, "carelog": False},
        )

    def test_platform_core_app_test_source(self):
        self.assertEqual(
            select(["platform-core/app/src/test/java/PlatformCoreApplicationTest.java"]),
            {"gateway": False, "identity": True, "carelog": False},
        )

    def test_docs(self):
        self.assertFalse(any(select(['docs/README.md']).values()))
    def test_self_change(self):
        self.assertTrue(all(select(['.github/workflows/audit-g4-targeted-feature.yml']).values()))

if __name__ == '__main__':
    unittest.main()
