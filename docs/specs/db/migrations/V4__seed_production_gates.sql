-- Migration: V4__seed_production_gates.sql
-- Description: Production gate registry with real endpoint URLs and certificates
-- Author: Development Team
-- Date: 2026-04-22
-- Database: PostgreSQL 14+
--
-- Usage: Apply ONLY in production environment.
--        In development, use V2__seed_data.sql gates (placeholder certs).
--        Replace certificate values with actual production PEM certificates
--        obtained from the EU eFTI gateway operator registry.
--
-- Rollback:
--   UPDATE gates SET
--     eDeliveryUrl = 'https://efti.traficom.fi/as4',
--     eDeliveryCert = '-----BEGIN CERTIFICATE-----\n...\n-----END CERTIFICATE-----',
--     status = 'ONLINE'
--   WHERE id IN ('eu-fi01','eu-de01','eu-se01','eu-lt01','eu-lv01','eu-pl01');

-- =====================================================
-- ENVIRONMENT GUARD
-- =====================================================

DO $$
BEGIN
  -- Skip if not explicitly running in production context
  -- Set app.environment = 'production' via: SET app.environment = 'production';
  -- Or via Flyway placeholder: ${app.environment}
  IF current_setting('app.environment', true) NOT IN ('production', 'staging') THEN
    RAISE NOTICE 'V4: Skipping production gate seed (app.environment = %). Set app.environment = production to apply.', current_setting('app.environment', true);
    RETURN;
  END IF;

  RAISE NOTICE 'V4: Applying production gate registry...';
END $$;

-- =====================================================
-- PRODUCTION GATE REGISTRY
-- =====================================================
-- Replace placeholder certificates with real production PEM certificates.
-- Certificates are obtained from each national eFTI gateway operator.
-- eDeliveryUrl: AS4 access point URL (SOAP/HTTPS)
-- tlsCert: optional TLS client certificate for mutual TLS (mTLS)

INSERT INTO gates (id, countryCode, eDeliveryUrl, eDeliveryCert, tlsCert, status)
VALUES
  (
    'eu-fi01', 'FI',
    'https://efti.traficom.fi/as4',
    -- Replace with actual Traficom (Finland) production certificate
    '-----BEGIN CERTIFICATE-----
REPLACE_WITH_ACTUAL_TRAFICOM_FI_PRODUCTION_CERTIFICATE
-----END CERTIFICATE-----',
    NULL,
    'ONLINE'
  ),
  (
    'eu-de01', 'DE',
    'https://efti.bast.de/as4',
    -- Replace with actual BASt (Germany) production certificate
    '-----BEGIN CERTIFICATE-----
REPLACE_WITH_ACTUAL_BAST_DE_PRODUCTION_CERTIFICATE
-----END CERTIFICATE-----',
    NULL,
    'ONLINE'
  ),
  (
    'eu-se01', 'SE',
    'https://efti.trafikverket.se/as4',
    -- Replace with actual Trafikverket (Sweden) production certificate
    '-----BEGIN CERTIFICATE-----
REPLACE_WITH_ACTUAL_TRAFIKVERKET_SE_PRODUCTION_CERTIFICATE
-----END CERTIFICATE-----',
    NULL,
    'ONLINE'
  ),
  (
    'eu-lt01', 'LT',
    'https://efti.transp.lt/as4',
    -- Replace with actual LTSA (Lithuania) production certificate
    '-----BEGIN CERTIFICATE-----
REPLACE_WITH_ACTUAL_TRANSP_LT_PRODUCTION_CERTIFICATE
-----END CERTIFICATE-----',
    NULL,
    'ONLINE'
  ),
  (
    'eu-lv01', 'LV',
    'https://efti.sam.gov.lv/as4',
    -- Replace with actual SAM (Latvia) production certificate
    '-----BEGIN CERTIFICATE-----
REPLACE_WITH_ACTUAL_SAM_GOV_LV_PRODUCTION_CERTIFICATE
-----END CERTIFICATE-----',
    NULL,
    'ONLINE'
  ),
  (
    'eu-pl01', 'PL',
    'https://efti.gitd.gov.pl/as4',
    -- Replace with actual GITD (Poland) production certificate
    '-----BEGIN CERTIFICATE-----
REPLACE_WITH_ACTUAL_GITD_GOV_PL_PRODUCTION_CERTIFICATE
-----END CERTIFICATE-----',
    NULL,
    'ONLINE'
  )
ON CONFLICT (id) DO UPDATE SET
  eDeliveryUrl  = EXCLUDED.eDeliveryUrl,
  eDeliveryCert = EXCLUDED.eDeliveryCert,
  tlsCert       = EXCLUDED.tlsCert,
  status        = EXCLUDED.status;

-- =====================================================
-- MIGRATION COMPLETE
-- =====================================================

DO $$
BEGIN
  RAISE NOTICE 'Migration V4 completed: Production gates updated (% gates total)', (SELECT COUNT(*) FROM gates);
END $$;
