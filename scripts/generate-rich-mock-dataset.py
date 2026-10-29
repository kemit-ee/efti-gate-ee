#!/usr/bin/env python3
"""Print a bounded, field-rich synthetic FTI010 consignment; never write files."""
from pathlib import Path
import base64
import copy
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


def facets(name):
    """(enumeration values, maxLength, exact length) a scalar type allows, following restriction bases."""
    namespace, local = resolve(name)
    if namespace == PREFIXES["xs"]:
        return [], None, None
    node = TYPES[(namespace, local)]
    restriction = node.find(XS + "restriction")
    if restriction is None:
        extension = node.find(".//" + XS + "extension")
        return facets(extension.attrib["base"]) if extension is not None else ([], None, None)
    options, limit, exact = facets(restriction.attrib["base"])
    options = [entry.attrib["value"] for entry in restriction.findall(XS + "enumeration")] or options
    if restriction.find(XS + "maxLength") is not None:
        limit = int(restriction.find(XS + "maxLength").attrib["value"])
    if restriction.find(XS + "length") is not None:
        exact = int(restriction.find(XS + "length").attrib["value"])
    return options, limit, exact


def documentation(node):
    """The eFTI_* annotation of an xs:element or xs:attribute, as {"ID": ..., "BN": ..., "DEF": ..., "CL": ...}."""
    doc = node.find(XS + "annotation/" + XS + "documentation") if node is not None else None
    if doc is None:
        return {}
    return {child.tag[len("eFTI_"):]: " ".join((child.text or "").split()) for child in doc if child.tag.startswith("eFTI_")}


# Per generated element: its scalar type, its eFTI documentation and those of its attributes.
INFO = {}


def populate(element, type_name, stack=(), inline=None, doc=None):
    key = resolve(type_name)
    INFO[element] = {"doc": doc or {}, "type": None, "attrs": {}}
    if inline is None and (key[0] == PREFIXES["xs"] or TYPES[key].tag == XS + "simpleType"):
        INFO[element]["type"] = type_name
        element.text = value(type_name, element.tag.split("}")[-1])
        return
    if key in stack:
        raise ValueError("Recursive schema requires an explicit fixture bound: " + type_name)
    node = inline if inline is not None else TYPES[key]
    content = node.find(XS + "simpleContent")
    if content is not None:
        extension = content.find(XS + "extension")
        INFO[element]["type"] = extension.attrib["base"]
        element.text = value(extension.attrib["base"], element.tag.split("}")[-1])
        for attr in extension.findall(XS + "attribute"):
            if attr.attrib.get("use") != "prohibited":
                INFO[element]["attrs"][attr.attrib["name"]] = {"type": attr.attrib["type"], "fixed": attr.attrib.get("fixed"),
                                                               "doc": documentation(attr)}
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
                populate(child, entry.attrib["type"], stack + (key,), doc=documentation(entry))
            else:
                populate(child, "udt:inline-" + entry.attrib["name"], stack + (key,), entry.find(XS + "complexType"),
                         documentation(entry))


def skeleton():
    """Every FTI010 consignment element once (first choice branch), with placeholder values."""
    ET.register_namespace("rsm", PREFIXES["rsm"])
    ET.register_namespace("", PREFIXES["ram"])
    ET.register_namespace("udt", PREFIXES["udt"])
    INFO.clear()
    root = ET.Element("{" + PREFIXES["rsm"] + "}SpecifiedSupplyChainConsignment")
    populate(root, "ram:SupplyChainConsignmentType")
    return root


# The full-field fixture tells one story: Mock Värvitehas AS (Tallinn) ships paint (UN 1263, ADR class 3)
# in steel drums and tile adhesive on pallets to Mock Statyba UAB (Kaunas). Mock Vedu OÜ's truck
# MOCK-PLATE-MAX pulls trailer MOCK-TRAILER-MAX carrying containers MOCK-CONTAINER-A (the paint) and
# MOCK-CONTAINER-B (the adhesive), with a transshipment in Riga. Companies are fictional ("Mock ..."),
# addresses are real-looking streets, e-mail domains use the reserved `.example` TLD. Identifiers that
# could otherwise be mistaken for real registrations are deliberately `MOCK-` prefixed and listed below.
MAX_PLATE, MAX_TRAILER, MAX_CONTAINERS = "MOCK-PLATE-MAX", "MOCK-TRAILER-MAX", ("MOCK-CONTAINER-A", "MOCK-CONTAINER-B")
MOCK_IDENTIFIERS = {
    MAX_PLATE, MAX_TRAILER, *MAX_CONTAINERS, "MOCK-CMR-MAX", "MOCK-INV-0914", "MOCK-FILE-0914", "MOCK-TRIP-0914",
    "MOCK-EE-1001", "MOCK-LT-2001", "MOCK-EE-3001", "MOCK-LV-4001", "MOCK-EE-5001", "MOCK-EE-6001",
    "MOCK-VAT-EE-1001", "MOCK-VAT-LT-2001", "MOCK-VAT-EE-3001", "MOCK-ACC-1001", "MOCK-LIC-EE-3001",
    "MOCK-LIC-LV-4001", "MOCK-SIG-1001", "MOCK-DRV-EE-0001", "MOCK-SEAL-T-0001", "MOCK-SEAL-C-0001",
    "MOCK-SEAL-C-0002", "MOCK-ADR-CERT-01", "MOCK-ADR-EX-01",
}
TOP = "SpecifiedSupplyChainConsignment/"
ITEM = "IncludedSupplyChainConsignmentItem/"
ITEM_DG = ITEM + "ApplicableTransportDangerousGoods/"
TRAILER_EQ = "UtilizedLogisticsTransportEquipment/"
CONTAINER_EQ = TRAILER_EQ + "CarriedAssociatedTransportEquipment/"
MOVEMENT = "MainCarriageLogisticsTransportMovement/"


def address(holder, street, building, city, postcode, country, region, extra, box=None, department=None):
    """Rules for every PostalTradeAddress field a holder may have (not every holder has all of them)."""
    prefix = holder + "/PostalTradeAddress/"
    rules = {"StreetName": street, "BuildingNumber": building, "CityName": city, "PostcodeCode": postcode,
             "CountryID": country, "CountrySubDivisionName": region, "AdditionalStreetName": extra,
             "PostOfficeBox": box or "PK " + building, "DepartmentName": department or "Logistikaosakond"}
    return {prefix + field: text for field, text in rules.items()}


def attachment(path, text, filename=None):
    rules = {path: base64.b64encode(text.encode()).decode()}
    if filename:
        rules[path + "@uri"] = "https://docs.mock-efti.example/" + filename
        rules[path + "@filename"] = filename
    return rules


CONSIGNOR = ("Peterburi tee", "46", "Tallinn", "11415", "EE", "Harju maakond", "korpus 3")
CONSIGNEE = ("Savanorių pr.", "200", "Kaunas", "LT-44001", "LT", "Kauno apskritis", "korpusas 2")
CARRIER = ("Ringtee", "35", "Tartu", "50105", "EE", "Tartu maakond", "hoone A")
OPTIONAL = {}
for holder, place, box, department in (
        ("ConsignorTradeParty", CONSIGNOR, "PK 101", "Laoosakond"),
        ("ConsigneeTradeParty", CONSIGNEE, "PD 15", None),
        ("CarrierTradeParty", CARRIER, "PK 33", None),
        ("FreightForwarderTradeParty", ("Krasta iela", "68", "Rīga", "LV-1019", "LV", "Rīga", "korpuss 1"), "PK 7", "Muitas nodaļa"),
        ("ConnectingCarrierTradeParty", ("Dzirnavu iela", "5", "Jelgava", "LV-3001", "LV", "Jelgava", "2. korpuss"), "PK 12", None),
        ("CarrierAcceptanceLogisticsLocation", CONSIGNOR[:-1] + ("laadimisramp 4",), None, None),
        ("ConsigneeReceiptLogisticsLocation", CONSIGNEE[:-1] + ("rampa 2",), None, None),
        (TRAILER_EQ + "OperatingTradeParty", CARRIER, "PK 33", "Veopark"),
        (TRAILER_EQ + "OwnerTradeParty", ("Narva mnt", "7", "Tallinn", "10117", "EE", "Harju maakond", "5. korrus"), "PK 77", "Liisinguosakond"),
        (MOVEMENT + "LoadingTransportEvent/OccurrenceLogisticsLocation", CONSIGNOR[:-1] + ("laadimisramp 4",), "PK 101", "Laoosakond"),
        (MOVEMENT + "UnloadingTransportEvent/OccurrenceLogisticsLocation", CONSIGNEE[:-1] + ("rampa 2",), "PD 15", "Sandėlis")):
    OPTIONAL.update(address(holder, *place, box=box, department=department))

DATES = {
    "CarrierAcceptanceDateTime": "202609140730+0300",
    "CarrierTradeParty/AgreedTradeContract/IssueDateTime": "20260105",
    "TransportContractReferencedDocument/FormattedIssueDateTime": "202609140700+0300",
    "DeliveryTransportEvent/ActualOccurrenceDateTime": "20260915",
    "DeliveryTransportEvent/ScheduledOccurrenceDateTime": "20260915",
    "DeliveryTransportEvent/RequestedOccurrenceDateTime": "20260916",
    "DeliveryTransportEvent/ScheduledOccurrenceSpecifiedPeriod/StartDateTime": "20260915",
    "DeliveryTransportEvent/ScheduledOccurrenceSpecifiedPeriod/EndDateTime": "20260916",
    "ConfirmedDocumentAuthentication/ActualDateTime": "202609140715+0300",
    TRAILER_EQ + "LoadedTransportDangerousGoods/StatedSpecifiedCondition/ActionDateTime": "202609140745+0300",
    CONTAINER_EQ + "LoadedTransportDangerousGoods/StatedSpecifiedCondition/ActionDateTime": "202609140750+0300",
    MOVEMENT + "MasterResponsibleTransportPerson/BirthDateTime": "19800512",
    MOVEMENT + "LoadingTransportEvent/ActualOccurrenceDateTime": "202609140800+0300",
    MOVEMENT + "LoadingTransportEvent/ScheduledOccurrenceDateTime": "20260914",
    MOVEMENT + "LoadingTransportEvent/RequestedOccurrenceDateTime": "20260913",
    MOVEMENT + "LoadingTransportEvent/ScheduledOccurrenceSpecifiedPeriod/EndDateTime": "20260914",
    MOVEMENT + "UnloadingTransportEvent/EstimatedOccurrenceDateTime": "20260915",
    MOVEMENT + "UnloadingTransportEvent/ActualOccurrenceDateTime": "20260915",
    MOVEMENT + "UnloadingTransportEvent/ScheduledOccurrenceDateTime": "20260915",
    MOVEMENT + "DepartureTransportEvent/ScheduledOccurrenceDateTime": "20260914",
    MOVEMENT + "BorderCrossingTransportEvent/ActualOccurrenceDateTime": "20260914",
    "PreCarriageLogisticsTransportMovement/ArrivalTransportEvent/ScheduledOccurrenceDateTime": "20260912",
}

RULES = {
    "@currencyID": "EUR",
    "@languageID": "et",
    **{path + "/DateTimeString": text for path, text in DATES.items()},
    TOP + "GrossWeightMeasure": "18000",
    TOP + "NetWeightMeasure": "16800",
    TOP + "GrossVolumeMeasure": "52.5",
    TOP + "CODAmount": "2500.00",
    TOP + "PackageQuantity": "64",
    TOP + "TransportEquipmentQuantity": "3",
    TOP + "Information": "Segaveos: värv (UN 1263) vaatides ja plaatimissegu kaubaalustel, kaks konteinerit "
                         "poolhaagisel. Näidis täidab kõik FTI010 väljad, ka radioaktiivse materjali ja jäätmete omad.",
    TOP + "DeclaredValueForCarriageAmount": "48500.00",
    "PaymentArrangementCode": "P",
    TOP + "DeliveryInformation": "Kättetoimetamine tööpäeviti 8-16; vastuvõtjale helistada tund enne saabumist.",
    TOP + "CargoInsuranceInstructionsInformation": "Kaup kindlustatud saatja poolt summas 48 500 EUR (CMR art 24).",
    TOP + "TransshipmentPermissionIndicator": "true",
    TOP + "ConsignorProvidedInformation": "Vaadid hoida püstiasendis; plaatimissegu kaitsta niiskuse eest.",
    TOP + "ContractTermsInformation": "CMR-konventsiooni tingimused; Incoterms 2020 DAP Kaunas.",

    "ConsignorTradeParty/ID": "MOCK-EE-1001",
    "ConsignorTradeParty/Name": "Mock Värvitehas AS",
    "ConsignorTradeParty/DefinedTradeContact/TelephoneUniversalCommunication/CompleteNumber": "+372 5550 1001",
    "ConsignorTradeParty/DefinedTradeContact/EmailURIUniversalCommunication/URIID": "saatmine@mock-varvitehas.example",
    "ConsignorTradeParty/SpecifiedTaxRegistration/ID": "MOCK-VAT-EE-1001",
    "ConsignorTradeParty/SpecifiedContactPerson/GivenName": "Mari",
    "ConsignorTradeParty/SpecifiedContactPerson/FamilyName": "Maasikas",
    "ConsignorTradeParty/OwnedCreditorFinancialAccount/ProprietaryID": "MOCK-ACC-1001",

    "ConsigneeTradeParty/ID": "MOCK-LT-2001",
    "ConsigneeTradeParty/Name": "Mock Statyba UAB",
    "ConsigneeTradeParty/DefinedTradeContact/PersonName": "Jonas Jonaitis",
    "ConsigneeTradeParty/DefinedTradeContact/TelephoneUniversalCommunication/CompleteNumber": "+370 600 00201",
    "ConsigneeTradeParty/DefinedTradeContact/FaxUniversalCommunication/CompleteNumber": "+370 37 000201",
    "ConsigneeTradeParty/DefinedTradeContact/EmailURIUniversalCommunication/URIID": "priemimas@mock-statyba.example",
    "ConsigneeTradeParty/SpecifiedTaxRegistration/ID": "MOCK-VAT-LT-2001",
    "ConsigneeTradeParty/SpecifiedContactPerson/GivenName": "Ona",
    "ConsigneeTradeParty/SpecifiedContactPerson/FamilyName": "Jonaitienė",

    "CarrierTradeParty/ID": "MOCK-EE-3001",
    "CarrierTradeParty/Name": "Mock Vedu OÜ",
    "CarrierTradeParty/DefinedTradeContact/PersonName": "Kati Karu",
    "CarrierTradeParty/DefinedTradeContact/TelephoneUniversalCommunication/CompleteNumber": "+372 5550 3001",
    "CarrierTradeParty/SpecifiedTaxRegistration/ID": "MOCK-VAT-EE-3001",
    "CarrierTradeParty/SpecifiedContactPerson/GivenName": "Kati",
    "CarrierTradeParty/SpecifiedContactPerson/FamilyName": "Karu",
    "CarrierTradeParty/AgreedTradeContract/SpecifiedDurationMeasure": "365",
    "CarrierTradeParty/AgreedTradeContract/SignedSpecifiedLocation/Name": "Tartu",
    "CarrierTradeParty/ApplicableSpecifiedLicence/ID": "MOCK-LIC-EE-3001",
    "ApplicableSpecifiedLicence/TypeCode": "CL",

    "FreightForwarderTradeParty/Name": "Mock Ekspedīcija SIA",
    "ConnectingCarrierTradeParty/ID": "MOCK-LV-4001",
    "ConnectingCarrierTradeParty/Name": "Mock Baltic Haulage SIA",
    "ConnectingCarrierTradeParty/ApplicableSpecifiedLicence/ID": "MOCK-LIC-LV-4001",

    "CarrierAcceptanceLogisticsLocation/ID": "EETLL",
    "CarrierAcceptanceLogisticsLocation/Name": "Mock Värvitehase ladu",
    "CarrierAcceptanceLogisticsLocation/PhysicalGeographicalCoordinate/LatitudeMeasure": "59.443100",
    "CarrierAcceptanceLogisticsLocation/PhysicalGeographicalCoordinate/LongitudeMeasure": "24.817200",
    "TransshipmentLogisticsLocation/ID": "LVRIX",
    "TransshipmentLogisticsLocation/Name": "Mock Rīgas terminālis",
    "ConsigneeReceiptLogisticsLocation/ID": "LTKUN",
    "ConsigneeReceiptLogisticsLocation/Name": "Mock Statyba Kauno sandėlis",
    "ConsigneeReceiptLogisticsLocation/PhysicalGeographicalCoordinate/LatitudeMeasure": "54.910500",
    "ConsigneeReceiptLogisticsLocation/PhysicalGeographicalCoordinate/LongitudeMeasure": "23.931900",

    "TransportContractReferencedDocument/URIID": "https://docs.mock-efti.example/cmr/MOCK-CMR-MAX",
    "TransportContractReferencedDocument/TypeCode": "730",
    "TransportContractReferencedDocument/ID": "MOCK-CMR-MAX",
    "TransportContractReferencedDocument/IssueLogisticsLocation/Name": "Tallinn",
    "TransportContractReferencedDocument/ContractualDocumentClause/Content":
        "Vedaja vastutab CMR-konventsiooni alusel; ooteaeg üle 2 tunni 40 EUR/h.",
    TOP + "AssociatedReferencedDocument/TypeCode": "380",
    TOP + "AssociatedReferencedDocument/ID": "MOCK-INV-0914",
    TOP + "AssociatedReferencedDocument/ReferenceTypeCode": "IV",
    TOP + "AssociatedReferencedDocument/IssuerTradeParty/Name": "Mock Värvitehas AS",
    TOP + "AssociatedReferencedDocument/IssuerTradeParty/SpecifiedRepresentativePerson/Name": "Mari Maasikas",
    TOP + "AssociatedReferencedDocument/AttachedSpecifiedBinaryFile/ID": "MOCK-FILE-0914",
    **attachment(TOP + "AssociatedReferencedDocument/AttachedSpecifiedBinaryFile/IncludedBinaryObject",
                 "Arve MOCK-INV-0914: 48 500.00 EUR", "arve-MOCK-INV-0914.txt"),

    "DeliveryTransportEvent/OccurrenceLogisticsLocation/ID": "LTKUN",
    "DeliveryTransportEvent/OccurrenceLogisticsLocation/ID@schemeAgencyID": "6",
    "DeliveryTransportEvent/OccurrenceLogisticsLocation/Name": "Mock Statyba Kauno sandėlis",
    "DeliveryTransportEvent/RelatedSpecifiedObservation/ApplicableNote/Content": "Kaup vastu võetud märkusteta.",
    "DeliveryTransportEvent/RelatedSpecifiedObservation/ApplicableNote/SubjectCode": "DEL",
    "DeliveryTransportEvent/ScheduledOccurrenceSpecifiedPeriod/DurationMeasure": "1",
    "DeliveryTransportEvent/ScheduledOccurrenceSpecifiedPeriod/MaximumDurationMeasure": "2",
    TOP + "TransportEvent/CertifyingTradeParty/ID": "MOCK-EE-1001",
    TOP + "TransportEvent/CertifyingTradeParty/SpecifiedAuthoritativeSignatoryPerson/Name": "Mari Maasikas",
    TOP + "TransportEvent/CertifyingTradeParty/SpecifiedAuthoritativeSignatoryPerson/ID": "MOCK-SIG-1001",

    ITEM + "GrossVolumeMeasure": "16",
    ITEM + "GoodsUnitQuantity": "40",
    ITEM_DG + "UNDGIdentificationCode": "1263",
    ITEM_DG + "TechnicalName": "PAINT",
    ITEM_DG + "TechnicalName@languageID": "en",
    ITEM_DG + "PackagingDangerLevelCode": "III",
    ITEM_DG + "GrossWeightMeasure": "8400",
    ITEM_DG + "HazardTypeCode": "F1",
    ITEM_DG + "HazardClassificationID": "3",
    ITEM_DG + "GrossVolumeMeasure": "8",
    ITEM_DG + "NetWeightMeasure": "8000",
    ITEM_DG + "ExplosiveCargoNetWeightMeasure": "0",
    ITEM_DG + "ProperShippingName": "PAINT",
    ITEM_DG + "ProperShippingName@languageID": "en",
    ITEM_DG + "TunnelRestrictionCode": "D/E",
    ITEM_DG + "HazardCategoryCode": "3",
    ITEM_DG + "DensityMeasure": "1.3",
    ITEM_DG + "MeltingPointTemperatureMeasure": "-5",
    ITEM_DG + "LabelTypeCode": "3",
    ITEM_DG + "ControlTemperatureMeasurement/ConditionMeasure": "25",
    ITEM_DG + "EmergencyTemperatureMeasurement/ConditionMeasure": "35",
    ITEM_DG + "RadioactiveMaterial/SpecialFormInformation": "Erivormis radioaktiivne aine (ainult väljade kaardistamiseks)",
    ITEM_DG + "RadioactiveMaterial/RadioactivePackageTransportIndexCode": "0.5",
    ITEM_DG + "RadioactiveMaterial/FissileCriticalitySafetyIndexNumeric": "0",
    ITEM_DG + "RadioactiveMaterial/ApplicableSpecifiedRadioactiveIsotope/Name": "Cs-137",
    ITEM_DG + "RadioactiveMaterial/ApplicableSpecifiedRadioactiveIsotope/ActivityLevelMeasure": "0.4",
    ITEM_DG + "SpecifiedLogisticsPackage/ItemQuantity": "40",
    ITEM_DG + "SpecifiedLogisticsPackage/TypeCode": "1A1",
    ITEM_DG + "SpecifiedLogisticsPackage/Type": "Terasvaat",
    ITEM_DG + "SpecifiedLogisticsPackage/PhysicalLogisticsShippingMarks/Marking": "UN 1263, 3, III - Mock Värvitehas AS",
    ITEM_DG + "SpecifiedLogisticsPackage/AssociatedReferencedDocument/URIID": "https://sds.mock-efti.example/1263",
    ITEM_DG + "SpecifiedLogisticsPackage/StatedSpecifiedCondition/Statement": "Pakend vastab ADR 4.1.1 nõuetele",
    ITEM_DG + "StatedSpecifiedCondition/Statement": "Keskkonnaohtlik aine: ei",
    ITEM_DG + "StatedSpecifiedCondition/SpecifiedCalibratedMeasurement/ValueMeasure": "23",
    **attachment(ITEM_DG + "RelatedReferencedDocument/AttachmentBinaryObject", "Ohutuskaart UN 1263 PAINT, klass 3",
                 "ohutuskaart-un1263.txt"),
    ITEM_DG + "IncludedSpecifiedFuel/VolumeMeasure": "0.4",
    ITEM_DG + "IncludedSpecifiedFuel/WeightMeasure": "320",
    ITEM_DG + "IncludedSpecifiedFuel/WorkingPressureMeasure": "1.0",
    ITEM_DG + "TechnicalNameNote/Content": "Värv vesialusel, tuleohtlik",
    ITEM + "LinearSpatialDimension/WidthMeasure": "2.35",
    ITEM + "LinearSpatialDimension/LengthMeasure": "5.90",
    ITEM + "LinearSpatialDimension/HeightMeasure": "1.20",
    ITEM + "AssociatedReferencedLogisticsTransportEquipment/ID": MAX_CONTAINERS[0],

    TRAILER_EQ + "ID": MAX_TRAILER,
    TRAILER_EQ + "GrossVolumeMeasure": "90",
    TRAILER_EQ + "CategoryCode": "TE",
    TRAILER_EQ + "CharacteristicCode": "CHASSIS40",
    TRAILER_EQ + "CharacteristicCode@listAgencyID": "6",
    TRAILER_EQ + "SequenceNumeric": "1",
    TRAILER_EQ + "SealQuantity": "2",
    TRAILER_EQ + "StowagePositionID": "POOLHAAGIS",
    TRAILER_EQ + "GrossGoodsWeightMeasure": "18000",
    TRAILER_EQ + "NetGoodsWeightMeasure": "16800",
    TRAILER_EQ + "GrossGoodsVolumeMeasure": "52.5",
    TRAILER_EQ + "NetGoodsVolumeMeasure": "50.2",
    TRAILER_EQ + "ReportableQuantity": "64",
    "LoadedTransportDangerousGoods/UNDGIdentificationCode": "1263",
    "LoadedTransportDangerousGoods/RegulatoryAuthorityName": "Transpordiamet",
    "LoadedTransportDangerousGoods/HazardClassificationID": "3",
    "LoadedTransportDangerousGoods/ReportableQuantity": "40",
    "LoadedTransportDangerousGoods/SpecifiedLogisticsPackage/ItemQuantity": "40",
    "LoadedTransportDangerousGoods/StatedSpecifiedCondition/ActionCode": "530",
    TRAILER_EQ + "LoadedTransportDangerousGoods/Information": "Poolhaagisel paakseadet ei ole",
    TRAILER_EQ + "LoadedTransportDangerousGoods/SupplementaryInformation": "Fumigeerimist ei ole tehtud",
    TRAILER_EQ + "LoadedTransportDangerousGoods/PreviousCargoInformation": "Eelmine veos: puidugraanulid (mitteohtlik)",
    TRAILER_EQ + "LoadedTransportDangerousGoods/StatedSpecifiedCondition/ValueMeasure": "25",
    TRAILER_EQ + "LoadedTransportDangerousGoods/StatedSpecifiedCondition/Statement": "Poolhaagis kontrollitud enne laadimist",
    TRAILER_EQ + "LoadedTransportDangerousGoods/StatedSpecifiedCondition/SpecifiedCalibratedMeasurement/ValueMeasure": "24",
    TRAILER_EQ + "LoadedTransportDangerousGoods/RelatedReferencedDocument/ID": "MOCK-ADR-CERT-01",
    **attachment(TRAILER_EQ + "LoadedTransportDangerousGoods/RelatedReferencedDocument/AttachmentBinaryObject",
                 "ADR heakskiidutunnistus MOCK-ADR-CERT-01", "adr-tunnistus-MOCK-ADR-CERT-01.txt"),
    TRAILER_EQ + "AffixedLogisticsSeal/ID": "MOCK-SEAL-T-0001",
    TRAILER_EQ + "AffixedLogisticsSeal/SealingPartyRoleCode": "CA",
    CONTAINER_EQ + "ID": MAX_CONTAINERS[0],
    CONTAINER_EQ + "CategoryCode": "CN",
    CONTAINER_EQ + "GrossWeightMeasure": "10600",
    CONTAINER_EQ + "SequenceNumeric": "1",
    CONTAINER_EQ + "VerifiedGrossWeightMeasure": "10620",
    CONTAINER_EQ + "GrossGoodsWeightMeasure": "8400",
    CONTAINER_EQ + "NetGoodsWeightMeasure": "8000",
    CONTAINER_EQ + "GrossGoodsVolumeMeasure": "16",
    CONTAINER_EQ + "NetGoodsVolumeMeasure": "15.2",
    CONTAINER_EQ + "GoodsItemUnitQuantity": "40",
    CONTAINER_EQ + "ReportableQuantity": "40",
    CONTAINER_EQ + "StowagePositionID": "EESMINE",
    CONTAINER_EQ + "LoadedTransportDangerousGoods/Information": "Konteineril paakseadet ei ole",
    CONTAINER_EQ + "LoadedTransportDangerousGoods/SupplementaryInformation": "Konteinerit ei ole fumigeeritud",
    CONTAINER_EQ + "LoadedTransportDangerousGoods/PreviousCargoInformation": "Eelmine veos: kipsplaadid (mitteohtlik)",
    CONTAINER_EQ + "LoadedTransportDangerousGoods/StatedSpecifiedCondition/ValueMeasure": "18",
    CONTAINER_EQ + "LoadedTransportDangerousGoods/StatedSpecifiedCondition/Statement": "Konteiner puhastatud enne laadimist",
    **attachment(CONTAINER_EQ + "LoadedTransportDangerousGoods/RelatedReferencedDocument/AttachmentBinaryObject",
                 "Konteineri pakkimissertifikaat MOCK-CONTAINER-A", "pakkimissertifikaat-MOCK-CONTAINER-A.txt"),
    CONTAINER_EQ + "AffixedLogisticsSeal/ID": "MOCK-SEAL-C-0001",
    CONTAINER_EQ + "AffixedLogisticsSeal/SealingPartyRoleCode": "SH",
    CONTAINER_EQ + "AffixedLogisticsSeal/IssuingTradeParty/Name": "Mock Värvitehas AS",
    CONTAINER_EQ + "WeightVerifierTradeParty/Name": "Mock Kaalukoda OÜ",
    TRAILER_EQ + "OperatingTradeParty/ID": "MOCK-EE-3001",
    TRAILER_EQ + "OperatingTradeParty/Name": "Mock Vedu OÜ",
    TRAILER_EQ + "OwnerTradeParty/ID": "MOCK-EE-5001",
    TRAILER_EQ + "OwnerTradeParty/Name": "Mock Liising AS",
    TRAILER_EQ + "RegistrationTradeCountry/ID": "EE",

    MOVEMENT + "ModeCode": "3",
    MOVEMENT + "ID": "MOCK-TRIP-0914",
    MOVEMENT + "SequenceNumeric": "1",
    MOVEMENT + "MasterResponsibleTransportPerson/ID": "MOCK-DRV-EE-0001",
    MOVEMENT + "MasterResponsibleTransportPerson/GivenName": "Jaan",
    MOVEMENT + "MasterResponsibleTransportPerson/FamilyName": "Tamm",
    MOVEMENT + "LoadingTransportEvent/OccurrenceLogisticsLocation/ID": "EETLL",
    MOVEMENT + "LoadingTransportEvent/OccurrenceLogisticsLocation/Name": "Mock Värvitehase ladu",
    MOVEMENT + "LoadingTransportEvent/CertifyingTradeParty/ID": "MOCK-EE-1001",
    MOVEMENT + "LoadingTransportEvent/CertifyingTradeParty/Name": "Mock Värvitehas AS",
    MOVEMENT + "LoadingTransportEvent/AdditionalSecurityMeasuresApplicableNote/Content": "Plommid kontrollitud laadimisel",
    MOVEMENT + "LoadingTransportEvent/AdditionalSecurityMeasuresApplicableNote/SubjectCode": "AAI",
    MOVEMENT + "UnloadingTransportEvent/OccurrenceLogisticsLocation/ID": "LTKUN",
    MOVEMENT + "UnloadingTransportEvent/OccurrenceLogisticsLocation/Name": "Mock Statyba Kauno sandėlis",
    MOVEMENT + "UnloadingTransportEvent/CertifyingTradeParty/ID": "MOCK-LT-2001",
    MOVEMENT + "UnloadingTransportEvent/CertifyingTradeParty/Name": "Mock Statyba UAB",
    MOVEMENT + "UsedLogisticsTransportMeans/TypeCode": "1522",
    MOVEMENT + "UsedLogisticsTransportMeans/ID": MAX_PLATE,
    MOVEMENT + "UsedLogisticsTransportMeans/ID@schemeAgencyID": "5",
    MOVEMENT + "UsedLogisticsTransportMeans/Name": "Mock Vedu sadulveok koos poolhaagisega",
    MOVEMENT + "UsedLogisticsTransportMeans/RegistrationTradeCountry/ID": "EE",
    MOVEMENT + "UsedLogisticsTransportMeans/OwnerTradeParty/Name": "Mock Liising AS",
    MOVEMENT + "UsedLogisticsTransportMeans/SpecifiedSpatialDimension/Description": "Sadulveok koos poolhaagisega",
    MOVEMENT + "UsedLogisticsTransportMeans/SpecifiedSpatialDimension/WidthMeasure": "255",
    MOVEMENT + "UsedLogisticsTransportMeans/SpecifiedSpatialDimension/LengthMeasure": "1650",
    MOVEMENT + "UsedLogisticsTransportMeans/SpecifiedSpatialDimension/HeightMeasure": "400",
    MOVEMENT + "UsedLogisticsTransportMeans/OperationalAttachedTransportEquipment/ID": MAX_TRAILER,
    MOVEMENT + "UsedLogisticsTransportMeans/OperationalAttachedTransportEquipment/CategoryCode": "TE",
    MOVEMENT + "UsedLogisticsTransportMeans/OperationalAttachedTransportEquipment/UnitQuantity": "1",
    MOVEMENT + "ItineraryTransportRoute/Description": "Tallinn - Pärnu - Ikla/Ainaži - Rīga (ümberlaadimine) - Panevėžys - Kaunas",
    MOVEMENT + "CallTransportEvent/OccurrenceLogisticsLocation/ID": "LVRIX",
    MOVEMENT + "BorderCrossingTransportEvent/OccurrenceLogisticsLocation/Name": "Ikla-Ainaži piiripunkt",
    "PreCarriageLogisticsTransportMovement/ModeCode": "2",
    "OnCarriageLogisticsTransportMovement/ModeCode": "3",
    "OnCarriageLogisticsTransportMovement/UnloadingTransportEvent/OccurrenceLogisticsLocation/ID": "LTVNO",
    "OnCarriageLogisticsTransportMovement/UnloadingTransportEvent/OccurrenceLogisticsLocation/Name": "Mock Vilniaus terminalas",
    "SpecifiedLogisticsTransportMovement/SpecifiedTransportEvent/OccurrenceLogisticsLocation/ID": "LVRIX",
    "SpecifiedLogisticsTransportMovement/SpecifiedTransportEvent/OccurrenceLogisticsLocation/Name": "Mock Rīgas terminālis",
    "TransportService/Description": "Poolhaagise maanteevedu ukselt uksele, ADR-veos",
    "ApplicableLogisticsServiceCharge/ID": "FC",
    "ApplicableLogisticsServiceCharge/AppliedAmount": "1850.00",
    "ApplicableLogisticsServiceCharge/PayingPartyRoleCode": "CZ",
    "ApplicableLogisticsServiceCharge/CalculationBasisCode": "WGT",
    "ApplicableLogisticsServiceCharge/CalculationBasisTradePrice/BasisQuantity": "1000",
    "ApplicableLogisticsServiceCharge/CalculationBasisTradePrice/UnitAmount": "102.78",
    "ApplicableLogisticsServiceCharge/CalculationBasisTradePrice/CategoryTypeCode": "FRT",
    "TransportLogisticsPackage/ItemQuantity": "64",
    "TransportLogisticsPackage/TypeCode": "PX",
    "TransportLogisticsPackage/Type": "Kaubaalus",
    "TransportLogisticsPackage/Information": "40 vaati ja 24 kaubaalust",
    "TransportLogisticsPackage/PhysicalLogisticsShippingMarks/Marking": "MOCK-CMR-MAX / Mock Statyba UAB, Kaunas",
    "AssociatedTradeParty/ID": "MOCK-EE-6001",
    "NatureIdentificationTransportCargo/TypeCode": "DGM",
    "NatureIdentificationTransportCargo/Identification": "Värvid (UN 1263) terasvaatides ja plaatimissegu kottides kaubaalustel",
    "NatureIdentificationTransportCargo/OperationalCategoryCode": "GEN",
    "NatureIdentificationTransportCargo/StatisticalClassificationCode": "08",
    "ApplicableCrossBorderRegulatoryProcedure/ImportCustomsOfficeSpecifiedLogisticsLocation/ID": "LTKUN000",
    "ApplicableCrossBorderRegulatoryProcedure/ExportCustomsOfficeSpecifiedLogisticsLocation/ID": "EETLL000",
    "ApplicableCrossBorderRegulatoryProcedure/TransitCustomsOfficeSpecifiedLogisticsLocation/ID": "LVRIX000",
    TOP + "ApplicableTransportDangerousGoods/Information": "Ohtliku kauba deklaratsioon ADR 5.4.1 kohaselt",
    TOP + "ApplicableTransportDangerousGoods/ExplosiveCargoNetWeightMeasure": "0",
    TOP + "ApplicableTransportDangerousGoods/RadioactiveMaterial/ApplicableSpecifiedRadioactiveIsotope/ActivityLevelMeasure": "0.4",
    TOP + "ApplicableTransportDangerousGoods/StatedSpecifiedCondition/ValueMeasure": "40",
    TOP + "ApplicableTransportDangerousGoods/StatedSpecifiedCondition/Statement": "Veos ületab ADR 1.1.3.6 piirmäära",
    **attachment(TOP + "ApplicableTransportDangerousGoods/RelatedReferencedDocument/AttachmentBinaryObject",
                 "Kirjalikud juhised ADR 5.4.3, eesti ja leedu keeles"),
    TOP + "ApplicableTransportDangerousGoods/ApplicableRegulatoryExemption/ID": "MOCK-ADR-EX-01",
    TOP + "ApplicableTransportDangerousGoods/ApplicableRegulatoryExemption/TypeCode": "113",
    TOP + "ApplicableTransportDangerousGoods/ApplicableRegulatoryExemption/ReportableExemptionCalculation/ReportableQuantity": "8000",
    "ConsignorProvidedBorderClearanceTransportInstructions/Description":
        "Ekspordideklaratsioon esitada Tallinna tolliasutusele; transiit läbi Läti.",
    "SpecifiedLogisticsRiskAnalysisResult/Description": "Riskianalüüs tehtud, kõrvalekaldeid ei tuvastatud",
    "SpecifiedLogisticsRiskAnalysisResult/ScreeningMethodCode": "VCK",
    "SpecifiedLogisticsRiskAnalysisResult/SecurityExemptionCode": "NONE",
    "SpecifiedLogisticsRiskAnalysisResult/Information": "Turvakontroll tehtud laadimisel Tallinnas",
    "NotifiedTransportationWasteMaterial/WeightMeasure": "120.5",
    "NotifiedTransportationWasteMaterial/ID": "080111",
}

# The second consignment item (tile adhesive, not dangerous) goes into the second container.
SECOND_ITEM = {
    "GrossVolumeMeasure": "36.5",
    "GoodsUnitQuantity": "24",
    "LinearSpatialDimension/WidthMeasure": "2.35",
    "LinearSpatialDimension/LengthMeasure": "12.03",
    "LinearSpatialDimension/HeightMeasure": "2.39",
    "AssociatedReferencedLogisticsTransportEquipment/ID": MAX_CONTAINERS[1],
}
SECOND_CONTAINER = {
    "ID": MAX_CONTAINERS[1],
    "GrossWeightMeasure": "13400",
    "SequenceNumeric": "2",
    "VerifiedGrossWeightMeasure": "13380",
    "GrossGoodsWeightMeasure": "9600",
    "NetGoodsWeightMeasure": "8800",
    "GrossGoodsVolumeMeasure": "36.5",
    "NetGoodsVolumeMeasure": "35",
    "GoodsItemUnitQuantity": "24",
    "ReportableQuantity": "24",
    "StowagePositionID": "TAGUMINE",
    "AffixedLogisticsSeal/ID": "MOCK-SEAL-C-0002",
}


def local(element):
    return element.tag.split("}")[-1]


def check(path, type_name, text):
    options, limit, exact = facets(type_name)
    if options and text not in options:
        raise ValueError(f"{path}: {text!r} is not one of {options}")
    if limit is not None and len(text) > limit or exact is not None and len(text) != exact:
        raise ValueError(f"{path}: {text!r} breaks the length limit of {type_name}")


def lookup(path):
    matches = [rule for rule in list(RULES) + list(OPTIONAL) if path.endswith(rule if rule.startswith("@") else "/" + rule)]
    if not matches:
        return None
    rule = max(matches, key=len)
    USED.add(rule)
    return RULES.get(rule, OPTIONAL.get(rule))


USED = set()


def enrich(root):
    """Replace every skeleton placeholder by the story's value. A free-text/identifier/number field without a
    rule is an error (no placeholder can survive); a code field without a rule keeps its schema code."""
    USED.clear()

    def walk(node, path):
        path = path + "/" + local(node)
        info = INFO[node]
        if info["type"]:
            text = lookup(path)
            if local(node) == "DateTimeString":
                node.set("format", "102" if len(text or "") == 8 else "205")
            if text is not None:
                node.text = text
            elif not facets(info["type"])[0]:
                raise ValueError("no fixture value for " + path)
            check(path, info["type"], node.text)
            for name, attr in info["attrs"].items():
                text = attr["fixed"] or lookup(path + "@" + name)
                if text is not None:
                    node.set(name, text)
                elif not facets(attr["type"])[0]:
                    raise ValueError("no fixture value for " + path + "@" + name)
                check(path + "@" + name, attr["type"], node.get(name))
        for child in node:
            walk(child, path)

    walk(root, "")
    unused = set(RULES) - USED
    if unused:
        raise ValueError("fixture rules matching no element: " + ", ".join(sorted(unused)))


def repeat(parent, name, overrides, drop=()):
    """Append a second `name` right after the first one, as an altered copy (it becomes a JSON array)."""
    first = parent.find("{" + PREFIXES["ram"] + "}" + name)
    second = copy.deepcopy(first)
    for original, duplicate in zip(first.iter(), second.iter()):
        INFO[duplicate] = INFO[original]
    parent.insert(list(parent).index(first) + 1, second)
    for relative in drop:
        node = second.find("/".join("{" + PREFIXES["ram"] + "}" + step for step in relative.split("/")))
        second.remove(node)
    for relative, text in overrides.items():
        node = second.find("/".join("{" + PREFIXES["ram"] + "}" + step for step in relative.split("/")))
        check(name + "/" + relative, INFO[node]["type"], text)
        node.text = text


def build():
    """The full-field fixture as an element tree (INFO then describes each of its elements)."""
    root = skeleton()
    enrich(root)
    ram = "{" + PREFIXES["ram"] + "}"
    repeat(root, "IncludedSupplyChainConsignmentItem", SECOND_ITEM, drop=("ApplicableTransportDangerousGoods",))
    repeat(root.find(ram + "UtilizedLogisticsTransportEquipment"), "CarriedAssociatedTransportEquipment",
           SECOND_CONTAINER, drop=("LoadedTransportDangerousGoods",))
    return root


def generate():
    root = build()
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


CATALOGUE_HEADER = """<!-- Generated by scripts/sync-mock-datasets.py from scripts/generate-rich-mock-dataset.py. Do not edit. -->
# FTI010 väljakataloog (rikkalik arendaja-näidis)

Iga rida on üks väli, mille arendaja-mock ja dev-gate'i mock-platvorm tagastavad UIL-i
`EU-EE/mock/550e8400-e29b-41d4-a716-446655440002` päringule (`POST /developer/v1/dataset`,
`POST /xroad/provide/v1/dataset`). Kataloog genereeritakse samast generaatorist kui vastus ise ja
`tests/mock/dataset.py` kontrollib, et see on ajakohane.

- **XPath** — asukoht vastuse `xml` väljas. Juurelement on prefiksita, ülejäänud elemendid on
  nimeruumis `urn:eu:move:eFTI:data:standard:ReusableAggregateBusinessInformationEntity:34`
  (vaikimisi nimeruum); `DateTimeString` on `udt:` nimeruumis. Atribuut on `/@nimi`.
- **`consignment` võti** — sama väli vastuse `consignment` objektis (xml-mapperi reeglid: esimene
  täht väike, iga `id` → `Id`; atribuut on naabervõti `element@atribuut`). `[]` tähendab, et
  element kordub ja muutub massiiviks — näidises on kaks `IncludedSupplyChainConsignmentItem`'it ja
  kaks `CarriedAssociatedTransportEquipment`'i.
- **eFTI ID / Äriline nimi / Koodiloend** — XSD `xs:documentation` väljad `eFTI_ID`, `eFTI_BN`,
  `eFTI_CL`. `DateTimeString` pärib need ümbritsevalt kuupäevaelemendilt.
- **Näide** — väärtus näidises (korduva elemendi puhul esimese esinemise väärtus).

Näidis on väljamõeldud: ettevõtted on „Mock …” nimelised, e-posti domeenid `.example` ja kõik
registrikoodi-laadsed tunnused algavad `MOCK-`. Radioaktiivse materjali ja jäätmete väljad on
täidetud ainult kaardistamiseks.

| # | XPath | `consignment` võti | eFTI ID | Äriline nimi | Koodiloend | Näide |
|---|---|---|---|---|---|---|
"""


def catalogue():
    """The field catalogue (docs/developer/fti010-field-catalogue.md) of the full-field fixture."""
    root = build()
    rows, seen = [], set()

    def cell(text):
        return (text or "").replace("|", "\\|")

    def row(xpath, key, doc, example):
        if xpath not in seen:
            seen.add(xpath)
            rows.append(f"| {len(rows) + 1} | `{xpath}` | `{key}` | {cell(doc.get('ID'))} | {cell(doc.get('BN'))} "
                        f"| {cell(doc.get('CL'))} | {cell(example)} |")

    def walk(node, xpath, key, inherited):
        info = INFO[node]
        doc = info["doc"] if info["doc"].get("ID") or info["doc"].get("BN") else inherited
        if info["type"]:
            row(xpath, key, doc, node.text)
            for name, attr in info["attrs"].items():
                row(xpath + "/@" + name, key + "@" + json_name(name), attr["doc"], node.get(name))
        names = [local(child) for child in node]
        for child in node:
            step = json_name(child.tag) + ("[]" if names.count(local(child)) > 1 else "")
            walk(child, xpath + "/" + local(child), key + "." + step if key else step, doc)

    walk(root, "/" + local(root), "", {})
    return CATALOGUE_HEADER + "\n".join(rows) + "\n"


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
