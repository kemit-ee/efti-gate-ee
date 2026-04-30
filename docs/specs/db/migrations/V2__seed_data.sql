-- Migration: V2__seed_data.sql
-- Description: Development/testing seed data for eFTI Gate v2.0
-- Author: Development Team
-- Date: 2026-04-22
-- Database: PostgreSQL 14+
--
-- Rollback:
--   DELETE FROM users WHERE email IN ('admin@efti.eu','demo-platform@efti.eu','demo-authority@efti.eu');
--   DELETE FROM authorities WHERE id = 'demo';
--   DELETE FROM platforms WHERE id IN ('demo', 'demo-edelivery');
--   DELETE FROM gates WHERE id IN ('eu-fi01','eu-de01','eu-se01','eu-lt01','eu-lv01','eu-pl01');

-- =====================================================
-- EU GATES REGISTRY (known peer gates)
-- =====================================================

-- Note: own gate (eu-ee31) is NOT inserted here — it is configured via environment variable GATE_ID.
-- Peer gates are seeded for development. Production URLs and certificates must be updated
-- via admin API (POST /gates) or V4__seed_production_gates.sql.

INSERT INTO gates (id, countryCode, eDeliveryUrl, eDeliveryCert, tlsCert, status)
VALUES
  ('eu-fi01', 'FI', 'https://efti.traficom.fi/as4',         '-----BEGIN CERTIFICATE-----\nMIIBpTCCAUugAwIBAgIUPlaceholderFI01CertV2AgIUAA==\n-----END CERTIFICATE-----', NULL, 'ONLINE'),
  ('eu-de01', 'DE', 'https://efti.bast.de/as4',             '-----BEGIN CERTIFICATE-----\nMIIBpTCCAUugAwIBAgIUPlaceholderDE01CertV2AgIUAA==\n-----END CERTIFICATE-----', NULL, 'ONLINE'),
  ('eu-se01', 'SE', 'https://efti.trafikverket.se/as4',     '-----BEGIN CERTIFICATE-----\nMIIBpTCCAUugAwIBAgIUPlaceholderSE01CertV2AgIUAA==\n-----END CERTIFICATE-----', NULL, 'ONLINE'),
  ('eu-lt01', 'LT', 'https://efti.transp.lt/as4',           '-----BEGIN CERTIFICATE-----\nMIIBpTCCAUugAwIBAgIUPlaceholderLT01CertV2AgIUAA==\n-----END CERTIFICATE-----', NULL, 'ONLINE'),
  ('eu-lv01', 'LV', 'https://efti.sam.gov.lv/as4',          '-----BEGIN CERTIFICATE-----\nMIIBpTCCAUugAwIBAgIUPlaceholderLV01CertV2AgIUAA==\n-----END CERTIFICATE-----', NULL, 'ONLINE'),
  ('eu-pl01', 'PL', 'https://efti.gitd.gov.pl/as4',         '-----BEGIN CERTIFICATE-----\nMIIBpTCCAUugAwIBAgIUPlaceholderPL01CertV2AgIUAA==\n-----END CERTIFICATE-----', NULL, 'ONLINE')
ON CONFLICT (id) DO NOTHING;

-- =====================================================
-- DEMO PLATFORM (development/testing)
-- =====================================================

-- Demo platform using HTTP (no eDelivery certificate)
INSERT INTO platforms (id, baseUrl, headers, supportsSubsetting, createdAt)
VALUES (
  'demo',
  'http://demo-platform:8070',
  '{"X-Api-Key":"demo-platform-key"}',
  FALSE,
  NOW()
)
ON CONFLICT (id) DO NOTHING;

-- Demo platform with eDelivery (AS4 transport)
INSERT INTO platforms (id, baseUrl, eDeliveryCert, supportsSubsetting, createdAt)
VALUES (
  'demo-edelivery',
  'http://demo-platform:8070/services/msh',
  '-----BEGIN CERTIFICATE-----
MIIC/zCCAeegAwIBAgIULAsIpl8XuCG4P1wR0gSyiMHSrFUwDQYJKoZIhvcNAQEL
BQAwDzENMAsGA1UEAwwEZGVtbzAeFw0yNTEyMjMxMjU1MDVaFw0yNjEyMjMxMjU1
MDVaMA8xDTALBgNVBAMMBGRlbW8wggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEK
AoIBAQDQc/xzfJvNjx69YzW4QbD7xoroSaSNgV1ti2Ru1/YBr+DXnCeUeMdg3eJx
Zr++/CetFw0hhNUT0ge/UB0Nt7cfVfiNG5aMBVi9k0ecmGMCQecK11tvFYvZ+jTU
sIQ+7mwrK56gX90dxrqjkdbK/ZmH3byKHnG/S+YXQ9stP6s7rbL/ulu9Z06FmVd8
KQseO65eEKiSEz30d484uoiSN/p/+XgWpsGqsQuBXK/pncqvQhcP/r0sst2f7Nu9
ucE3EWVIWXXZOWIy/+gj+Hphx1PJ+lllxwSV7pFf0p/ttF6aVeVLz2OFEF0jJ+/u
NXNt3P1aN60kf/bEakPP9nw4kI0ZAgMBAAGjUzBRMB0GA1UdDgQWBBQfoIFEajBX
v5cGIKbU0ZDyzLrcPTAfBgNVHSMEGDAWgBQfoIFEajBXv5cGIKbU0ZDyzLrcPTAP
BgNVHRMBAf8EBTADAQH/MA0GCSqGSIb3DQEBCwUAA4IBAQBpryOkYizwSI1lwEp5
RD42DIaxKxfD3svwAKZL5ejW1KR62ICJjdths+PfJLPtzx0OMNUu4Ywnt8zFOYzP
DsKixRG8XHXDcn7Ckv6q1gNVU0NvPrhF2nFXoIT0Oao0iwaB7tkRaauuvoZmiMB5
27U5iL0B0BDMteDwtgzl22bML2HQWManzN5MAOZbIP5qMdIPtM9cKka/ppkkimNx
k6a7ef1qmmn+fRtgk0xinSSIT5wEzT+VHXD38OjSVuziJak2vie6Ihq3s/AHfd7/
+6d6jJyOOZ+M5b7z46yZbr6STz2gHloYvqEmuxn8Rl8bV4JO9K4b6f9+baUZjuX
rrgA+
-----END CERTIFICATE-----',
  FALSE,
  NOW()
)
ON CONFLICT (id) DO NOTHING;

-- =====================================================
-- DEMO AUTHORITY (development/testing)
-- =====================================================

INSERT INTO authorities (id, subsets, countryCode, name)
VALUES ('demo', ARRAY['full'], 'EE', 'Demo Authority')
ON CONFLICT (id) DO NOTHING;

-- =====================================================
-- DEMO USERS (development/testing)
-- =====================================================

-- Admin user
-- Credentials: admin@efti.eu / (secret stored as sha256 hash in secretHash column)
-- Hash: UA7SAxKbE14pBjoRoSpPvV3U2bSn6KPu2dlWz4RIoYQ= (sha256 of demo admin secret)
INSERT INTO users (id, name, email, isAdmin, roles, secretHash, createdAt)
VALUES (
  '175791a3-da82-11f0-b10c-3c9c0f2eb459',
  'Demo Admin',
  'admin@efti.eu',
  TRUE,
  '{}',
  'UA7SAxKbE14pBjoRoSpPvV3U2bSn6KPu2dlWz4RIoYQ=',
  NOW()
)
ON CONFLICT (id) DO NOTHING;

-- Platform user (linked to 'demo' platform)
-- Credentials: demo-platform@efti.eu / (sha256 hash)
INSERT INTO users (id, name, email, isAdmin, roles, secretHash, createdAt)
VALUES (
  '502d74a0-eb03-11f0-b86c-3c9c0f2eb459',
  'Demo Platform',
  'demo-platform@efti.eu',
  TRUE,
  '{"PLATFORM":["demo"]}',
  'd6FSE1dlZGeZOaH9qBQVwLIBSNLtR/V6lcX+y8Xvd2Y=',
  NOW()
)
ON CONFLICT (id) DO NOTHING;

-- Authority user (linked to 'demo' authority)
-- Credentials: demo-authority@efti.eu / (sha256 hash)
INSERT INTO users (id, name, email, isAdmin, roles, secretHash, createdAt)
VALUES (
  '04fa30eb-eb08-11f0-b506-3c9c0f2eb459',
  'Demo Authority',
  'demo-authority@efti.eu',
  TRUE,
  '{"AUTHORITY":["demo"]}',
  'SYKPH8XcG6HGxFVyZX6xLxDUViJldNbNbYtqtvM2pO4=',
  NOW()
)
ON CONFLICT (id) DO NOTHING;

-- =====================================================
-- MIGRATION COMPLETE
-- =====================================================

DO $$
BEGIN
  RAISE NOTICE 'Migration V2 completed: Seed data inserted (% gates, 2 platforms, 1 authority, 3 users)',
    (SELECT COUNT(*) FROM gates);
END $$;
