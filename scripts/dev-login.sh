#!/usr/bin/env bash
#
# Log in against the mocked TARA and print the resulting gate JWT.
#
#   ./scripts/dev-login.sh 60001017869        # Mari Tamm  — AUTHORITY, no admin rights
#   ./scripts/dev-login.sh 60001019906        # Super Admin — unrestricted
#   ./scripts/dev-login.sh 60001017727        # exists in TARA, NOT provisioned in the gate
#
# Walks the real OIDC redirect chain (TIM -> TARA-Mock -> TIM callback) rather than
# short-cutting to TIM's JWT generator, so this exercises the same hops a browser would.
# TARA-Mock's ?autologin= parameter is what removes the need for a browser.
#
# The token comes back as a Set-Cookie because that is how TIM emits it; from here on the
# gate's own contract is Bearer-only (docs/architecture/user-interfaces/README.md:30-32):
#
#   curl -H "Authorization: Bearer $(./scripts/dev-login.sh 60001019906)" \
#        http://localhost:8086/efti/api/v1/user
#
set -euo pipefail

PERSONAL_CODE="${1:-}"
TIM_URL="${TIM_URL:-http://localhost:8085}"
CALLBACK_URL="${CALLBACK_URL:-http://localhost:8086}"
COOKIE_NAME="customJwtCookie"

if [[ -z "$PERSONAL_CODE" ]]; then
  echo "usage: $0 <personal-code>" >&2
  exit 2
fi

JAR="$(mktemp)"
trap 'rm -f "$JAR"' EXIT

log()  { echo "[dev-login] $*" >&2; }
die()  { echo "[dev-login] ERROR: $*" >&2; exit 1; }

# Pull the Location header out of a raw curl response (-i), CR stripped.
location_of() { grep -i '^location:' <<<"$1" | tail -1 | sed 's/^[Ll]ocation:[[:space:]]*//' | tr -d '\r'; }

# ── 1. Ask TIM to start the OIDC dance; it answers with a redirect to TARA-Mock ──
log "1/4 starting OIDC flow at $TIM_URL"
resp="$(curl -sSi -c "$JAR" -b "$JAR" \
  "$TIM_URL/oauth2/authorization/tara?callback_url=$CALLBACK_URL")" \
  || die "cannot reach TIM at $TIM_URL — is the stack up?"
authorize_url="$(location_of "$resp")"
[[ -n "$authorize_url" ]] || die "TIM did not redirect to TARA. Response head:
$(head -20 <<<"$resp")"

# ── 2. Authenticate at TARA-Mock. autologin skips the identity-picker page. ──
log "2/4 authenticating $PERSONAL_CODE at TARA-Mock"
sep='&'; [[ "$authorize_url" == *"?"* ]] || sep='?'
resp="$(curl -sSik "${authorize_url}${sep}autologin=${PERSONAL_CODE}")" \
  || die "cannot reach TARA-Mock at $authorize_url"
callback_url="$(location_of "$resp")"
[[ "$callback_url" == *"code="* ]] \
  || die "TARA-Mock returned no authorization code. Is $PERSONAL_CODE in docker/tara-mock/identities.json? Response head:
$(head -20 <<<"$resp")"

# ── 3. Hand the code to TIM. TIM does the back-channel token exchange against
#       tara-mock:8080, verifies the RS256 id_token against the mock JWKS, and mints
#       its own JWT as a Set-Cookie. ──
log "3/4 exchanging the authorization code at TIM"
# TARA-Mock builds the callback from the registered redirect URI, which points at TIM.
resp="$(curl -sSi -c "$JAR" -b "$JAR" "$callback_url")" \
  || die "TIM callback failed at $callback_url"

# ── 4. Read the JWT out of the cookie jar. ──
log "4/4 extracting the JWT"
jwt="$(awk -v name="$COOKIE_NAME" '$0 !~ /^#/ || $0 ~ /^#HttpOnly_/ {
         for (i = 1; i <= NF; i++) if ($i == name) { print $(i+1); exit }
       }' "$JAR")"

if [[ -z "$jwt" ]]; then
  # Fall back to the response headers in case the jar did not capture it.
  jwt="$(grep -i '^set-cookie:' <<<"$resp" | sed -n "s/.*${COOKIE_NAME}=\([^;]*\).*/\1/p" | tail -1 | tr -d '\r')"
fi

[[ -n "$jwt" ]] || die "no $COOKIE_NAME issued. TIM response head:
$(head -30 <<<"$resp")"

log "ok — JWT issued for $PERSONAL_CODE"
echo "$jwt"
