#!/usr/bin/env python3
"""Validate the exact rich XML embedded in the public mock route."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import unittest
import urllib.request
import xml.etree.ElementTree as ET
import yaml

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("fixture", ROOT / "scripts/generate-rich-mock-dataset.py")
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)


class RichDataset(unittest.TestCase):
    def setUp(self):
        self.route = yaml.safe_load((ROOT / "DSL/Ruuter-xroad-mock/developer/POST/v1/dataset.yml").read_text())
        self.xml = self.route["respond_rich"]["return"]["xml"]
        self.root = ET.fromstring(self.xml)

    def test_fixture_matches_all_field_generator(self):
        self.assertEqual(self.xml, fixture.generate())
        self.assertGreater(len(list(self.root.iter())), 500)
        for name in ("ConsignorTradeParty", "ConsigneeTradeParty", "CarrierTradeParty",
                     "IncludedSupplyChainConsignmentItem", "ApplicableTransportDangerousGoods",
                     "UtilizedLogisticsTransportEquipment", "AssociatedReferencedDocument"):
            self.assertIsNotNone(self.root.find(".//{" + fixture.PREFIXES["ram"] + "}" + name))

    def test_embedded_xml_validates_against_fti010(self):
        self.validate_xml(self.xml)

    def validate_xml(self, xml):
        envelope = ET.parse(ROOT / "code/xml-mapper/xsd/FTI010/sample.xml").getroot()
        envelope.remove(envelope.find("{" + fixture.PREFIXES["rsm"] + "}SpecifiedSupplyChainConsignment"))
        envelope.append(ET.fromstring(xml))
        result = subprocess.run(
            ["xmllint", "--noout", "--schema", str(ROOT / "code/xml-mapper/xsd/FTI010/FTI010s.xsd"), "-"],
            input=ET.tostring(envelope), capture_output=True,
        )
        self.assertEqual(result.returncode, 0, result.stderr.decode())

    def test_three_shipments_validate_and_match_pickup_scenario(self):
        namespace = "{" + fixture.PREFIXES["ram"] + "}"
        for number, (dataset, city, street, hour, weight, goods, dangerous) in enumerate(fixture.SCENARIOS, 1):
            with self.subTest(dataset=dataset):
                xml = self.route[f"respond_shipment_{number}"]["return"]["xml"]
                self.assertEqual(xml, fixture.generate_scenario(number))
                self.validate_xml(xml)
                root = ET.fromstring(xml)
                movement = root.find(namespace + "MainCarriageLogisticsTransportMovement")
                self.assertEqual(movement.findtext(namespace + "UsedLogisticsTransportMeans/" + namespace + "ID"), "MOCK-PLATE-3")
                self.assertEqual(movement.findtext(namespace + "LoadingTransportEvent/" + namespace + "OccurrenceLogisticsLocation/" + namespace + "PostalTradeAddress/" + namespace + "CityName"), city)
                self.assertEqual(movement.findtext(namespace + "UnloadingTransportEvent/" + namespace + "OccurrenceLogisticsLocation/" + namespace + "PostalTradeAddress/" + namespace + "CityName"), "Narva")
                hazard = root.find(".//" + namespace + "ApplicableTransportDangerousGoods")
                self.assertEqual(hazard is not None, dangerous)
                if dangerous:
                    self.assertEqual(hazard.findtext(namespace + "UNDGIdentificationCode"), "1203")
                    self.assertEqual(hazard.findtext(namespace + "HazardClassificationID"), "3")

    def test_search_and_transport_projection_share_three_unique_uils(self):
        base = ROOT / "DSL/Ruuter-xroad-mock/developer/POST/v1"
        search = yaml.safe_load((base / "search.yml").read_text())["respond_multi"]["return"]
        transport = yaml.safe_load((base / "transport-means.yml").read_text())["respond_local_multi"]["return"]
        self.assertEqual(transport["found"], 3)
        self.assertEqual(search, transport["consignments"])
        self.assertEqual([row["uil"]["datasetId"] for row in search], [row[0] for row in fixture.SCENARIOS])
        self.assertEqual(sum(row["dangerousGoods"] is not None for row in search), 1)
        self.assertEqual({row["mainTransportId"] for row in search}, {"MOCK-PLATE-3"})

    @unittest.skipUnless(os.environ.get("MOCK_TEST_URL"), "optional live mock URL not provided")
    def test_live_lookup_and_all_three_dataset_details(self):
        base = os.environ["MOCK_TEST_URL"].rstrip("/")

        def request(path, body):
            headers = {"Content-Type": "application/json", "X-Road-Client": "EE/GOV/70000097/test",
                       "X-Road-Id": "550e8400-e29b-41d4-a716-446655440099"}
            req = urllib.request.Request(base + "/developer/v1/" + path, data=json.dumps(body).encode(), headers=headers)
            with urllib.request.urlopen(req, timeout=10) as response:
                return json.load(response)

        lookup = request("transport-means", {"identifier": "MOCK-PLATE-3", "countryCode": "EE", "scope": "local"})
        self.assertEqual(lookup["found"], 3)
        self.assertEqual(request("search", {"mainTransportId": {"id": "MOCK-PLATE-3", "operation": "EQ"}}), lookup["consignments"])
        for number, row in enumerate(lookup["consignments"], 1):
            body = request("dataset", {"uil": row["uil"], "subsets": ["EU01", "EU02", "EU03", "EU05"]})
            self.assertEqual(body["xml"], fixture.generate_scenario(number))
        rich = request("dataset", {"uil": {"gateId": "EU-EE", "platformId": "mock", "datasetId": "550e8400-e29b-41d4-a716-446655440002"}, "subsets": ["EU01", "EU02", "EU03", "EU05"]})
        self.assertEqual(rich["xml"], fixture.generate())
        simple = request("dataset", {"uil": {"gateId": "EU-EE", "platformId": "mock", "datasetId": "550e8400-e29b-41d4-a716-446655440001"}, "subsets": ["EU01"]})
        self.assertEqual(simple["xml"], self.route["respond"]["return"]["xml"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
