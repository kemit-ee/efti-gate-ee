"""Run the real ReSQL files against a disposable PostgreSQL 18 container.

No published ports or persistent volumes are used. Run from the repository root:
    python3 tests/sql/regression.py
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import csv
import io
import json
import re
import subprocess
import sys
import time
import unittest
import uuid
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
CONTAINER = "efti-guard-sql-tests-" + uuid.uuid4().hex[:12]
USER_ID = "10000000-0000-0000-0000-000000000001"
DATASET_ID = "20000000-0000-0000-0000-000000000001"


def truncate_registries():
    sql("TRUNCATE users, consignments, follow_up_log, audit_log, async_responses, inbound_messages, search_results, rm_consignment_counts, rm_consignment_summary, read_model_pointer;")


def docker(*args, **kwargs):
    return subprocess.run(["docker", *args], check=True, text=True, capture_output=True, **kwargs).stdout


def sql(statement, csv_output=False):
    try:
        return docker("exec", "-i", CONTAINER, "psql", "-h", "127.0.0.1", "-U", "postgres", "-q",
                      "--csv" if csv_output else "-At", "-v", "ON_ERROR_STOP=1", input=statement)
    except subprocess.CalledProcessError as error:
        raise RuntimeError(error.stderr) from error


def declared_params(source):
    """The endpoint's `params` mapping from its YAML header.

    The description is free-form prose and not necessarily valid YAML on its own (a multi-line
    scalar may contain ': '), so only the params mapping is parsed."""
    header = re.search(r"/\*(.*?)\*/", source, re.S).group(1)
    if "\nparams:" not in header:
        return {}
    return (yaml.safe_load("params:" + header.split("\nparams:", 1)[1]) or {}).get("params") or {}


def endpoint(name, params, source=None):
    if source is None:
        source = (ROOT / "DSL/Resql/efti/POST" / (name + ".sql")).read_text()
    header = re.search(r"/\*(.*?)\*/", source, re.S)
    declarations = declared_params(source)
    names = list(declarations)
    scalar_types = {"number": "bigint", "integer": "bigint", "object": "jsonb", "uuid": "uuid", "datetime": "timestamptz", "boolean": "boolean"}
    types = []
    for declaration in declarations.values():
        if declaration["type"] == "array":
            types.append(scalar_types.get(declaration["items"]["type"], "text") + "[]")
        else:
            types.append(scalar_types.get(declaration["type"], "text"))
    query = source[header.end():].strip().rstrip(";")
    query = re.sub(r"'(?:''|[^'])*'|--[^\n]*|/\*.*?\*/", lambda m: "" if m.group().startswith(("--", "/*")) else m.group(), query, flags=re.S)
    query = re.sub(r"'(?:''|[^'])*'|(?<!:):([A-Za-z_]\w*)", lambda m: "$" + str(names.index(m.group(1)) + 1) if m.group(1) else m.group(), query)
    values = []
    for name in names:
        value = params.get(name, declarations[name].get("default"))
        if value is None:
            values.append("NULL")
        elif isinstance(value, (int, float)):
            values.append(str(value))
        else:
            if isinstance(value, list) and declarations[name]["type"] == "array":
                value = "{" + ",".join(str(v) for v in value) + "}"
            elif isinstance(value, (dict, list)):
                value = json.dumps(value)
            values.append("'" + str(value).replace("'", "''") + "'")
    signature = "(" + ",".join(types) + ")" if names else ""
    arguments = "(" + ",".join(values) + ")" if names else ""
    return query, signature, arguments


def call(endpoint_name, **params):
    query, signature, arguments = endpoint(endpoint_name, params)
    result = sql("SET ROLE app; PREPARE q" + signature + " AS " + query + "; EXECUTE q" + arguments + ";", csv_output=True)
    def value(text):
        if text == "t":
            return True
        if text == "f":
            return False
        if text == "":
            return None
        try:
            return json.loads(text)
        except ValueError:
            return text
    return [{key: value(text) for key, text in row.items()} for row in csv.DictReader(io.StringIO(result))]


class Queries(unittest.TestCase):
    def setUp(self):
        truncate_registries()

    def user(self, tara="old", active=True, stamp="2026-09-01", revoked=None):
        revoked_sql = "NULL" if revoked is None else "'" + revoked + "'"
        sql(f"INSERT INTO users (id,tara_sub,name,secret_hash,is_active,created_at,token_revoked_at) "
            f"VALUES ('{USER_ID}','{tara}','Test','preserved-secret',{str(active).lower()},'{stamp}',{revoked_sql});")

    def consignment(self, identifier="MATCH", status="ACTIVE", stamp="2026-09-01", platform="mock", equipment="{}", country="EE"):
        sql(f"INSERT INTO consignments (dataset_id,platform_id,gate_id,xml,status,main_transport_id,used_equipment_ids,transport_reg_country,created_at) "
            f"VALUES ('{DATASET_ID}','{platform}','EU-EE','<criteria/>','{status}','{identifier}','{equipment}','{country}','{stamp}');")

    def refresh_counts(self):
        sql("SET ROLE app; " + (ROOT / "DSL/Resql/efti/POST/refresh_consignment_counts.sql").read_text().split("*/", 1)[1])

    def counts(self):
        return {(row["gate_id"], row["platform_id"], row["status"]): row["consignment_count"] for row in call("get_consignment_counts")}

    def test_read_model_counts_only_current_rows(self):
        self.consignment(identifier="A", stamp="2026-09-01")
        self.consignment(identifier="A", status="INACTIVE", stamp="2026-09-02")
        self.assertEqual([], call("get_consignment_counts"))
        self.refresh_counts()
        self.assertEqual({("EU-EE", "mock", "INACTIVE"): 1}, self.counts())

    def test_read_model_excludes_deleted_datasets(self):
        self.consignment(stamp="2026-09-01")
        self.consignment(status="DELETED", stamp="2026-09-02")
        self.refresh_counts()
        self.assertEqual({}, self.counts())

    def test_read_model_serves_new_generation_only_after_refresh(self):
        self.consignment(stamp="2026-09-01")
        self.refresh_counts()
        sql(f"INSERT INTO consignments (dataset_id,platform_id,gate_id,xml,status,created_at) "
            f"VALUES ('{uuid.uuid4()}','mock','EU-EE','<x/>','ACTIVE','2026-09-03');")
        self.assertEqual({("EU-EE", "mock", "ACTIVE"): 1}, self.counts())
        self.refresh_counts()
        self.assertEqual({("EU-EE", "mock", "ACTIVE"): 2}, self.counts())
        self.assertEqual(1, len({row["generation"] for row in call("get_consignment_counts")}))

    def test_read_model_purge_keeps_current_generation(self):
        self.consignment()
        for _ in range(4):
            self.refresh_counts()
        purge = (ROOT / "DSL/Resql/efti/POST/purge_read_model_generations.sql").read_text()
        query, signature, arguments = endpoint("purge_read_model_generations", {"keepGenerations": 1}, purge)
        sql("RESET ROLE; PREPARE q" + signature + " AS " + query + "; EXECUTE q" + arguments + ";")
        self.assertEqual("1", sql("SELECT count(DISTINCT generation) FROM rm_consignment_counts;").strip())
        self.assertEqual("1", sql("SELECT count(*) FROM read_model_pointer;").strip())
        self.assertEqual({("EU-EE", "mock", "ACTIVE"): 1}, self.counts())

    def test_read_model_app_role_cannot_update_or_delete(self):
        self.consignment()
        self.refresh_counts()
        for statement in ("UPDATE rm_consignment_counts SET consignment_count = 0;", "DELETE FROM rm_consignment_counts;",
                          "UPDATE read_model_pointer SET generation = 0;", "DELETE FROM read_model_pointer;"):
            with self.assertRaisesRegex(RuntimeError, "permission denied"):
                sql("SET ROLE app; " + statement)

    def test_history_is_paginated_and_omits_xml(self):
        for day in range(1, 6):
            self.consignment(stamp="2026-09-0" + str(day))
        page = call("get_consignment_history", datasetId=DATASET_ID, limit=2, offset=1)
        self.assertEqual(2, len(page))
        self.assertTrue(all("xml" not in row for row in page))
        self.assertEqual(["2026-09-04", "2026-09-03"], [row["created_at"][:10] for row in page])
        self.assertEqual(5, len(call("get_consignment_history", datasetId=DATASET_ID)))
        self.assertEqual(0, len(call("get_consignment_history", datasetId=DATASET_ID, limit=-5, offset=-5)))

    def refresh_summary(self):
        sql("SET ROLE app; " + (ROOT / "DSL/Resql/efti/POST/refresh_consignment_summary.sql").read_text().split("*/", 1)[1])

    def summary_consignment(self, stamp, dataset=None, status="ACTIVE", loading="EE", dangerous="1", mode="R"):
        sql(f"INSERT INTO consignments (dataset_id,platform_id,gate_id,xml,status,loading_country,dangerous_goods,transport_mode,created_at) "
            f"VALUES ('{dataset or uuid.uuid4()}','mock','EU-EE','<x/>','{status}','{loading}','{dangerous}','{mode}','{stamp}');")

    def summary(self, subsets, **params):
        return {(row["subset"] or "", row["dimension"], "" if row["dim_value"] is None else str(row["dim_value"])): row["consignment_count"]
                for row in call("get_consignment_summary", subsets=subsets, **params)}

    def test_summary_only_returns_dimensions_of_the_authoritys_subsets(self):
        self.summary_consignment("2026-09-01")
        self.refresh_summary()
        self.assertEqual({("", "total", ""): 1}, self.summary([]))
        eu03 = self.summary(["EU03"])
        self.assertEqual({("", "total", ""): 1, ("EU03", "loading_country", "EE"): 1, ("EU03", "unloading_country", ""): 1}, eu03)
        everything = self.summary(["EU02", "EU03", "EU04"])
        self.assertEqual(1, everything[("EU02", "dangerous_goods", "1")])
        self.assertEqual(1, everything[("EU04", "transport_mode", "R")])
        self.assertTrue(all(key[0] in ("", "EU02", "EU03", "EU04") for key in everything))

    def test_summary_uses_manual_day_range_inclusively(self):
        for day in ("2026-09-01", "2026-09-05", "2026-09-10"):
            self.summary_consignment(day)
        self.refresh_summary()
        self.assertEqual(3, self.summary([])[("", "total", "")])
        self.assertEqual(2, self.summary([], **{"from": "2026-09-05"})[("", "total", "")])
        self.assertEqual(1, self.summary([], **{"from": "2026-09-05", "to": "2026-09-05"})[("", "total", "")])
        self.assertEqual({}, self.summary([], **{"from": "2026-10-01"}))

    def test_summary_counts_current_active_versions_only(self):
        dataset = uuid.uuid4()
        self.summary_consignment("2026-09-01", dataset=dataset)
        self.summary_consignment("2026-09-02", dataset=dataset, loading="FI")
        self.summary_consignment("2026-09-02", status="INACTIVE")
        gone = uuid.uuid4()
        self.summary_consignment("2026-09-01", dataset=gone)
        self.summary_consignment("2026-09-03", dataset=gone, status="DELETED")
        self.refresh_summary()
        counts = self.summary(["EU03"])
        self.assertEqual(1, counts[("", "total", "")])
        self.assertEqual(1, counts[("EU03", "loading_country", "FI")])
        self.assertNotIn(("EU03", "loading_country", "EE"), counts)

    def test_summary_is_empty_before_first_refresh_and_bounded(self):
        self.summary_consignment("2026-09-01")
        self.assertEqual({}, self.summary(["EU02", "EU03", "EU04"]))
        self.refresh_summary()
        self.assertEqual(1, len(call("get_consignment_summary", subsets=["EU02", "EU03", "EU04"], limit=1)))
        self.assertEqual([], call("get_consignment_summary", subsets=[], limit=-1, offset=-1))

    def test_all_endpoint_files_prepare_under_app_role(self):
        for path in sorted((ROOT / "DSL/Resql/efti/POST").glob("*.sql")):
            with self.subTest(endpoint=path.stem):
                query, signature, _ = endpoint(path.stem, {})
                sql("SET ROLE app; PREPARE q" + signature + " AS " + query + ";")

    def test_deleted_user_cannot_authenticate(self):
        self.user()
        self.user(active=False, stamp="2026-09-02")
        self.assertEqual([], call("check_user_auth", tara_sub="old", token_issued_at="2026-09-03"))
        self.assertEqual([], call("check_tara_sub_exists", taraSub="old"))

    def test_changed_identity_does_not_resolve_old_tara_sub(self):
        self.user()
        self.user(tara="new", stamp="2026-09-02")
        for name, params in [("check_user_auth", {"tara_sub": "old", "token_issued_at": "2026-09-03"}),
                             ("get_user_by_tara_sub", {"tara_sub": "old"}), ("check_tara_sub_exists", {"taraSub": "old"})]:
            with self.subTest(endpoint=name):
                self.assertEqual([], call(name, **params))
        self.assertEqual("new", call("get_user_by_tara_sub", tara_sub="new")[0]["tara_sub"])

    def test_user_update_preserves_revocation_and_password(self):
        self.user(revoked="2026-09-02")
        row = call("update_user", id=USER_ID, taraSub="old", name="Renamed")[0]
        self.assertIsNotNone(row["token_revoked_at"])
        self.assertEqual([], call("check_user_auth", tara_sub="old", token_issued_at="2026-09-01"))
        self.assertEqual("preserved-secret", sql(f"SELECT secret_hash FROM users WHERE id='{USER_ID}' ORDER BY created_at DESC, row_id DESC LIMIT 1;").strip())

    def test_updating_inactive_user_does_not_reactivate(self):
        self.user(active=False)
        self.assertFalse(call("update_user", id=USER_ID, taraSub="old", name="Renamed")[0]["is_user_active"])

    def test_revoking_password_user_preserves_password(self):
        self.user()
        call("revoke_user_token", userId=USER_ID)
        self.assertEqual("preserved-secret", sql(f"SELECT secret_hash FROM users WHERE id='{USER_ID}' ORDER BY created_at DESC, row_id DESC LIMIT 1;").strip())

    def test_identifier_lookup_filters_latest_row_not_history(self):
        self.consignment()
        self.consignment(identifier="CORRECTED", stamp="2026-09-02")
        self.assertFalse(call("check_transport_means_registered", transport_means_id="MATCH")[0]["registered"])
        self.assertEqual([], call("get_consignments_by_transport_means", transport_means_id="MATCH"))
        self.assertTrue(call("check_transport_means_registered", transport_means_id="CORRECTED")[0]["registered"])

    def test_identifier_lookup_excludes_deleted_and_inactive(self):
        for status in ["DELETED", "INACTIVE"]:
            with self.subTest(status=status):
                sql("TRUNCATE consignments;")
                self.consignment()
                self.consignment(status=status, stamp="2026-09-02")
                self.assertFalse(call("check_transport_means_registered", transport_means_id="MATCH")[0]["registered"])
                self.assertEqual([], call("get_consignments_by_transport_means", transport_means_id="MATCH"))

    def test_equipment_lookup_and_country_filter(self):
        self.consignment(identifier="OTHER", equipment="{CONTAINER}")
        self.assertTrue(call("check_transport_means_registered", transport_means_id="CONTAINER", country_code="")[0]["registered"])
        self.assertFalse(call("check_transport_means_registered", transport_means_id="CONTAINER", country_code="FI")[0]["registered"])
        self.assertEqual(1, len(call("get_consignments_by_transport_means", transport_means_id="CONTAINER")))

    def test_equipment_not_equal_means_value_absent(self):
        self.consignment(equipment="{A,B}")
        self.assertEqual([], call("get_consignments", criteria={"usedEquipmentId": {"operator": "NE", "id": "A"}}))
        self.assertEqual(1, len(call("get_consignments", criteria={"usedEquipmentId": {"operator": "EQ", "id": "A"}})))
        self.assertEqual(1, len(call("get_consignments", criteria={"usedEquipmentId": {"operator": "NE", "id": "C"}})))

    def test_consignment_verification_uses_platform_identity(self):
        self.consignment(platform="one")
        self.consignment(platform="two", stamp="2026-09-02")
        rows = call("get_consignment_by_id", datasetId=DATASET_ID, platformId="one", gateId="EU-EE")
        self.assertEqual(["one"], [r["platform_id"] for r in rows])

    def test_deleting_one_platform_does_not_delete_another(self):
        self.consignment(platform="one")
        self.consignment(platform="two", stamp="2026-09-02")
        self.assertEqual(1, len(call("soft_delete_consignment", datasetId=DATASET_ID, platformId="one", gateId="EU-EE")))
        rows = call("get_consignment_by_id", datasetId=DATASET_ID, platformId="two", gateId="EU-EE")
        self.assertEqual("ACTIVE", rows[0]["status"])

    def test_old_gate_does_not_resolve_after_uil_gate_changes(self):
        self.consignment()
        sql(f"INSERT INTO consignments (dataset_id,platform_id,gate_id,xml,created_at) VALUES ('{DATASET_ID}','mock','EU-FI','<criteria/>','2026-09-02');")
        self.assertEqual([], call("get_consignment_xml", datasetId=DATASET_ID, platformId="mock", gateId="EU-EE"))

    def test_all_seven_equipment_filters_handle_membership_and_null(self):
        fields = [("usedEquipmentId", "id", "A", "C"), ("usedEquipmentCategory", "code", "A", "C"),
                  ("usedEquipmentCountry", "country", "EE", "DE"), ("usedEquipmentSeq", "sequence", 1, 3),
                  ("carriedEquipmentId", "id", "A", "C"), ("carriedEquipmentCategory", "code", "A", "C"),
                  ("carriedEquipmentSeq", "sequence", 1, 3)]
        sql(f"INSERT INTO consignments (dataset_id,platform_id,gate_id,xml,used_equipment_ids,used_equipment_categories,used_equipment_countries,used_equipment_seq,carried_equipment_ids,carried_equipment_categories,carried_equipment_seq) "
            f"VALUES ('{DATASET_ID}','mock','EU-EE','<criteria/>','{{A,B}}','{{A,B}}','{{EE,FI}}','{{1,2}}','{{A,B}}','{{A,B}}','{{1,2}}');")
        for field, key, present, absent in fields:
            with self.subTest(field=field):
                self.assertEqual(1, len(call("get_consignments", criteria={field: {"operator": "EQ", key: present}})))
                self.assertEqual([], call("get_consignments", criteria={field: {"operator": "NE", key: present}}))
                self.assertEqual(1, len(call("get_consignments", criteria={field: {"operator": "NE", key: absent}})))
        sql("TRUNCATE consignments;")
        self.consignment()
        for field, key, present, _ in fields:
            with self.subTest(null_field=field):
                self.assertEqual(1, len(call("get_consignments", criteria={field: {"operator": "NE", key: present}})))

    def test_pagination_is_bounded_and_nonnegative(self):
        sql("INSERT INTO consignments (dataset_id,platform_id,gate_id,xml) SELECT md5(n::text)::uuid,'mock','EU-EE','<criteria/>' FROM generate_series(1,1005) n;")
        self.assertEqual(1000, len(call("get_consignments", criteria={}, limit=100000)))
        self.assertEqual([], call("get_consignments", criteria={}, limit=-1))
        self.assertEqual(call("get_consignments", criteria={}, limit=1, offset=0), call("get_consignments", criteria={}, limit=1, offset=-1))

    def test_identity_change_revokes_tokens_issued_before_change(self):
        self.user()
        row = call("update_user", id=USER_ID, taraSub="new", name="Changed identity")[0]
        self.assertIsNotNone(row["token_revoked_at"])
        self.assertEqual([], call("check_user_auth", tara_sub="new", token_issued_at="2026-09-01"))

    def test_async_response_is_claimed_exactly_once(self):
        key = "EU-PEER:30000000-0000-0000-0000-000000000001:EU-EE"
        self.assertEqual([], call("claim_async_response", requestKey=key))
        call("insert_async_response", requestKey=key, body="<FTI010/>")
        self.assertEqual([{"body": "<FTI010/>"}], call("claim_async_response", requestKey=key))
        self.assertEqual([], call("claim_async_response", requestKey=key))

    def test_async_response_claims_are_per_request_key(self):
        one, two = "EU-PEER:30000000-0000-0000-0000-000000000001:EU-EE", "EU-PEER:30000000-0000-0000-0000-000000000002:EU-EE"
        call("insert_async_response", requestKey=one, body="one")
        call("insert_async_response", requestKey=two, body="two")
        self.assertEqual([{"body": "two"}], call("claim_async_response", requestKey=two))
        self.assertEqual([{"body": "one"}], call("claim_async_response", requestKey=one))

    def test_async_response_concurrent_claims_have_one_winner(self):
        key = "EU-PEER:30000000-0000-0000-0000-000000000003:EU-EE"
        call("insert_async_response", requestKey=key, body="<once/>")
        with ThreadPoolExecutor(max_workers=8) as pool:
            results = list(pool.map(lambda _: call("claim_async_response", requestKey=key), range(8)))
        self.assertEqual(1, sum(1 for rows in results if rows))

    def test_expired_async_responses_are_purged_by_archiver_role(self):
        sql("INSERT INTO async_responses (request_key, body, created_at) VALUES ('old', 'x', now() - interval '1 hour'), ('fresh', 'y', now());")
        query, signature, arguments = endpoint("delete_expired_async_responses", {"keepMinutes": 10})
        out = sql("SET ROLE db_archiver; PREPARE q" + signature + " AS " + query + "; EXECUTE q" + arguments + ";").strip()
        self.assertEqual("1", out)
        self.assertEqual("fresh", sql("SELECT request_key FROM async_responses;").strip())

    def test_inbound_message_is_recorded_once_per_verified_sender_and_message_id(self):
        first = call("insert_inbound_message", senderId="EU-DE", messageId="m-1@eu-de", payloadSha256="a" * 64, receiptB64="UkVDMQ==")
        self.assertEqual(1, len(first))
        self.assertEqual([], call("insert_inbound_message", senderId="EU-DE", messageId="m-1@eu-de", payloadSha256="b" * 64, receiptB64="UkVDMg=="))
        # the test harness reads raw column names; ReSql camel-cases them (receiptB64, payloadSha256)
        self.assertEqual([{"receipt_b64": "UkVDMQ==", "payload_sha256": "a" * 64}], call("find_inbound_message", senderId="EU-DE", messageId="m-1@eu-de"))

    def test_the_same_message_id_from_another_sender_is_a_new_message(self):
        call("insert_inbound_message", senderId="EU-DE", messageId="m-2", payloadSha256="a" * 64, receiptB64="UkVDMQ==")
        self.assertEqual(1, len(call("insert_inbound_message", senderId="EU-FR", messageId="m-2", payloadSha256="a" * 64, receiptB64="UkVDMw==")))
        self.assertEqual([], call("find_inbound_message", senderId="EU-NL", messageId="m-2"))

    def test_concurrent_inserts_of_one_inbound_message_have_one_winner(self):
        with ThreadPoolExecutor(max_workers=8) as pool:
            results = list(pool.map(lambda _: call("insert_inbound_message", senderId="EU-DE", messageId="m-race", payloadSha256="a" * 64, receiptB64="UkVDMQ=="), range(8)))
        self.assertEqual(1, sum(1 for rows in results if rows))

    def test_expired_inbound_messages_are_purged_by_archiver_role_and_app_cannot_delete(self):
        sql("INSERT INTO inbound_messages (sender_id, message_id, payload_sha256, receipt_b64, created_at) VALUES ('EU-DE', 'old', 'x', 'y', now() - interval '3 days'), ('EU-DE', 'fresh', 'x', 'y', now());")
        query, signature, arguments = endpoint("delete_expired_inbound_messages", {"keepHours": 48})
        out = sql("SET ROLE db_archiver; PREPARE q" + signature + " AS " + query + "; EXECUTE q" + arguments + ";").strip()
        self.assertEqual("1", out)
        self.assertEqual("fresh", sql("SELECT message_id FROM inbound_messages;").strip())
        with self.assertRaises(Exception):
            sql("SET ROLE app; DELETE FROM inbound_messages;")

    def follow_up(self, query_id, status="PENDING", platform="mock", dataset="40000000-0000-0000-0000-000000000001", content='["hello",["r1"],[]]', **extra):
        if "contentHash" not in extra:
            extra["contentJson"] = content
        return call("insert_follow_up_log", queryId=query_id, status=status, destinationPlatformId=platform, datasetRequestId=dataset,
                    requestingGateId="EU-EE", destinationGateId="EU-EE", payload="hello", **extra)

    def follow_up_state(self, query_id, platform="mock", dataset="40000000-0000-0000-0000-000000000001", content='["hello",["r1"],[]]'):
        return [row["status"] for row in call("get_follow_up_state", queryId=query_id, destinationPlatformId=platform, datasetRequestId=dataset, contentJson=content)]

    def follow_up_statuses(self):
        return sql("SELECT string_agg(status::text, ',' ORDER BY revision) FROM follow_up_log;").strip()

    def test_follow_up_in_flight_is_not_started_twice(self):
        query_id = "40000000-0000-0000-0000-0000000000a1"
        first = self.follow_up(query_id)
        self.assertEqual(1, len(first))
        self.assertEqual([], self.follow_up(query_id))
        self.assertEqual(["PENDING"], self.follow_up_state(query_id))
        self.assertEqual("PENDING", self.follow_up_statuses())

    def test_content_hash_is_the_sha256_of_the_content_json(self):
        row = self.follow_up("40000000-0000-0000-0000-0000000000b1", content="abc")[0]
        self.assertEqual("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", row["content_hash"])

    def test_delivered_follow_up_is_never_started_again(self):
        query_id = "40000000-0000-0000-0000-0000000000a2"
        content_hash = self.follow_up(query_id)[0]["content_hash"]
        self.assertEqual(1, len(self.follow_up(query_id, status="DELIVERED", contentHash=content_hash)))
        sql("UPDATE follow_up_log SET created_at = now() - interval '1 day';")
        self.assertEqual([], self.follow_up(query_id))
        self.assertEqual(["DELIVERED"], self.follow_up_state(query_id))
        self.assertEqual("PENDING,DELIVERED", self.follow_up_statuses())

    def test_failed_follow_up_can_be_retried_and_the_retry_is_guarded_again(self):
        query_id = "40000000-0000-0000-0000-0000000000a3"
        content_hash = self.follow_up(query_id)[0]["content_hash"]
        self.assertEqual(1, len(self.follow_up(query_id, status="FAILED", contentHash=content_hash, failureReason="forward_status_503")))
        self.assertEqual(["FAILED"], self.follow_up_state(query_id))
        self.assertEqual(1, len(self.follow_up(query_id)))
        self.assertEqual([], self.follow_up(query_id))
        self.assertEqual("PENDING,FAILED,PENDING", self.follow_up_statuses())

    def test_pending_follow_up_of_a_dead_node_is_retried_after_two_minutes(self):
        query_id = "40000000-0000-0000-0000-0000000000a4"
        self.follow_up(query_id)
        sql("UPDATE follow_up_log SET created_at = now() - interval '119 seconds';")
        self.assertEqual([], self.follow_up(query_id))
        sql("UPDATE follow_up_log SET created_at = now() - interval '121 seconds';")
        self.assertEqual(1, len(self.follow_up(query_id)))
        self.assertEqual("PENDING,PENDING", self.follow_up_statuses())

    def test_follow_up_identity_is_query_id_platform_dataset_request_and_content(self):
        query_id = "40000000-0000-0000-0000-0000000000a5"
        self.assertEqual(1, len(self.follow_up(query_id)))
        self.assertEqual(1, len(self.follow_up(query_id, platform="other")))
        self.assertEqual(1, len(self.follow_up(query_id, dataset="40000000-0000-0000-0000-000000000002")))
        self.assertEqual(1, len(self.follow_up(query_id, content='["hello",["r2"],[]]')))
        self.assertEqual(1, len(self.follow_up(query_id, content='["hello",["r1"],[{"fileName":"a"}]]')))
        self.assertEqual(1, len(self.follow_up("40000000-0000-0000-0000-0000000000a6")))
        self.assertEqual([], self.follow_up(query_id))
        self.assertEqual([], self.follow_up_state(query_id, platform="nobody"))
        self.assertEqual([], self.follow_up_state(query_id, content="[]"))

    def test_follow_up_without_query_id_is_never_deduplicated(self):
        self.assertEqual(1, len(self.follow_up(None)))
        self.assertEqual(1, len(self.follow_up(None)))
        self.assertEqual(1, len(self.follow_up(None, status="DELIVERED")))

    def test_failed_follow_up_row_carries_the_reason_and_is_never_skipped(self):
        query_id = "40000000-0000-0000-0000-0000000000a7"
        self.assertEqual(1, len(self.follow_up(query_id, status="FAILED", failureReason="platform_not_found")))
        self.assertEqual(1, len(self.follow_up(query_id, status="FAILED", failureReason="platform_not_found")))
        self.assertEqual("platform_not_found", sql("SELECT DISTINCT failure_reason FROM follow_up_log;").strip())

    def test_concurrent_follow_ups_with_one_query_id_have_one_winner(self):
        with ThreadPoolExecutor(max_workers=8) as pool:
            for round_number in range(10):
                query_id = "40000000-0000-0000-0000-0000000001%02d" % round_number
                results = list(pool.map(lambda _: self.follow_up(query_id), range(8)))
                self.assertEqual(1, sum(1 for rows in results if rows), query_id)
        self.assertEqual("10", sql("SELECT count(*) FROM follow_up_log;").strip())

    def test_follow_up_log_stays_append_only_for_the_app_role(self):
        self.follow_up("40000000-0000-0000-0000-0000000000a8")
        with self.assertRaises(Exception):
            sql("SET ROLE app; UPDATE follow_up_log SET status = 'FAILED';")
        with self.assertRaises(Exception):
            sql("SET ROLE app; DELETE FROM follow_up_log;")

    def test_search_result_latest_row_wins(self):
        call("insert_search_pending", searchId="s1")
        self.assertEqual([{"status": "pending", "body": None}], call("get_search_result", searchId="s1"))
        call("insert_search_complete", searchId="s1", body=json.dumps([{"datasetId": "d"}]))
        rows = call("get_search_result", searchId="s1")
        self.assertEqual("complete", rows[0]["status"])
        self.assertEqual([{"datasetId": "d"}], rows[0]["body"])

    def test_unknown_search_result_is_empty(self):
        self.assertEqual([], call("get_search_result", searchId="never-registered"))

    def test_expired_search_results_are_purged_by_archiver_role(self):
        sql("INSERT INTO search_results (search_id, status, created_at) VALUES ('old', 'pending', now() - interval '1 hour'), ('fresh', 'pending', now());")
        query, signature, arguments = endpoint("delete_expired_search_results", {"keepMinutes": 10})
        out = sql("SET ROLE db_archiver; PREPARE q" + signature + " AS " + query + "; EXECUTE q" + arguments + ";").strip()
        self.assertEqual("1", out)
        self.assertEqual("fresh", sql("SELECT search_id FROM search_results;").strip())

    def road(self, dataset, mode="3", accepted="20 days", status="ACTIVE", stamp="now() - interval '30 days'"):
        sql(f"INSERT INTO consignments (dataset_id,platform_id,gate_id,xml,status,transport_mode,acceptance_date,main_transport_id,created_at) "
            f"VALUES ('{dataset}','mock','EU-EE','<x/>','{status}','{mode}',now() - interval '{accepted}','{dataset}',{stamp});")

    def latest_statuses(self):
        return dict(row.split("|") for row in sql(
            "SELECT DISTINCT ON (dataset_id) dataset_id, status FROM consignments "
            "ORDER BY dataset_id, created_at DESC, revision DESC;").split())

    def test_cabotage_expiry_appends_inactive_for_expired_road_only(self):
        expired, fresh, rail, deleted, superseded = (f"30000000-0000-0000-0000-00000000000{i}" for i in range(1, 6))
        self.road(expired)
        self.road(fresh, accepted="13 days")
        self.road(rail, mode="2")
        self.road(deleted)
        self.road(deleted, status="DELETED", stamp="now() - interval '1 day'")
        self.road(superseded, accepted="20 days")
        self.road(superseded, accepted="1 day", stamp="now() - interval '1 day'")
        before = int(sql("SELECT count(*) FROM consignments;"))
        self.assertEqual([{"candidate_count": 1, "expired_count": 0}], call("expire_road_consignments", dryRun="true"))
        self.assertEqual(before, int(sql("SELECT count(*) FROM consignments;")))
        self.assertEqual([{"candidate_count": 1, "expired_count": 1}], call("expire_road_consignments"))
        self.assertEqual(before + 1, int(sql("SELECT count(*) FROM consignments;")))
        self.assertEqual({expired: "INACTIVE", fresh: "ACTIVE", rail: "ACTIVE", deleted: "DELETED", superseded: "ACTIVE"}, self.latest_statuses())
        self.assertEqual("<x/>|" + expired, sql(f"SELECT xml || '|' || main_transport_id FROM consignments WHERE dataset_id = '{expired}' AND status = 'INACTIVE';").strip())
        self.assertEqual([{"candidate_count": 0, "expired_count": 0}], call("expire_road_consignments"))


class MigrationPrototype(unittest.TestCase):
    def setUp(self):
        sql("TRUNCATE users, consignments;")

    def test_equal_timestamp_versions_follow_insertion_sequence(self):
        sql(f"INSERT INTO users (id,tara_sub,name,created_at,is_active) VALUES ('{USER_ID}','old','First','2026-09-01',true);")
        sql(f"INSERT INTO users (id,tara_sub,name,created_at,is_active) VALUES ('{USER_ID}','old','Second','2026-09-01',false);")
        self.assertEqual("f", sql(f"SELECT is_active FROM users WHERE id='{USER_ID}' ORDER BY created_at DESC,revision DESC LIMIT 1;").strip())

    def test_stale_user_append_cannot_restore_active_or_clear_revocation(self):
        sql(f"SET ROLE app; INSERT INTO users (id,tara_sub,name,is_active,token_revoked_at) VALUES ('{USER_ID}','old','Deleted',false,'2026-09-01');")
        sql(f"SET ROLE app; INSERT INTO users (id,tara_sub,name,is_active) VALUES ('{USER_ID}','old','Stale update',true);")
        self.assertEqual("f|2026-09-01", sql(f"SELECT is_active,token_revoked_at::date FROM users WHERE id='{USER_ID}' ORDER BY created_at DESC,revision DESC LIMIT 1;").strip())

    def test_concurrent_append_waits_for_delete_and_cannot_resurrect_a_user(self):
        sql(f"INSERT INTO users (id,tara_sub,name,is_active) VALUES ('{USER_ID}','old','First',true);")
        with ThreadPoolExecutor(max_workers=1) as pool:
            deleting = pool.submit(sql, f"SET ROLE app; BEGIN; INSERT INTO users (id,tara_sub,name,is_active) VALUES ('{USER_ID}','old','Deleted',false); SELECT pg_sleep(2); COMMIT;")
            for _ in range(30):
                if int(sql("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND granted;")):
                    break
                time.sleep(0.1)
            else:
                self.fail("Delete did not acquire the registry lock")
            sql(f"SET ROLE app; INSERT INTO users (id,tara_sub,name,is_active) VALUES ('{USER_ID}','old','Stale update',true);")
            deleting.result()
        self.assertEqual("f", sql(f"SELECT is_active FROM users WHERE id='{USER_ID}' ORDER BY created_at DESC,revision DESC LIMIT 1;").strip())


def benchmark(rows):
    sql("TRUNCATE consignments; INSERT INTO consignments (dataset_id,platform_id,gate_id,xml,used_equipment_ids,created_at) "
        "SELECT md5(n::text)::uuid,'mock','EU-EE','<criteria/>',ARRAY['CONTAINER-' || n::text],now() FROM generate_series(1," + str(rows) + ") n;")
    sql("VACUUM (ANALYZE) consignments;")
    for name in ["check_transport_means_registered", "get_consignments_by_transport_means"]:
        for baseline in [True, False]:
            source = subprocess.run(["git", "show", "origin/dev:DSL/Resql/efti/POST/" + name + ".sql"],
                                    cwd=ROOT, check=True, capture_output=True, text=True).stdout if baseline else None
            query, signature, arguments = endpoint(name, {"transport_means_id": "CONTAINER-" + str(rows // 2)}, source)
            for mode in ["force_custom_plan", "force_generic_plan"]:
                plan = json.loads(sql("SET ROLE app; SET plan_cache_mode=" + mode + "; PREPARE q" + signature +
                                      " AS " + query + "; EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) EXECUTE q" + arguments + ";"))[0]
                nodes = []
                def visit(node):
                    nodes.append(node["Node Type"] + (":" + node["Index Name"] if "Index Name" in node else ""))
                    for child in node.get("Plans", []):
                        visit(child)
                visit(plan["Plan"])
                print(json.dumps({"query": name, "baseline": baseline, "mode": mode, "rows": rows,
                                  "execution_ms": plan["Execution Time"], "shared_hit_blocks": plan["Plan"]["Shared Hit Blocks"],
                                  "nodes": nodes}), flush=True)
    refresh = json.loads(sql("SET ROLE app; EXPLAIN (ANALYZE,FORMAT JSON) " + (ROOT / "DSL/Resql/efti/POST/refresh_consignment_counts.sql").read_text().split("*/", 1)[1].strip().rstrip(";") + ";"))[0]
    print(json.dumps({"query": "refresh_consignment_counts", "rows": rows, "execution_ms": refresh["Execution Time"]}), flush=True)
    query, signature, arguments = endpoint("get_consignment_counts", {})
    plan = json.loads(sql("SET ROLE app; PREPARE q" + signature + " AS " + query + "; EXPLAIN (ANALYZE,FORMAT JSON) EXECUTE q" + arguments + ";"))[0]
    print(json.dumps({"query": "get_consignment_counts", "rows": rows, "execution_ms": plan["Execution Time"]}), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--performance", action="store_true", help="Also measure plans on synthetic consignments (--rows, default 100,000)")
    parser.add_argument("--rows", type=int, default=100000, help="Synthetic consignment rows for --performance")
    parser.add_argument("--init", action="store_true", help="Test the consolidated fresh-install schema instead of upgrade SQL")
    parser.add_argument("--migration-prototype", action="store_true", help="Apply proposed SQL from stdin only inside the disposable database")
    options = parser.parse_args()
    prototype = sys.stdin.read() if options.migration_prototype else None
    docker("run", "-d", "--name", CONTAINER, "--tmpfs", "/var/lib/postgresql", "-v", str(ROOT) + ":/workdir:ro",
           "-e", "POSTGRES_HOST_AUTH_METHOD=trust", "postgres:18")
    try:
        for _ in range(120):
            try:
                sql("SELECT 1;")
                break
            except RuntimeError:
                time.sleep(0.25)
        else:
            raise RuntimeError("Disposable PostgreSQL did not become ready")
        paths = [ROOT / "DSL/Liquibase/init.sql"] if options.init else sorted((ROOT / "DSL/Liquibase/initial").glob("*.sql"))
        for path in paths:
            try:
                sql(path.read_text())
            except RuntimeError as error:
                raise RuntimeError(path.name + ": " + str(error)) from error
        if not options.init:
            sql((ROOT / "DSL/Liquibase/changelog/20260902-platform-api-key.sql").read_text())
        migration = ROOT / "DSL/Liquibase/changelog/20260914-latest-row-order.sql"
        if migration.exists() and not options.init:
            sql(migration.read_text())
            for name in ["20260926-drop-async-responses.sql", "20261005-async-responses.sql", "20261007-search-results.sql"]:
                sql((ROOT / "DSL/Liquibase/changelog" / name).read_text())
        elif prototype:
            sql(prototype)
        if not options.init:
            sql((ROOT / "DSL/Liquibase/changelog/20261005-read-model-generations.sql").read_text())
            sql((ROOT / "DSL/Liquibase/changelog/20261006-read-model-registries.sql").read_text())
            sql((ROOT / "DSL/Liquibase/changelog/20261007-read-model-consignment-summary.sql").read_text())
            sql((ROOT / "DSL/Liquibase/changelog/20261008-registry-sync.sql").read_text())
            sql((ROOT / "DSL/Liquibase/changelog/20261009-drop-registry-tables.sql").read_text())
            sql((ROOT / "DSL/Liquibase/changelog/20261015-consignments-road-expiry-index.sql").read_text())
            sql((ROOT / "DSL/Liquibase/changelog/20261023-inbound-messages.sql").read_text())
            sql((ROOT / "DSL/Liquibase/changelog/20261026-follow-up-log-query-id.sql").read_text())
        suite = unittest.defaultTestLoader.loadTestsFromTestCase(Queries)
        if prototype or migration.exists():
            suite.addTests(unittest.defaultTestLoader.loadTestsFromTestCase(MigrationPrototype))
        result = unittest.TextTestRunner(verbosity=2).run(suite)
        if result.wasSuccessful() and options.performance:
            benchmark(options.rows)
        raise SystemExit(not result.wasSuccessful())
    finally:
        docker("rm", "-f", CONTAINER)


if __name__ == "__main__":
    main()
