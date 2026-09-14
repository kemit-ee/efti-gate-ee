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


def docker(*args, **kwargs):
    return subprocess.run(["docker", *args], check=True, text=True, capture_output=True, **kwargs).stdout


def sql(statement, csv_output=False):
    try:
        return docker("exec", "-i", CONTAINER, "psql", "-h", "127.0.0.1", "-U", "postgres", "-q",
                      "--csv" if csv_output else "-At", "-v", "ON_ERROR_STOP=1", input=statement)
    except subprocess.CalledProcessError as error:
        raise RuntimeError(error.stderr) from error


def endpoint(name, params, source=None):
    if source is None:
        source = (ROOT / "DSL/Resql/efti/POST" / (name + ".sql")).read_text()
    header = re.search(r"/\*(.*?)\*/", source, re.S)
    declarations = yaml.safe_load(header.group(1)).get("params", {})
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
        sql("TRUNCATE users, gates, platforms, authorities, consignments, follow_up_log, audit_log;")

    def user(self, tara="old", active=True, stamp="2026-09-01", revoked=None):
        revoked_sql = "NULL" if revoked is None else "'" + revoked + "'"
        sql(f"INSERT INTO users (id,tara_sub,name,secret_hash,is_active,created_at,token_revoked_at) "
            f"VALUES ('{USER_ID}','{tara}','Test','preserved-secret',{str(active).lower()},'{stamp}',{revoked_sql});")

    def platform(self, key="old-key", status="ONLINE", stamp="2026-09-01", platform="mock"):
        sql(f"INSERT INTO platforms (id,base_url,status,api_key_hash,api_key_generated_at,created_at) "
            f"VALUES ('{platform}','http://mock','{status}',digest('{key}','sha256'),'{stamp}','{stamp}');")

    def consignment(self, identifier="MATCH", status="ACTIVE", stamp="2026-09-01", platform="mock", equipment="{}", country="EE"):
        sql(f"INSERT INTO consignments (dataset_id,platform_id,gate_id,xml,status,main_transport_id,used_equipment_ids,transport_reg_country,created_at) "
            f"VALUES ('{DATASET_ID}','{platform}','EU-EE','<criteria/>','{status}','{identifier}','{equipment}','{country}','{stamp}');")

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

    def test_rotated_platform_key_never_matches_history(self):
        self.platform()
        self.platform(key="new-key", stamp="2026-09-02")
        self.assertEqual([], call("get_platform_by_api_key", apiKey="old-key"))
        self.assertEqual(1, len(call("get_platform_by_api_key", apiKey="new-key")))

    def test_deleted_platform_key_ping_and_rotation_are_denied(self):
        self.platform()
        self.platform(status="DELETED", stamp="2026-09-02")
        self.assertEqual([], call("get_platform_by_api_key", apiKey="old-key"))
        self.assertEqual([], call("update_platform_ping", id="mock", status="ONLINE"))
        self.assertEqual([], call("generate_platform_api_key", id="mock"))

    def test_ping_does_not_enable_disabled_platform(self):
        self.platform(status="DISABLED")
        self.assertEqual([], call("update_platform_ping", id="mock", status="ONLINE"))

    def test_ping_does_not_enable_deleted_or_disabled_gate(self):
        for status in ["DELETED", "DISABLED"]:
            with self.subTest(status=status):
                sql(f"INSERT INTO gates (id,country_code,e_delivery_url,status) VALUES ('EU-{status}','EE','http://gate','{status}');")
                self.assertEqual([], call("update_gate_ping", id="EU-" + status, status="ONLINE"))

    def test_duplicate_key_returns_all_platforms_for_guard_to_deny(self):
        self.platform(platform="one")
        self.platform(platform="two")
        self.assertEqual(2, len(call("get_platform_by_api_key", apiKey="old-key")))

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

    def test_disabled_platform_key_is_denied(self):
        self.platform(status="DISABLED")
        self.assertEqual([], call("get_platform_by_api_key", apiKey="old-key"))

    def test_registry_put_does_not_recreate_missing_or_deleted_ids(self):
        self.platform(status="DELETED")
        sql("INSERT INTO gates (id,country_code,e_delivery_url,status) VALUES ('EU-EE','EE','http://gate','DELETED');")
        sql("INSERT INTO authorities (id,name,registry_code,status) VALUES ('authority','Test','70000000','DELETED');")
        calls = [("update_gate", {"countryCode": "EE", "eDeliveryUrl": "http://gate"}, "EU-EE"),
                 ("update_platform", {"baseUrl": "http://platform"}, "mock"),
                 ("update_authority", {"name": "Changed", "registryCode": "70000000"}, "authority")]
        for name, params, deleted in calls:
            for identifier in [deleted, "missing"]:
                with self.subTest(endpoint=name, id=identifier):
                    self.assertEqual([], call(name, id=identifier, **params))


class MigrationPrototype(unittest.TestCase):
    def setUp(self):
        sql("TRUNCATE users, gates, platforms, authorities, consignments;")

    def test_equal_timestamp_versions_follow_insertion_sequence(self):
        sql(f"INSERT INTO users (id,tara_sub,name,created_at,is_active) VALUES ('{USER_ID}','old','First','2026-09-01',true);")
        sql(f"INSERT INTO users (id,tara_sub,name,created_at,is_active) VALUES ('{USER_ID}','old','Second','2026-09-01',false);")
        self.assertEqual("f", sql(f"SELECT is_active FROM users WHERE id='{USER_ID}' ORDER BY created_at DESC,revision DESC LIMIT 1;").strip())

    def test_stale_user_append_cannot_restore_active_or_clear_revocation(self):
        sql(f"SET ROLE app; INSERT INTO users (id,tara_sub,name,is_active,token_revoked_at) VALUES ('{USER_ID}','old','Deleted',false,'2026-09-01');")
        sql(f"SET ROLE app; INSERT INTO users (id,tara_sub,name,is_active) VALUES ('{USER_ID}','old','Stale update',true);")
        self.assertEqual("f|2026-09-01", sql(f"SELECT is_active,token_revoked_at::date FROM users WHERE id='{USER_ID}' ORDER BY created_at DESC,revision DESC LIMIT 1;").strip())

    def test_concurrent_append_waits_for_delete_and_cannot_resurrect(self):
        sql("INSERT INTO gates (id,country_code,e_delivery_url,status) VALUES ('EU-EE','EE','http://gate','ONLINE');")
        with ThreadPoolExecutor(max_workers=1) as pool:
            deleting = pool.submit(sql, "SET ROLE app; BEGIN; INSERT INTO gates (id,country_code,e_delivery_url,status) VALUES ('EU-EE','EE','http://gate','DELETED'); SELECT pg_sleep(2); COMMIT;")
            for _ in range(30):
                if int(sql("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND granted;")):
                    break
                time.sleep(0.1)
            else:
                self.fail("Delete did not acquire the registry lock")
            with self.assertRaisesRegex(RuntimeError, "Cannot modify deleted"):
                sql("SET ROLE app; INSERT INTO gates (id,country_code,e_delivery_url,status) VALUES ('EU-EE','EE','http://gate','ONLINE');")
            deleting.result()
        self.assertEqual("DELETED", sql("SELECT status FROM gates WHERE id='EU-EE' ORDER BY created_at DESC,revision DESC LIMIT 1;").strip())

    def test_stale_platform_append_cannot_restore_previous_key(self):
        sql("INSERT INTO platforms (id,status,api_key_hash,api_key_generated_at) VALUES ('mock','ONLINE',digest('current-key','sha256'),'2026-09-02');")
        sql("SET ROLE app; INSERT INTO platforms (id,status,api_key_hash,api_key_generated_at) VALUES ('mock','ONLINE',digest('old-key','sha256'),'2026-09-01');")
        self.assertEqual([], call("get_platform_by_api_key", apiKey="old-key"))
        self.assertEqual(1, len(call("get_platform_by_api_key", apiKey="current-key")))


def benchmark():
    sql("TRUNCATE consignments; INSERT INTO consignments (dataset_id,platform_id,gate_id,xml,used_equipment_ids,created_at) "
        "SELECT md5(n::text)::uuid,'mock','EU-EE','<criteria/>',ARRAY['CONTAINER-' || n::text],now() FROM generate_series(1,100000) n;")
    sql("VACUUM (ANALYZE) consignments;")
    for name in ["check_transport_means_registered", "get_consignments_by_transport_means"]:
        for baseline in [True, False]:
            source = subprocess.run(["git", "show", "origin/dev:DSL/Resql/efti/POST/" + name + ".sql"],
                                    cwd=ROOT, check=True, capture_output=True, text=True).stdout if baseline else None
            query, signature, arguments = endpoint(name, {"transport_means_id": "CONTAINER-50000"}, source)
            for mode in ["force_custom_plan", "force_generic_plan"]:
                plan = json.loads(sql("SET ROLE app; SET plan_cache_mode=" + mode + "; PREPARE q" + signature +
                                      " AS " + query + "; EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) EXECUTE q" + arguments + ";"))[0]
                nodes = []
                def visit(node):
                    nodes.append(node["Node Type"] + (":" + node["Index Name"] if "Index Name" in node else ""))
                    for child in node.get("Plans", []):
                        visit(child)
                visit(plan["Plan"])
                print(json.dumps({"query": name, "baseline": baseline, "mode": mode, "rows": 100000,
                                  "execution_ms": plan["Execution Time"], "shared_hit_blocks": plan["Plan"]["Shared Hit Blocks"],
                                  "nodes": nodes}), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--performance", action="store_true", help="Also measure plans on 100,000 synthetic consignments")
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
        for path in sorted((ROOT / "DSL/Liquibase/initial").glob("*.sql")):
            try:
                sql(path.read_text())
            except RuntimeError as error:
                raise RuntimeError(path.name + ": " + str(error)) from error
        sql((ROOT / "DSL/Liquibase/changelog/20260902-platform-api-key.sql").read_text())
        migration = ROOT / "DSL/Liquibase/changelog/20260914-latest-row-order.sql"
        if migration.exists():
            sql(migration.read_text())
        elif prototype:
            sql(prototype)
        suite = unittest.defaultTestLoader.loadTestsFromTestCase(Queries)
        if prototype:
            suite.addTests(unittest.defaultTestLoader.loadTestsFromTestCase(MigrationPrototype))
        result = unittest.TextTestRunner(verbosity=2).run(suite)
        if result.wasSuccessful() and options.performance:
            benchmark()
        raise SystemExit(not result.wasSuccessful())
    finally:
        docker("rm", "-f", CONTAINER)


if __name__ == "__main__":
    main()
