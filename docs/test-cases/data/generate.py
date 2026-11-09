#!/usr/bin/env python3
"""Writes the Liquibase test-data changeset DSL/Liquibase/test/test-consignments.sql.

    python3 docs/test-cases/data/generate.py

Edit CASES, regenerate, commit the SQL, and update the expected answers in docs/test-cases/*.md.
"""
import pathlib

GATE = "EU-EE"
PLATFORM = "mock"

# datasetId -> criteria. Keep the id suffix = case number.
CASES = {
    "11111111-0000-4000-8000-000000000001": dict(  # road, EE plate, EE -> LV, dangerous goods
        plate="TST-001", mode="3", type_code="31", reg="EE",
        accept=("20261101", "EE"), delivery=("20261102", "LV"),
        loading=("20261101", "EE"), unloading=("20261102", "LV"), dangerous="1"),
    "11111111-0000-4000-8000-000000000002": dict(  # road, DE plate with a trailer container
        plate="TST-002", mode="3", type_code="31", reg="DE",
        accept=("20261101", "DE"), delivery=("20261103", "EE"),
        loading=("20261101", "DE"), unloading=("20261103", "EE"), dangerous="0",
        used=["CONT-100"], used_cat="T10", used_reg="DE"),
    "11111111-0000-4000-8000-000000000003": dict(  # sea, FI -> EE
        plate="IMO9321483", mode="1", type_code="1513", reg="FI",
        accept=("20261030", "FI"), delivery=("20261101", "EE"),
        loading=("20261030", "FI"), unloading=("20261101", "EE"), dangerous="0",
        carried=["CONT-200", "CONT-201"], carried_cat="BPR"),
    "11111111-0000-4000-8000-000000000004": dict(  # air, SE -> EE
        plate="OH-LKA", mode="4", type_code="1100", reg="FI",
        accept=("20261101", "SE"), delivery=("20261101", "EE"),
        loading=("20261101", "SE"), unloading=("20261101", "EE"), dangerous="0"),
    "11111111-0000-4000-8000-000000000005": dict(  # road, same plate as 001 (second dataset, other trip)
        plate="TST-001", mode="3", type_code="31", reg="EE",
        accept=("20261103", "EE"), delivery=("20261103", "FI"),
        loading=("20261103", "EE"), unloading=("20261103", "FI"), dangerous="0"),
    "11111111-0000-4000-8000-000000000006": dict(  # rail, LV -> EE
        plate="WAGON-77", mode="2", type_code="2300", reg="LV",
        accept=("20261029", "LV"), delivery=("20261030", "EE"),
        loading=("20261029", "LV"), unloading=("20261030", "EE"), dangerous="0"),
}


def iso6346(owner_cat_serial):
    """Appends the ISO 6346 check digit to a 10-character container id (4 letters + 6 digits)."""
    letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    values, v = [], 10
    for ch in letters:
        if v % 11 == 0:
            v += 1
        values.append(v)
        v += 1
    table = dict(zip(letters, values))
    total = sum((table[c] if c.isalpha() else int(c)) * (2 ** i) for i, c in enumerate(owner_cat_serial))
    return f"{owner_cat_serial}{total % 11 % 10}"


# Second changeset (test-consignments-2): the registration plates the testers query by hand. Trucks are
# the main transport means, trailers are used equipment (a plate lookup finds either). Acceptance dates
# are 2026-11-05..09 so the road rows outlive the 14-day expiry job until 2026-11-19.
T = "T10"
CASES_2 = {
    "11111111-0000-4000-8000-000000000007": dict(  # 888UUU + O2 trailer, EE -> LV, explosives
        plate="888UUU", mode="3", type_code="31", reg="EE",
        accept=("20261105", "EE"), delivery=("20261106", "LV"),
        loading=("20261105", "EE"), unloading=("20261106", "LV"), dangerous="1",
        used=[("735DF", T, "EE")]),
    "11111111-0000-4000-8000-000000000008": dict(  # 031AVH + O2 trailer, two containers, EE -> FI
        plate="031AVH", mode="3", type_code="31", reg="EE",
        accept=("20261105", "EE"), delivery=("20261107", "FI"),
        loading=("20261105", "EE"), unloading=("20261107", "FI"), dangerous="0",
        used=[("300DK", T, "EE")],
        carried=[iso6346("MSKU123456"), iso6346("TGHU765432")], carried_cat="BPR"),
    "11111111-0000-4000-8000-000000000009": dict(  # 963APY + O4 trailer, DE -> EE, flammable liquids
        plate="963APY", mode="3", type_code="31", reg="EE",
        accept=("20261106", "DE"), delivery=("20261108", "EE"),
        loading=("20261106", "DE"), unloading=("20261108", "EE"), dangerous="3",
        used=[("060TP", T, "EE")]),
    "11111111-0000-4000-8000-000000000010": dict(  # 244AEH + light trailer, EE -> LT, nothing special
        plate="244AEH", mode="3", type_code="31", reg="EE",
        accept=("20261106", "EE"), delivery=("20261106", "LT"),
        loading=("20261106", "EE"), unloading=("20261106", "LT"), dangerous="0",
        used=[("337YLN", T, "EE")]),
    "11111111-0000-4000-8000-000000000011": dict(  # 587AXF + O4 trailer, three containers, EE -> PL
        plate="587AXF", mode="3", type_code="31", reg="EE",
        accept=("20261107", "EE"), delivery=("20261109", "PL"),
        loading=("20261107", "EE"), unloading=("20261109", "PL"), dangerous="0",
        used=[("055YHY", T, "EE")],
        carried=[iso6346("MAEU456789"), iso6346("CMAU234567"), iso6346("HLCU987654")], carried_cat="BPR"),
    "11111111-0000-4000-8000-000000000012": dict(  # 587AXF again, tipper trailer, domestic
        plate="587AXF", mode="3", type_code="31", reg="EE",
        accept=("20261108", "EE"), delivery=("20261108", "EE"),
        loading=("20261108", "EE"), unloading=("20261108", "EE"), dangerous="0",
        used=[("611YHZ", T, "EE")]),
    "11111111-0000-4000-8000-000000000013": dict(  # 031AVH again with an O3 trailer, LV -> EE, corrosives
        plate="031AVH", mode="3", type_code="31", reg="EE",
        accept=("20261108", "LV"), delivery=("20261109", "EE"),
        loading=("20261108", "LV"), unloading=("20261109", "EE"), dangerous="8",
        used=[("793YKT", T, "EE")]),
    "11111111-0000-4000-8000-000000000014": dict(  # bus 557SCG with a luggage trailer, EE -> LV
        plate="557SCG", mode="3", type_code="31", reg="EE",
        accept=("20261109", "EE"), delivery=("20261109", "LV"),
        loading=("20261109", "EE"), unloading=("20261109", "LV"), dangerous="0",
        used=[("442DC", T, "EE")]),
}


def arr(values, cast="text"):
    if not values:
        return "NULL"
    items = ", ".join(f"'{v}'" if cast == "text" else str(v) for v in values)
    return f"ARRAY[{items}]::{cast}[]" if cast == "text" else f"ARRAY[{items}]::integer[]"


def ts(day):
    return f"'{day[:4]}-{day[4:6]}-{day[6:]}T00:00:00Z'"


def row(uid, k):
    used = [u if isinstance(u, tuple) else (u, k["used_cat"], k["used_reg"]) for u in k.get("used", [])]
    carried = k.get("carried", [])
    xml = f"<ParameterIDSetCriteria><MainCarriageTransportMeansIDParameterScope><ID>{k['plate']}</ID></MainCarriageTransportMeansIDParameterScope></ParameterIDSetCriteria>"
    return f"""  ('{uid}', '{PLATFORM}', '{GATE}', '{xml}', 'ACTIVE', '{k['mode']}',
   {ts(k['accept'][0])}, '{k['accept'][1]}', {ts(k['delivery'][0])}, '{k['delivery'][1]}', '{k['dangerous']}',
   '{k['plate']}', '{k['type_code']}', '{k['reg']}',
   {ts(k['loading'][0])}, '{k['loading'][1]}', {ts(k['unloading'][0])}, '{k['unloading'][1]}',
   {arr([u[0] for u in used])}, {arr([u[1] for u in used]) if used else 'NULL'}, {arr([u[2] for u in used]) if used else 'NULL'}, {arr(list(range(1, len(used) + 1)), 'int') if used else 'NULL'},
   {arr(carried)}, {arr([k['carried_cat']] * len(carried)) if carried else 'NULL'}, {arr(list(range(1, len(carried) + 1)), 'int') if carried else 'NULL'})"""


COLUMNS = """INSERT INTO consignments (
  dataset_id, platform_id, gate_id, xml, status, transport_mode,
  acceptance_date, acceptance_country, delivery_date, delivery_country, dangerous_goods,
  main_transport_id, main_transport_type, transport_reg_country,
  loading_date, loading_country, unloading_date, unloading_country,
  used_equipment_ids, used_equipment_categories, used_equipment_countries, used_equipment_seq,
  carried_equipment_ids, carried_equipment_categories, carried_equipment_seq
) VALUES"""


def changeset(name, cases, note):
    ids = ", ".join(f"'{u}'" for u in cases)
    rows = ",\n".join(row(u, k) for u, k in cases.items())
    return f"""--changeset efti:{name}
{note}{COLUMNS}
{rows};

-- Append-only: undoing is a newer INACTIVE row per dataset, not a DELETE.
--rollback INSERT INTO consignments (dataset_id, platform_id, gate_id, xml, status) SELECT dataset_id, platform_id, gate_id, xml, 'INACTIVE' FROM consignments WHERE dataset_id IN ({ids});
"""


if __name__ == "__main__":
    out = pathlib.Path(__file__).resolve().parents[3] / "DSL/Liquibase/test/test-consignments.sql"
    out.write_text("""--liquibase formatted sql

-- Generated by docs/test-cases/data/generate.py - do not edit by hand.
-- Test consignments for docs/test-cases/. NOT part of the default run: db.changelog-master.yaml does
-- not include this file, only test/db.changelog-test.yaml does.
-- Rows and queries: docs/test-cases/data/README.md.

""" + changeset("test-consignments-1", CASES, "")
        + "\n" + changeset("test-consignments-2", CASES_2, "-- Registration plates for manual lookups: trucks with trailers, containers, dangerous goods.\n"))
    print(f"wrote {out}")
