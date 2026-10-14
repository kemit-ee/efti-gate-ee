#!/usr/bin/env python3
"""Validate the canned datasets and keep the developer mock's dataset contract equal to the live gate's."""
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import unittest
import urllib.request
import xml.etree.ElementTree as ET
import yaml

ROOT = Path(__file__).resolve().parents[2]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


fixture = load("fixture", "scripts/generate-rich-mock-dataset.py")
sync = load("sync", "scripts/sync-mock-datasets.py")
RAM = "{" + fixture.PREFIXES["ram"] + "}"
MOCK_BASE = ROOT / "DSL/Ruuter-xroad-mock/developer/POST/v1"


def step(dataset_id):
    return "respond_" + dataset_id[-4:]


def path(root, *names):
    return root.findtext("/".join(RAM + name for name in names))


class RichDataset(unittest.TestCase):
    def setUp(self):
        self.route = yaml.safe_load((MOCK_BASE / "dataset.yml").read_text())
        self.platform = yaml.safe_load((ROOT / "DSL/Ruuter/mock-platform/GET/v1/dataset.yml").read_text())
        self.xml = self.platform["respond_0002"]["return"]
        self.root = ET.fromstring(self.xml)

    def test_routes_are_generated_from_the_fixture(self):
        self.assertEqual((MOCK_BASE / "dataset.yml").read_text(), sync.mock_route())
        self.assertEqual((ROOT / "DSL/Ruuter/mock-platform/GET/v1/dataset.yml").read_text(), sync.platform_route())

    def test_mock_and_live_platform_serve_the_same_xml(self):
        for dataset_id, make in fixture.DATASETS.items():
            with self.subTest(dataset=dataset_id):
                self.assertEqual(self.route[step(dataset_id)]["return"]["xml"], fixture.served_xml(make()))
                self.assertEqual(self.platform[step(dataset_id)]["return"], make())
        self.assertEqual(self.route["respond"]["return"]["xml"], fixture.served_xml(fixture.SIMPLE))
        self.assertEqual(self.platform["respond"]["return"], fixture.SIMPLE)

    def test_consignment_is_the_mapper_projection_of_the_xml(self):
        for name in [step(dataset_id) for dataset_id in fixture.DATASETS] + ["respond"]:
            with self.subTest(step=name):
                body = self.route[name]["return"]
                self.assertEqual(list(body), ["uil", "subsets", "xml", "consignment"])
                platform_xml = self.platform[name]["return"]
                self.assertEqual(body["xml"], fixture.served_xml(platform_xml))
                self.assertEqual(body["consignment"], fixture.consignment(platform_xml))

    def test_mapper_naming_rules(self):
        self.assertEqual([fixture.json_name(n) for n in ("ID", "CountryID", "WidthMeasure", "Identification",
                                                         "UNDGIdentificationCode", "CODAmount", "schemeAgencyID")],
                         ["Id", "countryId", "wIdthMeasure", "Identification", "uNDGIdentificationCode",
                          "cODAmount", "schemeAgencyId"])
        projected = fixture.consignment(fixture.generate_scenario(1))
        self.assertEqual(projected["utilizedLogisticsTransportEquipment"]["Id"], fixture.TRAILER)
        self.assertEqual(projected["grossWeightMeasure@unitCode"], "KGM")

    def test_fixture_matches_all_field_generator(self):
        self.assertEqual(self.xml, fixture.generate())
        self.assertGreater(len(list(self.root.iter())), 500)
        for name in ("ConsignorTradeParty", "ConsigneeTradeParty", "CarrierTradeParty",
                     "IncludedSupplyChainConsignmentItem", "ApplicableTransportDangerousGoods",
                     "UtilizedLogisticsTransportEquipment", "AssociatedReferencedDocument"):
            self.assertIsNotNone(self.root.find(".//" + RAM + name))

    def test_embedded_xml_validates_against_fti010(self):
        for xml in [make() for make in fixture.DATASETS.values()]:
            self.validate_xml(xml)

    def validate_xml(self, xml):
        envelope = ET.parse(ROOT / "code/xml-mapper/xsd/FTI010/sample.xml").getroot()
        envelope.remove(envelope.find("{" + fixture.PREFIXES["rsm"] + "}SpecifiedSupplyChainConsignment"))
        envelope.append(ET.fromstring(xml))
        result = subprocess.run(
            ["xmllint", "--noout", "--schema", str(ROOT / "code/xml-mapper/xsd/FTI010/FTI010s.xsd"), "-"],
            input=ET.tostring(envelope), capture_output=True,
        )
        self.assertEqual(result.returncode, 0, result.stderr.decode())

    def test_three_shipments_carry_every_form_field(self):
        for number, scenario in enumerate(fixture.SCENARIOS, 1):
            with self.subTest(dataset=scenario["dataset"]):
                root = ET.fromstring(fixture.generate_scenario(number))
                movement = "MainCarriageLogisticsTransportMovement"
                self.assertEqual(path(root, movement, "UsedLogisticsTransportMeans", "ID"), fixture.PLATE)
                self.assertEqual(path(root, "UtilizedLogisticsTransportEquipment", "ID"), fixture.TRAILER)
                self.assertEqual(path(root, "UtilizedLogisticsTransportEquipment", "CategoryCode"), "TE")
                self.assertEqual(path(root, "UtilizedLogisticsTransportEquipment", "CarriedAssociatedTransportEquipment", "ID"), fixture.CONTAINER)
                self.assertEqual(path(root, "UtilizedLogisticsTransportEquipment", "CarriedAssociatedTransportEquipment", "CategoryCode"), "CN")
                self.assertEqual(path(root, "TransportContractReferencedDocument", "TypeCode"), "730")
                self.assertEqual(path(root, "TransportContractReferencedDocument", "ID"), scenario["cmr"])
                self.assertEqual(path(root, "NatureIdentificationTransportCargo", "Identification"), scenario["goods"])
                self.assertEqual(path(root, "GrossVolumeMeasure"), scenario["volume"])
                self.assertEqual(path(root, "Information"), scenario["remark"])
                self.assertEqual(path(root, "CarrierTradeParty", "PostalTradeAddress", "CountryID"), "EE")
                self.assertEqual(path(root, movement, "LoadingTransportEvent", "OccurrenceLogisticsLocation", "PostalTradeAddress", "CityName"), scenario["city"])
                self.assertEqual(path(root, movement, "UnloadingTransportEvent", "OccurrenceLogisticsLocation", "PostalTradeAddress", "CityName"), "Narva")
                loading = root.find(".//" + RAM + "LoadingTransportEvent//{" + fixture.PREFIXES["udt"] + "}DateTimeString")
                self.assertEqual((loading.get("format"), loading.text), ("205", "20260914" + scenario["hour"] + "00+0000"))
                hazard = root.find(".//" + RAM + "ApplicableTransportDangerousGoods")
                self.assertEqual(hazard is not None, scenario["dangerous"] is not None)
                if hazard is not None:
                    self.assertEqual(path(hazard, "UNDGIdentificationCode"), "1203")
                    self.assertEqual(path(hazard, "HazardClassificationID"), "3")
                    self.assertEqual(path(hazard, "PackagingDangerLevelCode"), "II")
                    self.assertEqual(path(hazard, "SpecifiedLogisticsPackage", "TypeCode"), "1A1")

    def test_search_and_transport_projection_share_three_unique_uils(self):
        search = yaml.safe_load((MOCK_BASE / "search.yml").read_text())["respond_multi"]["return"]
        transport = yaml.safe_load((MOCK_BASE / "transport-means.yml").read_text())["respond_local_multi"]["return"]
        self.assertEqual(transport["found"], 3)
        self.assertEqual(search, transport["consignments"])
        self.assertEqual([row["uil"]["datasetId"] for row in search], [s["dataset"] for s in fixture.SCENARIOS])
        self.assertEqual(sum(row["dangerousGoods"] is not None for row in search), 1)
        self.assertEqual({row["mainTransportId"] for row in search}, {fixture.PLATE})
        self.assertEqual({tuple(row["usedEquipmentIds"]) for row in search}, {(fixture.TRAILER,)})
        self.assertEqual({tuple(row["carriedEquipmentIds"]) for row in search}, {(fixture.CONTAINER,)})

    @unittest.skipUnless(os.environ.get("MOCK_TEST_URL"), "optional URL of a running developer mock")
    def test_live_lookup_and_all_three_dataset_details(self):
        base = os.environ["MOCK_TEST_URL"].rstrip("/")

        def request(service, body):
            headers = {"Content-Type": "application/json", "X-Road-Client": "EE/GOV/70000097/test",
                       "X-Road-Id": "550e8400-e29b-41d4-a716-446655440099"}
            req = urllib.request.Request(base + "/developer/v1/" + service, data=json.dumps(body).encode(), headers=headers)
            with urllib.request.urlopen(req, timeout=30) as response:
                return json.load(response)

        lookup = request("transport-means", {"identifier": fixture.PLATE, "countryCode": "EE", "scope": "local"})
        self.assertEqual(lookup["found"], 3)
        for identifier in (fixture.TRAILER, fixture.CONTAINER):
            self.assertEqual(request("transport-means", {"identifier": identifier, "scope": "local"})["consignments"],
                             lookup["consignments"])
        subsets = ["EU01", "EU02", "EU03", "EU05"]
        for number, row in enumerate(lookup["consignments"], 1):
            xml = fixture.generate_scenario(number)
            self.assertEqual(request("dataset", {"uil": row["uil"], "subsets": subsets}),
                             {"uil": row["uil"], "subsets": subsets, "xml": fixture.served_xml(xml),
                              "consignment": fixture.consignment(xml)})


if __name__ == "__main__":
    unittest.main(verbosity=2)
