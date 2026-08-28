/*
description: update user (append-only: inserts a new row with the same logical id)
params:
  id:      { type: string,       required: true  }
  name:    { type: string,       required: true  }
  taraSub: { type: string,       required: true  }
  roles:   { type: string_array, required: false }  -- e.g. ['ADMIN'] or ['AUTHORITY']
*/
INSERT INTO users (id, tara_sub, name, roles)
VALUES (
  :id::uuid,
  :taraSub,
  :name,
  COALESCE(:roles, '{}')
)
RETURNING
  row_id,
  id,
  tara_sub,
  name,
  roles,
  'ADMIN'     = ANY(roles) AS is_admin,
  'AUTHORITY' = ANY(roles) AS is_authority,
  token_revoked_at,
  is_active,
  created_at;
