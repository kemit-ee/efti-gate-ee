#!/usr/bin/env python3
"""Unit tests for scripts/validate-xml-surface.py (#307): a new parser, Turvis or xml-mapper site must fail."""
import importlib.util
import pathlib
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
spec = importlib.util.spec_from_file_location("validate_xml_surface", ROOT / "scripts" / "validate-xml-surface.py")
vxs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(vxs)


class Tree:
    def __init__(self, test):
        self.dir = tempfile.TemporaryDirectory()
        test.addCleanup(self.dir.cleanup)
        self.root = pathlib.Path(self.dir.name)
        self.allow = self.root / "allow.txt"
        self.allow.write_text("")

    def file(self, rel, text):
        path = self.root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)

    def allowlist(self, *lines):
        self.allow.write_text("\n".join(lines) + "\n")

    def problems(self):
        return vxs.check(self.root, self.allow)


class ValidateXmlSurface(unittest.TestCase):
    def test_a_clean_tree_with_an_empty_allowlist_passes(self):
        t = Tree(self)
        t.file("code/edelivery/src/A.kt", "class A { fun f() = 1 }\n")
        self.assertEqual([], t.problems())

    def test_the_real_tree_matches_the_real_allowlist(self):
        self.assertEqual([], vxs.check(ROOT, vxs.ALLOWLIST))

    def test_a_new_parser_site_fails_with_file_and_line(self):
        t = Tree(self)
        t.file("code/edelivery/src/A.kt", "package a\n\nval p = XmlParser()\n")
        (problem,) = t.problems()
        self.assertIn("code/edelivery/src/A.kt:3", problem)
        self.assertIn("xml-parser", problem)

    def test_every_parser_api_is_found_and_imports_are_not(self):
        for api in ["DocumentBuilderFactory.newInstance()", "SAXParserFactory.newInstance()", "XMLInputFactory.newFactory()", "TransformerFactory.newInstance()",
                    "SchemaFactory.newInstance(x)", "XMLSignatureFactory.getInstance(\"DOM\")", "XPathFactory.newInstance()", "XmlParser(keys = k)"]:
            with self.subTest(api):
                t = Tree(self)
                t.file("code/x/src/A.kt", f"import javax.xml.parsers.DocumentBuilderFactory\nval p = {api}\n")
                (problem,) = t.problems()
                self.assertIn("A.kt:2", problem)

    def test_tests_are_not_scanned(self):
        t = Tree(self)
        t.file("code/edelivery/test/edelivery/A.kt", "val p = XmlParser()\n")
        self.assertEqual([], t.problems())

    def test_an_allowlisted_site_passes_and_one_more_hit_in_it_fails(self):
        t = Tree(self)
        t.file("code/edelivery/src/A.kt", "val p = XmlParser()\n")
        t.allowlist("xml-parser | code/edelivery/src/A.kt | 1 | reviewed")
        self.assertEqual([], t.problems())
        t.file("code/edelivery/src/A.kt", "val p = XmlParser()\nval q = XmlParser()\n")
        (problem,) = t.problems()
        self.assertIn("changed from 1 to 2", problem)

    def test_a_stale_entry_fails(self):
        t = Tree(self)
        t.allowlist("xml-parser | code/edelivery/src/Gone.kt | 1 | reviewed")
        (problem,) = t.problems()
        self.assertIn("stale entry", problem)

    def test_the_same_file_under_another_kind_is_not_covered(self):
        t = Tree(self)
        t.file("code/edelivery/src/A.kt", "val c = TurvisClient()\nval r = c.verify(x)\n")
        t.allowlist("xml-parser | code/edelivery/src/A.kt | 1 | wrong kind")
        self.assertEqual(2, len(t.problems()))

    def test_a_new_turvis_call_in_a_client_user_fails(self):
        t = Tree(self)
        t.file("code/edelivery/src/B.kt", "class B(val client: TurvisClient) { fun f() = client.decrypt(a, b) }\n")
        (problem,) = t.problems()
        self.assertIn("turvis-call", problem)
        self.assertIn("B.kt:1", problem)

    def test_a_turvis_url_literal_in_code_fails(self):
        t = Tree(self)
        t.file("code/edelivery/src/C.kt", 'val u = "/turvis/v1/verifications/xmldsig"\n')
        (problem,) = t.problems()
        self.assertIn("turvis-call", problem)

    def test_dsl_calling_turvis_fails_but_the_mock_itself_does_not(self):
        t = Tree(self)
        t.file("DSL/Ruuter-turvis-mock/turvis/POST/v1/x.yml", "url: /turvis/v1/x\n")
        self.assertEqual([], t.problems())
        t.file("DSL/Ruuter/efti/POST/api/v1/x.yml", 'url: "[#TURVIS_URL]/turvis/v1/verifications/xmldsig"\n')
        (problem,) = t.problems()
        self.assertIn("turvis-dsl", problem)

    def test_a_new_dsl_xml_mapper_caller_fails(self):
        t = Tree(self)
        t.file("DSL/Ruuter/efti/POST/api/v1/new.yml", 'url: "[#XML_MAPPER_URL]/api/v1/search/request-to-json"\n')
        (problem,) = t.problems()
        self.assertIn("xml-mapper-call", problem)

    def test_a_malformed_allowlist_line_is_refused(self):
        t = Tree(self)
        t.allowlist("xml-parser | code/a.kt | many | reason")
        with self.assertRaises(SystemExit):
            t.problems()
        t.allowlist("xml-parser | code/a.kt | 1 |")
        with self.assertRaises(SystemExit):
            t.problems()


if __name__ == "__main__":
    unittest.main()
