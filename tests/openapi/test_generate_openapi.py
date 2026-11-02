#!/usr/bin/env python3
"""Unit tests for the cleaning step of scripts/generate-openapi.py (the Ruuter and docker parts need the runtime image)."""
import importlib.util
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
spec = importlib.util.spec_from_file_location("generate_openapi", ROOT / "scripts" / "generate-openapi.py")
g = importlib.util.module_from_spec(spec)
spec.loader.exec_module(g)


class Clean(unittest.TestCase):
    def doc(self):
        return {
            "openapi": "3.1.0", "info": {"title": "Ruuter-on-Rust DSL API", "version": "0.12.1-rc"}, "servers": [{"url": "/"}],
            "paths": {
                "/a": {"get": {"parameters": [{"name": "subsets", "in": "query"}, {"name": "pathParams", "in": "query"}]}},
                "/b": {"get": {"parameters": [{"name": "pathParams", "in": "query"}]}, "post": {"responses": {}}},
            },
        }

    def test_info_is_ours_and_does_not_carry_the_engine_version(self):
        info = g.clean(self.doc())["info"]
        self.assertEqual("generated", info["version"])
        self.assertNotIn("Ruuter-on-Rust", info["title"])

    def test_the_path_parameter_map_is_not_a_query_parameter(self):
        paths = g.clean(self.doc())["paths"]
        self.assertEqual(["subsets"], [p["name"] for p in paths["/a"]["get"]["parameters"]])
        self.assertNotIn("parameters", paths["/b"]["get"])

    def test_servers_are_dropped_and_other_operations_survive(self):
        doc = g.clean(self.doc())
        self.assertNotIn("servers", doc)
        self.assertIn("post", doc["paths"]["/b"])


if __name__ == "__main__":
    unittest.main()
