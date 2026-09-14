#!/usr/bin/env python3
"""Print a bounded, field-rich synthetic FTI010 consignment; never write files."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
XS = "{http://www.w3.org/2001/XMLSchema}"
PREFIXES = {
    "rsm": "urn:eu:move:eFTI:data:standard:FTI010GetCmdsResponse:1",
    "ram": "urn:eu:move:eFTI:data:standard:ReusableAggregateBusinessInformationEntity:34",
    "qdt": "urn:eu:move:eFTI:data:standard:QualifiedDataType:34",
    "udt": "urn:eu:move:eFTI:data:standard:UnqualifiedDataType:34",
    "xs": "http://www.w3.org/2001/XMLSchema",
}
TYPES = {}
for path in sorted((ROOT / "code/xml-mapper/xsd/FTI010").glob("*.xsd")):
    schema = ET.parse(path).getroot()
    for node in schema:
        if node.tag in (XS + "complexType", XS + "simpleType"):
            TYPES[(schema.attrib["targetNamespace"], node.attrib["name"])] = node


def resolve(name):
    prefix, local = name.split(":")
    return PREFIXES[prefix], local


def value(name, field):
    namespace, local = resolve(name)
    if namespace == PREFIXES["xs"]:
        if local == "boolean":
            return "true"
        if local in ("decimal", "integer", "int", "nonNegativeInteger", "positiveInteger", "float", "double"):
            return "1"
        if local == "dateTime":
            return "2026-09-14T09:00:00Z"
        if local == "date":
            return "2026-09-14"
        if local == "time":
            return "09:00:00Z"
        if local == "base64Binary":
            return "TW9jayBhdHRhY2htZW50"
        return "MOCK-" + field
    node = TYPES[(namespace, local)]
    restriction = node.find(XS + "restriction")
    if restriction is not None:
        options = [entry.attrib["value"] for entry in restriction.findall(XS + "enumeration")]
        if options:
            for preferred in ("EE", "KGM", "MTQ", "EUR", "3", "102", "RFC 9562-4"):
                if preferred in options:
                    return preferred
            return options[0]
        result = value(restriction.attrib["base"], field)
        exact = restriction.find(XS + "length")
        if exact is not None:
            size = int(exact.attrib["value"])
            result = result[:size].ljust(size, "0")
        length = restriction.find(XS + "maxLength")
        return result[:int(length.attrib["value"])] if length is not None else result
    extension = node.find(".//" + XS + "extension")
    if extension is not None:
        return value(extension.attrib["base"], field)
    raise ValueError("Unsupported scalar type: " + name)


def populate(element, type_name, stack=(), inline=None):
    key = resolve(type_name)
    if inline is None and (key[0] == PREFIXES["xs"] or TYPES[key].tag == XS + "simpleType"):
        element.text = value(type_name, element.tag.split("}")[-1])
        return
    if key in stack:
        raise ValueError("Recursive schema requires an explicit fixture bound: " + type_name)
    node = inline if inline is not None else TYPES[key]
    content = node.find(XS + "simpleContent")
    if content is not None:
        extension = content.find(XS + "extension")
        element.text = value(extension.attrib["base"], element.tag.split("}")[-1])
        for attr in extension.findall(XS + "attribute"):
            if attr.attrib.get("use") != "prohibited":
                element.set(attr.attrib["name"], attr.attrib.get("fixed") or value(attr.attrib["type"], attr.attrib["name"]))
        return
    for group in node:
        if group.tag not in (XS + "sequence", XS + "choice"):
            continue
        entries = group.findall(XS + "element")
        if group.tag == XS + "choice":
            entries = entries[:1]
        for entry in entries:
            if entry.attrib.get("maxOccurs") == "0":
                continue
            child = ET.SubElement(element, "{" + key[0] + "}" + entry.attrib["name"])
            if "type" in entry.attrib:
                populate(child, entry.attrib["type"], stack + (key,))
            else:
                populate(child, "udt:inline-" + entry.attrib["name"], stack + (key,), entry.find(XS + "complexType"))


def generate():
    ET.register_namespace("rsm", PREFIXES["rsm"])
    ET.register_namespace("", PREFIXES["ram"])
    ET.register_namespace("udt", PREFIXES["udt"])
    root = ET.Element("{" + PREFIXES["rsm"] + "}SpecifiedSupplyChainConsignment")
    populate(root, "ram:SupplyChainConsignmentType")
    overrides = {
        "GrossWeightMeasure": "15000.00", "NetWeightMeasure": "12000.00",
        "GrossVolumeMeasure": "45.00", "PackageQuantity": "120",
        "TransportEquipmentQuantity": "1", "ModeCode": "3",
        "DateTimeString": "20260914", "Information": "Full-field synthetic test fixture",
        "CountryID": "EE", "UNDGIdentificationCode": "1203",
    }
    for element in root.iter():
        local = element.tag.split("}")[-1]
        if local in overrides and not len(element):
            element.text = overrides[local]
        if local == "DateTimeString":
            element.set("format", "102")
        if local == "UsedLogisticsTransportMeans":
            child = element.find("{" + PREFIXES["ram"] + "}ID")
            if child is not None:
                child.text = "MOCK-PLATE-MAX"
    ET.indent(root, space="  ")
    return ET.tostring(root, encoding="unicode") + "\n"


SCENARIOS = (
    ("550e8400-e29b-41d4-a716-446655440011", "Tallinn", "Peterburi tee 10", "06", "2000", "Furniture", False),
    ("550e8400-e29b-41d4-a716-446655440012", "Tartu", "Ringtee 20", "09", "3000", "Machine parts", False),
    ("550e8400-e29b-41d4-a716-446655440013", "Parnu", "Tallinna mnt 30", "12", "1500", "PETROL UN 1203", True),
)


def generate_scenario(number):
    """One truck, three pickups, one destination; third shipment is dangerous."""
    dataset, city, street, hour, weight, goods, dangerous = SCENARIOS[number - 1]
    ram, udt = PREFIXES["ram"], PREFIXES["udt"]
    ET.register_namespace("rsm", PREFIXES["rsm"])
    ET.register_namespace("", ram)
    ET.register_namespace("udt", udt)
    root = ET.Element("{" + PREFIXES["rsm"] + "}SpecifiedSupplyChainConsignment")

    def add(parent, name, text=None, **attrs):
        node = ET.SubElement(parent, "{" + ram + "}" + name, attrs)
        node.text = text
        return node

    def address(parent, town, road):
        node = add(parent, "PostalTradeAddress")
        add(node, "StreetName", road)
        add(node, "CityName", town)
        add(node, "CountryID", "EE")

    def event(parent, name, time, town, road):
        node = add(parent, name)
        date = add(node, "ActualOccurrenceDateTime")
        date_only = name == "UnloadingTransportEvent"
        ET.SubElement(date, "{" + udt + "}DateTimeString", {"format": "102" if date_only else "205"}).text = "20260915" if date_only else "20260914" + time + "0000"
        location = add(node, "OccurrenceLogisticsLocation")
        add(location, "Name", town + " warehouse")
        address(location, town, road)

    add(root, "GrossWeightMeasure", weight, unitCode="KGM")
    add(root, "NetWeightMeasure", str(int(weight) - 100), unitCode="KGM")
    add(root, "PackageQuantity", "10")
    add(root, "Information", goods)
    sender = add(root, "ConsignorTradeParty")
    add(sender, "Name", city + " Mock Supplier")
    address(sender, city, street)
    receiver = add(root, "ConsigneeTradeParty")
    add(receiver, "Name", "Narva Mock Distribution Centre")
    address(receiver, "Narva", "Kadastiku 25")
    add(add(root, "CarrierTradeParty"), "Name", "Mock Multi-pickup Carrier")
    item = add(root, "IncludedSupplyChainConsignmentItem")
    add(item, "GoodsUnitQuantity", "10", unitCode="C62")
    if dangerous:
        hazard = add(item, "ApplicableTransportDangerousGoods")
        add(hazard, "UNDGIdentificationCode", "1203")
        add(hazard, "TechnicalName", "PETROL", languageID="eng")
        add(hazard, "PackagingDangerLevelCode", "II")
        add(hazard, "GrossWeightMeasure", weight, unitCode="KGM")
        add(hazard, "HazardClassificationID", "3")
        add(hazard, "ProperShippingName", "PETROL", languageID="eng")
        add(hazard, "TunnelRestrictionCode", "D/E")
    movement = add(root, "MainCarriageLogisticsTransportMovement")
    add(movement, "ModeCode", "3")
    add(movement, "ID", "MOCK-MULTI-0914")
    add(movement, "SequenceNumeric", "1")
    event(movement, "LoadingTransportEvent", hour, city, street)
    event(movement, "UnloadingTransportEvent", "16", "Narva", "Kadastiku 25")
    vehicle = add(movement, "UsedLogisticsTransportMeans")
    add(vehicle, "TypeCode", "1522")
    add(vehicle, "ID", "MOCK-PLATE-3", schemeAgencyID="5")
    add(vehicle, "Name", "Mock three-consignment truck")
    add(add(vehicle, "RegistrationTradeCountry"), "ID", "EE")
    ET.indent(root, space="  ")
    return ET.tostring(root, encoding="unicode") + "\n"


if __name__ == "__main__":
    number = int(sys.argv[sys.argv.index("--scenario") + 1]) if "--scenario" in sys.argv else None
    xml = generate_scenario(number) if number else generate()
    if "--envelope" in sys.argv:
        envelope = ET.parse(ROOT / "code/xml-mapper/xsd/FTI010/sample.xml").getroot()
        envelope.remove(envelope.find("{" + PREFIXES["rsm"] + "}SpecifiedSupplyChainConsignment"))
        envelope.append(ET.fromstring(xml))
        for element in envelope.iter():
            if element.tag == "{" + PREFIXES["ram"] + "}DatasetID":
                element.text = "550e8400-e29b-41d4-a716-446655440002"
        ET.indent(envelope, space="  ")
        xml = ET.tostring(envelope, encoding="unicode") + "\n"
    sys.stdout.write(xml)
