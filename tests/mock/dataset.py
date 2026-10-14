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
import uuid
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


def search_rows():
    """The developer mock's searchable rows as its search returns them (sequence numbers dropped)."""
    rows = yaml.safe_load((MOCK_BASE / "search.yml").read_text())["setup"]["assign"]["rows"]
    return [{k: v for k, v in row.items() if k not in ("usedEquipmentSeq", "carriedEquipmentSeq")} for row in rows]


# Search bodies and the developer mock's answer under get_consignments.sql's criteria semantics
# (dataset suffixes in its order, and x-poll-more), the contract the live gate shares.
SEARCHES = [
    ({"mainTransportId": {"operator": "EQ", "id": "MOCK-PLATE-1"}}, ["0001"], "false"),
    ({"mainTransportId": {"operator": "EQ", "id": "MOCK-PLATE-3"}}, ["0011", "0012", "0013"], "false"),
    ({"usedEquipmentId": {"operator": "EQ", "id": "CONT-MOCK-01"}}, ["0001"], "false"),
    ({"usedEquipmentId": {"operator": "EQ", "id": "MOCK-TRAILER-3"}}, ["0011", "0012", "0013"], "false"),
    ({"carriedEquipmentId": {"operator": "EQ", "id": "MOCK-CONTAINER-3"}}, ["0011", "0012", "0013"], "false"),
    ({"transportMeansOrEquipmentId": {"operator": "EQ", "id": "MOCK-TRAILER-3"}}, ["0011", "0012", "0013"], "false"),
    ({"mainTransportId": {"operator": "EQ", "id": "MOCK-PLATE-3"}, "dangerousGoodsCode": {"operator": "EQ", "code": "1"}},
     ["0013"], "false"),
    ({"mainTransportId": {"operator": "EQ", "id": "MOCK-PLATE-3"},
      "loadingDate": [{"operator": "GE", "date": "2026-09-14T08:00:00Z"}, {"operator": "LT", "date": "2026-09-14T12:00:00Z"}]},
     ["0012"], "false"),
    ({"mainTransportId": {"operator": "EQ", "id": "MOCK-PLATE-3"}, "dangerousGoodsCode": {"operator": "NE", "code": "1"}},
     [], "true"),
    ({"mainTransportId": {"operation": "EQ", "id": "MOCK-PLATE-3"}}, [], "true"),
    ({"mainTransportId": {"operator": "EQ", "id": "NO-SUCH-PLATE"}}, [], "true"),
    ({"poll": True}, [], "false"),
]


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

    def test_full_field_values_are_meaningful(self):
        names = {local for element in self.root.iter() for local in [element.tag.split("}")[-1], *element.attrib]}
        placeholders = {("MOCK-" + name)[:size] for name in names for size in range(4, len(name) + 6)} | {"MOC", "MO"}
        values = [(element.tag.split("}")[-1], element.text) for element in self.root.iter() if not len(element)]
        values += [(element.tag.split("}")[-1] + "@" + name, text) for element in self.root.iter()
                   for name, text in element.attrib.items()]
        for name, text in values:
            with self.subTest(field=name, value=text):
                self.assertTrue(text and text.strip())
                self.assertNotIn(text, placeholders)
                self.assertNotEqual(text, "TW9jayBhdHRhY2htZW50")
                for identifier in re.findall(r"MOCK-[A-Z0-9-]+", text):
                    self.assertIn(identifier, fixture.MOCK_IDENTIFIERS)
        self.assertFalse(fixture.MOCK_IDENTIFIERS & placeholders)

    def test_full_field_vehicle_and_equipment_are_distinguishable(self):
        movement = "MainCarriageLogisticsTransportMovement"
        self.assertEqual(path(self.root, movement, "UsedLogisticsTransportMeans", "ID"), fixture.MAX_PLATE)
        trailer = self.root.find(RAM + "UtilizedLogisticsTransportEquipment")
        self.assertEqual((path(trailer, "ID"), path(trailer, "CategoryCode")), (fixture.MAX_TRAILER, "TE"))
        self.assertEqual(path(self.root, movement, "UsedLogisticsTransportMeans", "OperationalAttachedTransportEquipment", "ID"),
                         fixture.MAX_TRAILER)
        containers = trailer.findall(RAM + "CarriedAssociatedTransportEquipment")
        self.assertEqual([(path(c, "ID"), path(c, "CategoryCode")) for c in containers],
                         [(fixture.MAX_CONTAINERS[0], "CN"), (fixture.MAX_CONTAINERS[1], "CN")])
        items = self.root.findall(RAM + "IncludedSupplyChainConsignmentItem")
        self.assertEqual([path(i, "AssociatedReferencedLogisticsTransportEquipment", "ID") for i in items],
                         list(fixture.MAX_CONTAINERS))
        self.assertEqual(len({fixture.MAX_PLATE, fixture.MAX_TRAILER, *fixture.MAX_CONTAINERS}), 4)
        self.assertEqual({e.get("currencyID") for e in self.root.iter() if e.get("currencyID")}, {"EUR"})

    def test_full_field_repeats_become_arrays(self):
        projected = fixture.consignment(self.xml)
        items = projected["includedSupplyChainConsignmentItem"]
        self.assertEqual([i["associatedReferencedLogisticsTransportEquipment"]["Id"] for i in items], list(fixture.MAX_CONTAINERS))
        self.assertIn("applicableTransportDangerousGoods", items[0])
        self.assertNotIn("applicableTransportDangerousGoods", items[1])
        carried = projected["utilizedLogisticsTransportEquipment"]["carriedAssociatedTransportEquipment"]
        self.assertEqual([c["Id"] for c in carried], list(fixture.MAX_CONTAINERS))

    def test_full_field_covers_the_schema_skeleton(self):
        def paths(root):
            def walk(node, prefix):
                here = prefix + "/" + node.tag.split("}")[-1]
                yield here
                for child in node:
                    yield from walk(child, here)
            return set(walk(root, ""))
        self.assertEqual(paths(self.root), paths(fixture.skeleton()))

    def test_full_field_dates_match_their_format(self):
        for element in self.root.iter("{" + fixture.PREFIXES["udt"] + "}DateTimeString"):
            with self.subTest(value=element.text):
                pattern = r"\d{8}" if element.get("format") == "102" else r"\d{12}[+-]\d{4}"
                self.assertRegex(element.text, "^" + pattern + "$")

    def test_field_catalogue_is_current_and_complete(self):
        text = (ROOT / "docs/developer/fti010-field-catalogue.md").read_text()
        self.assertEqual(text, fixture.catalogue())
        rows = [line.split(" | ") for line in text.splitlines() if re.match(r"\| \d+ \|", line)]
        xpaths = {row[1].strip("`") for row in rows}
        for element in self.root.iter():
            if not len(element):
                found = [x for x in xpaths if x.endswith("/" + element.tag.split("}")[-1])]
                self.assertTrue(found, element.tag)
        undocumented = [row[1] for row in rows if not row[3]]
        self.assertTrue(all(x.endswith(("/@uri`", "/@filename`")) for x in undocumented), undocumented)
        self.assertGreater(len(rows), 500)

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
        search = search_rows()
        transport = yaml.safe_load((MOCK_BASE / "transport-means.yml").read_text())
        multi = transport["respond_local_multi"]["return"]
        self.assertEqual(multi["found"], 3)
        # get_consignments.sql order: created_at DESC, revision DESC.
        self.assertEqual(search, multi["consignments"] + transport["respond_local_hit"]["return"]["consignments"])
        plate3 = search[:3]
        self.assertEqual([row["uil"]["datasetId"] for row in plate3], [s["dataset"] for s in fixture.SCENARIOS])
        self.assertEqual(sum(row["dangerousGoods"] is not None for row in plate3), 1)
        self.assertEqual({row["mainTransportId"] for row in plate3}, {fixture.PLATE})
        self.assertEqual({tuple(row["usedEquipmentIds"]) for row in plate3}, {(fixture.TRAILER,)})
        self.assertEqual({tuple(row["carriedEquipmentIds"]) for row in plate3}, {(fixture.CONTAINER,)})

    def test_search_rows_carry_equipment_sequence_numbers(self):
        for row in yaml.safe_load((MOCK_BASE / "search.yml").read_text())["setup"]["assign"]["rows"]:
            with self.subTest(dataset=row["uil"]["datasetId"]):
                self.assertEqual(row["usedEquipmentSeq"], [1] * len(row["usedEquipmentIds"]))
                self.assertEqual(row["carriedEquipmentSeq"], [1] * len(row["carriedEquipmentIds"]))

    @unittest.skipUnless(os.environ.get("MOCK_TEST_URL"), "optional URL of a running developer mock")
    def test_live_search_lookup_and_all_three_dataset_details(self):
        base = os.environ["MOCK_TEST_URL"].rstrip("/")

        def exchange(service, body, request_id="550e8400-e29b-41d4-a716-446655440099"):
            headers = {"Content-Type": "application/json", "X-Road-Client": "EE/GOV/70000097/test",
                       "X-Road-Id": request_id}
            req = urllib.request.Request(base + "/developer/v1/" + service, data=json.dumps(body).encode(), headers=headers)
            with urllib.request.urlopen(req, timeout=30) as response:
                return json.load(response), response.headers

        def request(service, body):
            return exchange(service, body)[0]

        rows = {row["uil"]["datasetId"][-4:]: row for row in search_rows()}
        for body, expected, poll_more in SEARCHES:
            with self.subTest(search=body):
                found, headers = exchange("search", body, str(uuid.uuid4()))
                self.assertEqual(found, [rows[suffix] for suffix in expected])
                self.assertEqual(headers.get("x-poll-more"), poll_more)

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
