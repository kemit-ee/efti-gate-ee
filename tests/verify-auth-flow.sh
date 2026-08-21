#!/usr/bin/env bash
#
# End-to-end check of the authentication flow. Requires the stack to be running:
#
#   docker compose up -d --build && sleep 45 && ./tests/verify-auth-flow.sh
#
# Proves: mocked-TARA login issues a JWT; the JWT opens an endpoint the caller is
# entitled to (GET /api/v1/user) and is refused on one they are not
# (GET /api/v1/audit, Super Admin only); unauthenticated, unprovisioned, soft-deleted,
# superseded-identifier and admin-revoked callers are all rejected; logout revokes the
# token and records it in the sessions denylist.
#
# Sections 9a-9c mutate the users table (append-only INSERTs) and restore it afterwards.
B=http://localhost:8086/efti
pass=0; fail=0

chk() { # chk <label> <expected> <actual>
  if [[ -n "$2" && "$2" == "$3" ]]; then echo "  PASS  $1 -> $3"; pass=$((pass+1));
  else echo "  FAIL  $1 -> got '$3', expected '$2'"; fail=$((fail+1)); fi
}
code()  { curl -sS -o /dev/null -w "%{http_code}" "$@"; }
psql()  { docker exec database psql -U efti -d efti -tAc "$1"; }
authcode() { curl -sS -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $1" "$B/api/v1/user"; }
# Append a new users row for Mari Tamm, overriding one column. Append-only: the previous
# row is untouched and the newest row becomes current state.
mari_row() { # mari_row <column> <value-sql>
  psql "INSERT INTO users (id, tara_sub, email, name, is_admin, roles, subsets, is_active, token_revoked_at)
        SELECT DISTINCT ON (id) id, tara_sub, email, name, is_admin, roles, subsets,
               $( [[ $1 == is_active ]] && echo "$2" || echo is_active ),
               $( [[ $1 == token_revoked_at ]] && echo "$2" || echo token_revoked_at )
          FROM users WHERE id='22222222-2222-2222-2222-222222222222'
         ORDER BY id, created_at DESC;" >/dev/null
}
mari_restore() {
  psql "INSERT INTO users (id, tara_sub, email, name, is_admin, roles, subsets)
        VALUES ('22222222-2222-2222-2222-222222222222','60001017869','mari.tamm@efti.test',
                'Mari Tamm',FALSE,'{\"AUTHORITY\":[\"auth-mta\"]}'::jsonb,ARRAY['EU07']::TEXT[]);" >/dev/null
}

echo "1. stack + liquibase"
chk "liquibase applied 5 changesets" "5" "$(docker compose logs liquibase 2>&1 | grep -c 'Running Changeset')"

echo "2. tara-mock discovery"
chk "RS256 advertised" "yes" "$(curl -sk https://localhost:8888/oidc/.well-known/openid-configuration | grep -q RS256 && echo yes || echo no)"

echo "3. seeded users"
chk "users logical rows" "2" "$(psql 'select count(distinct id) from users;' | tr -d ' ')"

echo "4. login via mocked TARA"
JWT=$(./scripts/dev-login.sh 60001017869 2>/dev/null)
ADMIN=$(./scripts/dev-login.sh 60001019906 2>/dev/null)
UNPROV=$(./scripts/dev-login.sh 60001017727 2>/dev/null)
chk "authority JWT issued" "yes" "$([[ -n $JWT ]] && echo yes || echo no)"
chk "super admin JWT issued" "yes" "$([[ -n $ADMIN ]] && echo yes || echo no)"
tok_jti=$(echo "$JWT" | cut -d. -f2 | tr '_-' '/+' | base64 -d 2>/dev/null | sed -n 's/.*"jti":"\([^"]*\)".*/\1/p')
chk "token carries a jti claim" "yes" "$([[ -n $tok_jti ]] && echo yes || echo no)"

echo "5. POSITIVE  GET /api/v1/user (any authenticated user)"
chk "authority" "200" "$(authcode "$JWT")"
chk "super admin" "200" "$(authcode "$ADMIN")"

echo "6. NEGATIVE authz  GET /api/v1/audit (Super Admin only)"
chk "authority denied" "403" "$(code -H "Authorization: Bearer $JWT" $B/api/v1/audit)"
chk "FORBIDDEN code" "yes" "$(curl -sS -H "Authorization: Bearer $JWT" $B/api/v1/audit | grep -q '"code":"FORBIDDEN"' && echo yes || echo no)"
chk "super admin allowed" "200" "$(code -H "Authorization: Bearer $ADMIN" $B/api/v1/audit)"

echo "7. NEGATIVE authn"
chk "no header" "401" "$(code $B/api/v1/user)"
chk "garbage token" "401" "$(authcode garbage)"
chk "unprovisioned TARA identity" "401" "$(authcode "$UNPROV")"

echo "8. logout / revocation"
chk "logout" "204" "$(code -X POST -H "Authorization: Bearer $JWT" -H 'Content-Type: application/json' -d '{}' $B/api/v1/auth/logout)"
chk "denylist jti == token jti" "$tok_jti" "$(psql 'select jti from sessions order by created_at desc, row_id asc limit 1;' | tr -d ' ')"
chk "token rejected after logout" "401" "$(authcode "$JWT")"
chk "logout idempotent" "204" "$(code -X POST -H "Authorization: Bearer $JWT" -H 'Content-Type: application/json' -d '{}' $B/api/v1/auth/logout)"

echo "9. DB-side state changes must invalidate a live token"
JWT2=$(./scripts/dev-login.sh 60001017869 2>/dev/null)
chk "fresh token works" "200" "$(authcode "$JWT2")"

echo "  9a. per-user broadcast revocation (users.token_revoked_at)"
mari_row token_revoked_at "NOW()"
chk "live token rejected after admin revoke" "401" "$(authcode "$JWT2")"
mari_restore
chk "new login works after restore" "200" "$(authcode "$(./scripts/dev-login.sh 60001017869 2>/dev/null)")"

echo "  9b. soft-deleted user (is_active = FALSE on the latest row)"
mari_row is_active "FALSE"
chk "soft-deleted user rejected" "401" "$(authcode "$(./scripts/dev-login.sh 60001017869 2>/dev/null)")"
mari_restore

echo "  9c. superseded tara_sub must stop authenticating"
psql "INSERT INTO users (id, tara_sub, email, name, is_admin, roles, subsets)
      SELECT DISTINCT ON (id) id, '60001099999', email, name, is_admin, roles, subsets
        FROM users WHERE id='22222222-2222-2222-2222-222222222222'
       ORDER BY id, created_at DESC;" >/dev/null
chk "old identifier rejected" "401" "$(authcode "$(./scripts/dev-login.sh 60001017869 2>/dev/null)")"
mari_restore
chk "restored identifier works" "200" "$(authcode "$(./scripts/dev-login.sh 60001017869 2>/dev/null)")"

echo "10. regression"
chk "existing demo route" "200" "$(code $B/v1/baasikontoroll)"
# The dev-login route (an unauthenticated JWT minter for any personal code) was removed.
# Assert on the body rather than the status: Ruuter answers 300 with an empty body for any
# unknown path, so "same as a path that never existed, and no token in it" is the real
# property. A JWT always starts with the base64 of {"alg" — i.e. eyJ.
chk "no unauthenticated token minter" "no-token" \
  "$(curl -sS -X POST -H 'Content-Type: application/json' -d '{"personalCode":"60001019906"}' \
       $B/auth/dev/dev-login | grep -q 'eyJ' && echo MINTED-A-TOKEN || echo no-token)"

echo
echo "==== $pass passed, $fail failed ===="
exit $fail
