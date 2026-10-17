import copy
import importlib.util
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("pr_versions", ROOT / "scripts/check_pr_versions.py")
VERSIONS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERSIONS)
MANIFEST = "app/src/main/AndroidManifest.xml"
JAVA = "app/src/main/java/com/chekayo/feishuantirecall/AntiRecall.java"
PROP = "app/src/main/resources/META-INF/xposed/module.prop"
RELEASES = ["1031-1.8.9-api102", "32-1.8.10", "1032-1.8.10-api102"]


def fixture(modern=True):
    code, name = (1033, "1.8.11-api102") if modern else (33, "1.8.11")
    declarations = {
        MANIFEST: f'android:versionCode="{code}"\nandroid:versionName="{name}">',
        JAVA: f'static final int MODULE_VERSION_CODE = {code}; // same as manifest\n'
              f'static final String MODULE_VERSION = "{name}";',
        "version-api102.json" if modern else "version.json":
            f'  "versionCode": {code},\n  "versionName": "{name}",',
    }
    if modern:
        declarations[PROP] = f'version={name}\nversionCode={code}'
    return [{"filename": path, "patch": "\n".join("+" + line for line in text.splitlines())}
            for path, text in declarations.items()]


class VersionGateTest(unittest.TestCase):
    def check(self, files, modern=True, releases=None):
        return VERSIONS.check_versions(files, "libxposed-api-102-migration" if modern else "main",
                                       RELEASES if releases is None else releases)

    def test_modern_uses_suffix_and_four_locations(self):
        self.assertIn("1033，4 处一致", self.check(fixture()))

    def test_legacy_does_not_compare_to_modern_code(self):
        self.assertIn("33，3 处一致", self.check(fixture(False), False))

    def test_missing_update_json_cannot_be_replaced_by_documentation(self):
        files = fixture()
        files[2]["filename"] = "README.md"
        with self.assertRaisesRegex(ValueError, "version-api102.json"):
            self.check(files)

    def test_legacy_json_is_not_the_modern_update_source(self):
        files = fixture()
        files[2]["filename"] = "version.json"
        with self.assertRaises(ValueError):
            self.check(files)

    def test_missing_patch_fails_closed(self):
        files = fixture()
        del files[0]["patch"]
        with self.assertRaises(ValueError):
            self.check(files)

    def test_disagreement_at_each_location_is_rejected(self):
        for index in range(4):
            with self.subTest(index=index):
                files = fixture()
                files[index]["patch"] = files[index]["patch"].replace("1033", "1034")
                with self.assertRaisesRegex(ValueError, "不一致"):
                    self.check(files)

    def test_missing_or_wrong_channel_suffix_is_rejected(self):
        for modern, bad_suffix in ((True, ""), (True, "-other"), (False, "-api102")):
            files = fixture(modern)
            expected = "1.8.11-api102" if modern else "1.8.11"
            for file in files:
                file["patch"] = file["patch"].replace(expected, "1.8.11" + bad_suffix)
            with self.subTest(modern=modern, suffix=bad_suffix), self.assertRaises(ValueError):
                self.check(files, modern)

    def test_same_or_newer_prerelease_blocks_reused_code(self):
        for released in (1033, 1034):
            with self.subTest(released=released), self.assertRaisesRegex(ValueError, "必须大于"):
                self.check(fixture(), releases=RELEASES + [f"{released}-1.8.11-api102"])

    def test_release_order_is_irrelevant(self):
        self.assertEqual(self.check(fixture()), self.check(fixture(), releases=list(reversed(RELEASES))))

    def test_unknown_channel_or_missing_channel_release_fails_closed(self):
        with self.assertRaises(ValueError):
            VERSIONS.check_versions(fixture(), "unrecognized", RELEASES)
        for tags in ([], ["32-1.8.10"], ["1033-1.8.11-api102-preview"]):
            with self.subTest(tags=tags), self.assertRaises(ValueError):
                self.check(fixture(), releases=tags)

    def test_comments_do_not_count_as_production_declarations(self):
        files = fixture()
        files[1]["patch"] = files[1]["patch"].replace("+static", "+// static")
        with self.assertRaises(ValueError):
            self.check(files)

    def test_duplicate_declarations_are_rejected(self):
        files = fixture()
        files[1]["patch"] += "\n" + copy.copy(files[1]["patch"])
        with self.assertRaises(ValueError):
            self.check(files)


if __name__ == "__main__":
    unittest.main()
