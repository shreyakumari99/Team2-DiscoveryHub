#!/usr/bin/env bash
#
# Seeds the custodian registry in case-service (FR-2.3).
#
#   ./scripts/seed-custodians.sh                       # against docker (nginx :8080)
#   CASE_URL=http://localhost:8084 ./scripts/seed-custodians.sh   # against a local IDE run
#
# The corpus produced by data-generator only contains *messages*; the custodian
# records the UI lists (case-detail "add custodian" dropdown, the search and
# hold-scope custodian pickers) live in case_db and are only created through
# POST /api/v1/custodians. Run this once after the stack is up, otherwise those
# dropdowns are empty. Emails must match data-generator's Custodians.java, since
# that is what hold scope and search filters match on.

set -euo pipefail

CASE_URL="${CASE_URL:-http://localhost:8080}"

CUSTODIANS=(
  "Alice Chen|alice.chen@smarsh.com|Compliance"
  "Bob Patel|bob.patel@smarsh.com|Trading"
  "Carla Gomez|carla.gomez@smarsh.com|Trading"
  "David Kim|david.kim@smarsh.com|Investment Banking"
  "Elena Rossi|elena.rossi@smarsh.com|Investment Banking"
  "Frank Müller|frank.muller@smarsh.com|Research"
  "Grace Lee|grace.lee@smarsh.com|Research"
  "Hiro Tanaka|hiro.tanaka@smarsh.com|Operations"
  "Isla Murphy|isla.murphy@smarsh.com|Operations"
  "Juan Alvarez|juan.alvarez@smarsh.com|Compliance"
  "Kavya Nair|kavya.nair@smarsh.com|Wealth Management"
  "Liam O'Brien|liam.obrien@smarsh.com|Wealth Management"
  "Mia Wong|mia.wong@smarsh.com|HR"
  "Noah Schmidt|noah.schmidt@smarsh.com|HR"
  "Olivia Dias|olivia.dias@smarsh.com|Legal"
  "Pierre Dubois|pierre.dubois@smarsh.com|Legal"
  "Quinn Anderson|quinn.anderson@smarsh.com|IT"
  "Riya Sharma|riya.sharma@smarsh.com|IT"
  "Sven Johansson|sven.johansson@smarsh.com|Finance"
  "Tara Singh|tara.singh@smarsh.com|Finance"
  "Uma Costa|uma.costa@smarsh.com|Trading"
  "Viktor Petrov|viktor.petrov@smarsh.com|Research"
)

if ! existing=$(curl -sf "${CASE_URL}/api/v1/custodians"); then
  echo "!!! Cannot reach ${CASE_URL}/api/v1/custodians — is the stack up?" >&2
  echo "!!! For a service started from the IDE, try CASE_URL=http://localhost:8084" >&2
  exit 1
fi

# There is no unique constraint on custodians.email, so seeding twice would
# create duplicates and show every name in the dropdown twice.
if [[ "$existing" == *'"email"'* ]]; then
  echo ">>> Custodians are already registered; nothing to do."
  exit 0
fi

for entry in "${CUSTODIANS[@]}"; do
  IFS='|' read -r name email department <<<"$entry"
  if ! curl -sf -X POST "${CASE_URL}/api/v1/custodians" \
    -H 'Content-Type: application/json; charset=utf-8' \
    -d "{\"name\":\"${name}\",\"email\":\"${email}\",\"department\":\"${department}\"}" \
    >/dev/null; then
    echo "!!! Failed to create ${email}" >&2
    exit 1
  fi
  echo ">>> ${name} <${email}>"
done

echo ">>> Seeded ${#CUSTODIANS[@]} custodians."
