#!/bin/bash
set -euo pipefail

HOST="${1:?Usage: seed-e2e.sh root@<host>}"

E2E_ADMIN_ID='e2e00000-0000-0000-0000-00000000adm1'
E2E_USER_ID='e2e00000-0000-0000-0000-00000000usr1'
# bcrypt cost 10 de "e2e-admin-2026"
HASH_ADMIN='$2y$10$J84aYfxu2bxqop1gbyd63.8wlBGTp0IOLuxe9kgFBDsmrn/m/YqWi'
# bcrypt cost 5 de "e2e-user-2026" (cost réduit pour rapidité des tests)
HASH_USER='$2y$05$yCSht4vQeyWQf7qu2P2nNeEoA6h8BDjRvzE7z/AQ7RfsbsO8jZd72'

generer_sql() {
  local admin_id="$1" user_id="$2" hash_admin="$3" hash_user="$4"
  cat <<SQL
DELETE FROM users WHERE username IN ('e2e-admin', 'e2e-user');

INSERT INTO users (id, email, email_verified, username, display_username, role)
VALUES
  ('${admin_id}', 'e2e-admin@test.local', true, 'e2e-admin', 'e2e-admin', 'admin'),
  ('${user_id}',  'e2e-user@test.local',  true, 'e2e-user',  'e2e-user',  'user');

INSERT INTO accounts (id, account_id, provider_id, user_id, password, created_at, updated_at)
VALUES
  (gen_random_uuid()::text, 'e2e-admin', 'credential', '${admin_id}', '${hash_admin}', NOW(), NOW()),
  (gen_random_uuid()::text, 'e2e-user',  'credential', '${user_id}',  '${hash_user}',  NOW(), NOW());

SELECT username, role FROM users WHERE username IN ('e2e-admin', 'e2e-user') ORDER BY username;
SQL
}

generer_sql "$E2E_ADMIN_ID" "$E2E_USER_ID" "$HASH_ADMIN" "$HASH_USER" \
  | ssh "$HOST" "su - postgres -c 'psql strapontin -v ON_ERROR_STOP=1'"

echo "Comptes E2E créés sur $HOST."
