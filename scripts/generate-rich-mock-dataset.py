#!/usr/bin/env python3
"""Print a bounded, field-rich synthetic FTI010 consignment; never write files."""
from pathlib import Path
import re
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



SIMPLE = """<rsm:SpecifiedSupplyChainConsignment
    xmlns:rsm="urn:eu:move:eFTI:data:standard:FTI010GetCmdsResponse:1"
    xmlns="urn:eu:move:eFTI:data:standard:ReusableAggregateBusinessInformationEntity:34"
    xmlns:udt="urn:eu:move:eFTI:data:standard:UnqualifiedDataType:34">
  <GrossWeightMeasure unitCode="KGM">15000.00</GrossWeightMeasure>
  <PackageQuantity>120</PackageQuantity>
  <MainCarriageLogisticsTransportMovement>
    <ModeCode>3</ModeCode>
    <UsedLogisticsTransportMeans>
      <ID schemeAgencyID="5">MOCK-PLATE-1</ID>
      <RegistrationTradeCountry><ID>EE</ID></RegistrationTradeCountry>
    </UsedLogisticsTransportMeans>
  </MainCarriageLogisticsTransportMovement>
</rsm:SpecifiedSupplyChainConsignment>
"""

PLATE, TRAILER, CONTAINER = "MOCK-PLATE-3", "MOCK-TRAILER-3", "MOCK-CONTAINER-3"
SCENARIOS = (
    {"dataset": "550e8400-e29b-41d4-a716-446655440011", "city": "Tallinn", "street": "Peterburi tee 10",
     "postcode": "11415", "hour": "06", "weight": "2000", "volume": "8.5", "packages": "10",
     "package": ("PX", "Kaubaalus"), "goods": "Mööbel: kontorilauad ja -toolid",
     "cmr": "MOCK-CMR-0011", "remark": "Kaup kinnitada rihmadega; mitte virnastada.", "dangerous": None},
    {"dataset": "550e8400-e29b-41d4-a716-446655440012", "city": "Tartu", "street": "Ringtee 20",
     "postcode": "50105", "hour": "09", "weight": "3000", "volume": "6.0", "packages": "10",
     "package": ("CR", "Puitkast"), "goods": "Masinaosad: laagrid ja võllid",
     "cmr": "MOCK-CMR-0012", "remark": "Raske kaup, mahalaadimiseks on vaja tõstukit.", "dangerous": None},
    {"dataset": "550e8400-e29b-41d4-a716-446655440013", "city": "Pärnu", "street": "Tallinna mnt 30",
     "postcode": "80042", "hour": "12", "weight": "1500", "volume": "2.1", "packages": "10",
     "package": ("1A1", "Terasvaat"), "goods": "Bensiin (UN 1203) terasvaatides",
     "cmr": "MOCK-CMR-0013", "remark": "ADR-veos: juhil peab olema kehtiv ADR-koolitustunnistus.",
     "dangerous": {"un": "1203", "name": "PETROL", "group": "II", "class": "3", "label": "3", "tunnel": "D/E"}},
)


def generate_scenario(number):
    """One truck with a trailer and container, three pickups, one destination; the third is dangerous."""
    s = SCENARIOS[number - 1]
    ram, udt = PREFIXES["ram"], PREFIXES["udt"]
    ET.register_namespace("rsm", PREFIXES["rsm"])
    ET.register_namespace("", ram)
    ET.register_namespace("udt", udt)
    root = ET.Element("{" + PREFIXES["rsm"] + "}SpecifiedSupplyChainConsignment")

    def add(parent, name, text=None, **attrs):
        node = ET.SubElement(parent, "{" + ram + "}" + name, attrs)
        node.text = text
        return node

    def date(parent, name, text, fmt):
        ET.SubElement(add(parent, name), "{" + udt + "}DateTimeString", {"format": fmt}).text = text

    def address(parent, town, road, postcode):
        node = add(parent, "PostalTradeAddress")
        add(node, "PostcodeCode", postcode)
        add(node, "StreetName", road)
        add(node, "CityName", town)
        add(node, "CountryID", "EE")

    def party(name, title, code, town, road, postcode, phone):
        node = add(root, name)
        add(node, "ID", code, schemeAgencyID="87" if name != "CarrierTradeParty" else "12")
        add(node, "Name", title)
        if name == "CarrierTradeParty":
            add(node, "RoleCode", "EV")
        contact = add(node, "DefinedTradeContact")
        if name != "ConsignorTradeParty":
            add(contact, "PersonName", "Mari Mock")
        add(add(contact, "TelephoneUniversalCommunication"), "CompleteNumber", phone)
        address(node, town, road, postcode)

    def event(parent, name, town, road, postcode):
        node = add(parent, name)
        if name == "UnloadingTransportEvent":
            date(node, "ActualOccurrenceDateTime", "20260915", "102")
        else:
            date(node, "ActualOccurrenceDateTime", "20260914" + s["hour"] + "00+0000", "205")
        location = add(node, "OccurrenceLogisticsLocation")
        add(location, "Name", town + " ladu")
        address(location, town, road, postcode)

    add(root, "GrossWeightMeasure", s["weight"], unitCode="KGM")
    add(root, "NetWeightMeasure", str(int(s["weight"]) - 100), unitCode="KGM")
    add(root, "GrossVolumeMeasure", s["volume"], unitCode="MTQ")
    add(root, "PackageQuantity", s["packages"])
    add(root, "TransportEquipmentQuantity", "2")
    add(root, "Information", s["remark"])
    party("ConsignorTradeParty", s["city"] + " Mock Supplier", "MOCK-EE-" + s["dataset"][-4:],
          s["city"], s["street"], s["postcode"], "+372 5550 " + s["dataset"][-4:])
    party("ConsigneeTradeParty", "Narva Mock Distribution Centre", "MOCK-EE-2500",
          "Narva", "Kadastiku 25", "20203", "+372 5550 2500")
    party("CarrierTradeParty", "Mock Multi-pickup Carrier", "MOCK-EE-3000",
          "Tallinn", "Mustamäe tee 5", "10616", "+372 5550 3000")
    document = add(root, "TransportContractReferencedDocument")
    add(document, "TypeCode", "730")
    add(document, "ID", s["cmr"])
    date(document, "FormattedIssueDateTime", "20260914", "102")
    item = add(root, "IncludedSupplyChainConsignmentItem")
    add(item, "GrossVolumeMeasure", s["volume"], unitCode="MTQ")
    add(item, "GoodsUnitQuantity", s["packages"], unitCode="C62")
    hazard = s["dangerous"]
    if hazard:
        node = add(item, "ApplicableTransportDangerousGoods")
        add(node, "UNDGIdentificationCode", hazard["un"])
        add(node, "TechnicalName", hazard["name"], languageID="eng")
        add(node, "PackagingDangerLevelCode", hazard["group"])
        add(node, "GrossWeightMeasure", s["weight"], unitCode="KGM")
        add(node, "HazardClassificationID", hazard["class"])
        add(node, "ProperShippingName", hazard["name"], languageID="eng")
        add(node, "TunnelRestrictionCode", hazard["tunnel"])
        add(node, "LabelTypeCode", hazard["label"])
        package = add(node, "SpecifiedLogisticsPackage")
        add(package, "ItemQuantity", s["packages"])
        add(package, "TypeCode", s["package"][0])
        add(package, "Type", s["package"][1], languageID="et")
    add(add(item, "AssociatedReferencedLogisticsTransportEquipment"), "ID", CONTAINER, schemeAgencyID="12")
    trailer = add(root, "UtilizedLogisticsTransportEquipment")
    add(trailer, "ID", TRAILER, schemeAgencyID="12")
    add(trailer, "CategoryCode", "TE")
    add(trailer, "SequenceNumeric", "1")
    container = add(trailer, "CarriedAssociatedTransportEquipment")
    add(container, "ID", CONTAINER, schemeAgencyID="12")
    add(container, "CategoryCode", "CN")
    add(container, "SequenceNumeric", "1")
    seal = add(container, "AffixedLogisticsSeal")
    add(seal, "ID", "MOCK-SEAL-3")
    add(add(trailer, "RegistrationTradeCountry"), "ID", "EE")
    movement = add(root, "MainCarriageLogisticsTransportMovement")
    add(movement, "ModeCode", "3")
    add(movement, "ID", "MOCK-MULTI-0914")
    add(movement, "SequenceNumeric", "1")
    driver = add(movement, "MasterResponsibleTransportPerson")
    add(driver, "GivenName", "Mati")
    add(driver, "FamilyName", "Mock")
    event(movement, "LoadingTransportEvent", s["city"], s["street"], s["postcode"])
    event(movement, "UnloadingTransportEvent", "Narva", "Kadastiku 25", "20203")
    vehicle = add(movement, "UsedLogisticsTransportMeans")
    add(vehicle, "TypeCode", "1522")
    add(vehicle, "ID", PLATE, schemeAgencyID="5")
    add(vehicle, "Name", "Mock three-consignment truck")
    add(add(vehicle, "RegistrationTradeCountry"), "ID", "EE")
    attached = add(vehicle, "OperationalAttachedTransportEquipment")
    add(attached, "ID", TRAILER)
    add(attached, "CategoryCode", "TE", listAgencyID="6")
    transport_package = add(root, "TransportLogisticsPackage")
    add(transport_package, "ItemQuantity", s["packages"])
    add(transport_package, "TypeCode", s["package"][0])
    add(transport_package, "Type", s["package"][1], languageID="et")
    add(add(root, "NatureIdentificationTransportCargo"), "Identification", s["goods"], languageID="et")
    ET.indent(root, space="  ")
    return ET.tostring(root, encoding="unicode") + "\n"


DATASETS = {
    "550e8400-e29b-41d4-a716-446655440002": generate,
    **{s["dataset"]: (lambda n: lambda: generate_scenario(n))(n) for n, s in enumerate(SCENARIOS, 1)},
}


def served_xml(xml):
    """The `xml` xml-mapper's /api/v1/dataset/response-to-json returns for this platform XML: the
    consignment root loses its `rsm:` prefix and the trailing newline goes."""
    return re.sub("(</?)rsm:SpecifiedSupplyChainConsignment", r"\1SpecifiedSupplyChainConsignment", xml).rstrip("\n")


def json_name(name):
    """xml-mapper's node naming: first letter lower-cased, then every case-insensitive `id` becomes `Id`
    (so `ID` -> `Id`, `Identification` keeps its capital and `WidthMeasure` -> `wIdthMeasure`)."""
    name = name.split("}")[-1]
    return re.sub("(?i)id", "Id", name[0].lower() + name[1:])


def consignment(xml):
    """The `consignment` object xml-mapper's /api/v1/dataset/response-to-json builds from this XML."""
    def convert(node):
        if not len(node):
            return (node.text or "").strip() if node.text and node.text.strip() else {}
        result = {}
        for child in node:
            key = json_name(child.tag)
            value = convert(child)
            if key in result:
                result[key] = result[key] if isinstance(result[key], list) else [result[key]]
                result[key].append(value)
            else:
                result[key] = value
                for attr, text in child.attrib.items():
                    result[key + "@" + json_name(attr)] = text
        return result
    return convert(ET.fromstring(xml))


if __name__ == "__main__":
    number = int(sys.argv[sys.argv.index("--scenario") + 1]) if "--scenario" in sys.argv else None
    xml = generate_scenario(number) if number else generate()
    if "--envelope" in sys.argv:
        envelope = ET.parse(ROOT / "code/xml-mapper/xsd/FTI010/sample.xml").getroot()
        envelope.remove(envelope.find("{" + PREFIXES["rsm"] + "}SpecifiedSupplyChainConsignment"))
        envelope.append(ET.fromstring(xml))
        for element in envelope.iter():
            if element.tag == "{" + PREFIXES["ram"] + "}DatasetID":
                element.text = SCENARIOS[number - 1]["dataset"] if number else "550e8400-e29b-41d4-a716-446655440002"
        ET.indent(envelope, space="  ")
        xml = ET.tostring(envelope, encoding="unicode") + "\n"
    sys.stdout.write(xml)
