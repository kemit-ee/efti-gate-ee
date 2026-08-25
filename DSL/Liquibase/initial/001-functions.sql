-- ============================================================================
-- 2. HELPER — record actor (denormalised users.row_id) on each INSERT
-- ============================================================================

CREATE OR REPLACE FUNCTION set_app_user(p_user UUID) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
  PERFORM set_config('app.user', p_user::text, false);
END;
$$;

CREATE OR REPLACE FUNCTION get_app_user() RETURNS UUID
LANGUAGE plpgsql AS $$
DECLARE v text := current_setting('app.user', true);
BEGIN
  RETURN CASE WHEN v IS NULL OR v = '' THEN NULL ELSE v::UUID END;
END;
$$;

COMMENT ON FUNCTION get_app_user() IS 'Returns the current session''s logical actor UUID (users.row_id). Used to populate `created_by` on registry INSERTs. NULL for system actions (background jobs, anonymous events).';
