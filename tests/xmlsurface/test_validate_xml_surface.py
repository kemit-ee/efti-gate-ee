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

    def allow_current(self):
        """Allowlist exactly what the tree has now."""
        self.allowlist(*[f"{k} | {p} | {len(f)} | {vxs.fingerprint([t for _, t in f])} | reviewed" for (k, p), f in sorted(vxs.scan(self.root).items())])

    def problems(self):
        return vxs.check(self.root, self.allow)


class ValidateXmlSurface(unittest.TestCase):
    def test_a_clean_tree_with_an_empty_allowlist_passes(self):
        t = Tree(self)
        t.file("code/edelivery/src/A.kt", "class A { fun f() = 1 }\n")
        self.assertEqual([], t.problems())

    def test_the_real_tree_matches_the_real_allowlist(self):
        self.assertEqual([], vxs.check(ROOT, vxs.ALLOWLIST))

    def test_a_new_parser_site_fails_with_file_and_line_and_the_entry_to_add(self):
        t = Tree(self)
        t.file("code/edelivery/src/A.kt", "package a\n\nval p = XmlParser()\n")
        (problem,) = t.problems()
        self.assertIn("code/edelivery/src/A.kt:3", problem)
        self.assertIn("new xml-parser site", problem)
        self.assertIn("xml-parser | code/edelivery/src/A.kt | 1 |", problem)

    def test_every_parser_api_is_found_and_imports_are_found_too(self):
        for api in ["DocumentBuilderFactory.newInstance()", "x: DocumentBuilder = y", "SAXParserFactory.newInstance()", "x: SAXParser? = null",
                    "XMLInputFactory.newFactory()", "XMLOutputFactory.newFactory()", "x: XMLReader? = null", "x: XMLStreamReader? = null",
                    "x: XMLEventReader? = null", "TransformerFactory.newInstance()", "SchemaFactory.newInstance(x)", "SchemaValidator()",
                    "x: Unmarshaller? = null", "JAXBContext.newInstance(A::class.java)", "XMLSignatureFactory.getInstance(\"DOM\")",
                    "XPathFactory.newInstance()", "XmlParser(keys = k)", "XmlMapper()", "secureDocumentBuilderFactory().parse(x)",
                    "val r = Regex(\"<eb:From>(.*)</eb:From>\")"]:
            with self.subTest(api):
                t = Tree(self)
                t.file("code/x/src/A.kt", f"val p = {api}\n")
                self.assertEqual(1, len(t.problems()))

    def test_hidden_parser_forms_are_found(self):
        for name, path, src in [
            ("di", "code/x/src/A.kt", "class A(private val p: XmlParser) { fun f(x: String) = p.parse<Any>(x) }\n"),
            ("alias import", "code/x/src/A.kt", "import klite.xml.XmlParser as XP\nval p = XP()\n"),
            ("java", "code/x/src/A.java", "class A { Object f() { return javax.xml.parsers.DocumentBuilderFactory.newInstance(); } }\n"),
            ("kotlin script", "code/x/build.gradle.kts", "val f = javax.xml.parsers.DocumentBuilderFactory.newInstance()\n"),
            ("ui dom parser", "code/ui/src/a.ts", "const d = new DOMParser().parseFromString(x, 'text/xml')\n"),
            ("untyped turvis call", "code/x/src/A.kt", "fun f(o: O) = o.client.sign(a, b)\n"),
            ("hard-coded turvis url", "code/x/src/A.kt", 'val u = "$base/turvis/v1/verifications/xmldsig"\n'),
        ]:
            with self.subTest(name):
                t = Tree(self)
                t.file(path, src)
                self.assertEqual(1, len(t.problems()))

    def test_tests_and_build_output_are_not_scanned(self):
        t = Tree(self)
        t.file("code/edelivery/test/edelivery/A.kt", "val p = XmlParser()\n")
        t.file("code/ui/src/a.test.ts", "new DOMParser()\n")
        t.file("code/ui/node_modules/x/src/b.ts", "new DOMParser()\n")
        t.file("code/edelivery/build/src/c.kt", "val p = XmlParser()\n")
        self.assertEqual([], t.problems())

    def test_a_documentation_path_with_turvis_in_it_is_not_a_call(self):
        t = Tree(self)
        t.file("code/edelivery/src/A.kt", "// contract: docs/specs/turvis/SPEC.md section 4\nclass A\n")
        self.assertEqual([], t.problems())

    def test_an_allowlisted_site_passes(self):
        t = Tree(self)
        t.file("code/edelivery/src/A.kt", "val p = XmlParser()\n")
        t.allow_current()
        self.assertEqual([], t.problems())

    def test_a_moved_line_passes_but_an_extra_hit_fails(self):
        t = Tree(self)
        t.file("code/edelivery/src/A.kt", "val p = XmlParser()\n")
        t.allow_current()
        t.file("code/edelivery/src/A.kt", "\n\n// moved\n   val p = XmlParser()   \n")
        self.assertEqual([], t.problems())
        t.file("code/edelivery/src/A.kt", "val p = XmlParser()\nval q = XmlParser()\n")
        (problem,) = t.problems()
        self.assertIn("hits changed", problem)

    def test_one_call_added_and_another_removed_in_the_same_file_fails(self):
        t = Tree(self)
        t.file("code/edelivery/src/A.kt", "fun f() = client.decrypt(a)\nfun g() = client.verify(b)\n")
        t.allow_current()
        t.file("code/edelivery/src/A.kt", "fun f() = client.decrypt(a)\nfun g() = client.sign(b)\n")
        (problem,) = t.problems()
        self.assertIn("hits changed", problem)

    def test_a_stale_entry_fails(self):
        t = Tree(self)
        t.allowlist("xml-parser | code/edelivery/src/Gone.kt | 1 | 0123456789 | reviewed")
        (problem,) = t.problems()
        self.assertIn("stale entry", problem)

    def test_the_same_file_under_another_kind_is_not_covered(self):
        t = Tree(self)
        t.file("code/edelivery/src/A.kt", "val c = TurvisClient()\nval r = c.verify(x)\n")
        t.allowlist("xml-parser | code/edelivery/src/A.kt | 1 | 0123456789 | wrong kind")
        self.assertEqual(2, len(t.problems()))

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

    def test_a_new_dsl_xml_mapper_caller_fails_in_any_spelling(self):
        for name, text in [("constant", 'url: "[#XML_MAPPER_URL]/api/v1/search/request-to-json"\n'),
                           ("host", 'url: "http://xml-mapper:8082/api/v1/search/request-to-json"\n'),
                           ("port", 'url: "http://10.0.0.5:8082/api/v1/x"\n')]:
            for ext in ("yml", "yaml"):
                with self.subTest(f"{name} {ext}"):
                    t = Tree(self)
                    t.file(f"DSL/Ruuter/efti/POST/api/v1/new.{ext}", text)
                    (problem,) = t.problems()
                    self.assertIn("xml-mapper-call", problem)

    def test_a_malformed_allowlist_line_is_refused(self):
        t = Tree(self)
        for bad in ["xml-parser | code/a.kt | many | 0123456789 | reason", "xml-parser | code/a.kt | 1 | 0123456789 |",
                    "xml-parser | code/a.kt | 1 | nothex | reason", "xml-parser | code/a.kt | 1 | reason"]:
            with self.subTest(bad):
                t.allowlist(bad)
                with self.assertRaises(SystemExit):
                    t.problems()

    def test_non_utf8_and_crlf_files_do_not_crash_or_hide_a_hit(self):
        t = Tree(self)
        (t.root / "code/x/src").mkdir(parents=True)
        (t.root / "code/x/src/A.kt").write_bytes(b"val s = \"\xe4\xf6\"\r\nval p = XmlParser()\r\n")
        (problem,) = t.problems()
        self.assertIn("A.kt:2", problem)


if __name__ == "__main__":
    unittest.main()
