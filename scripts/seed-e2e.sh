#!/bin/bash
set -euo pipefail

HOST="${1:?Usage: seed-e2e.sh root@<host>}"

E2E_ADMIN_ID='e2e00000-0000-0000-0000-00000000adm1'
E2E_USER_ID='e2e00000-0000-0000-0000-00000000usr1'
E2E_INVITE_ID='e2e00000-0000-0000-0000-000000001nv1'
E2E_INVITE_EMAIL='e2e-invite@test.local'
# bcrypt cost 10 de "e2e-admin-2026"
HASH_ADMIN='$2y$10$J84aYfxu2bxqop1gbyd63.8wlBGTp0IOLuxe9kgFBDsmrn/m/YqWi'
# bcrypt cost 5 de "e2e-user-2026" (cost réduit pour rapidité des tests)
HASH_USER='$2y$05$yCSht4vQeyWQf7qu2P2nNeEoA6h8BDjRvzE7z/AQ7RfsbsO8jZd72'
# bcrypt cost 5 de "e2e-invite-tmp" (mot de passe temporaire, jamais utilisé)
HASH_INVITE='$2y$05$yCSht4vQeyWQf7qu2P2nNeEoA6h8BDjRvzE7z/AQ7RfsbsO8jZd72'
# SHA-256 du token "e2e-invite-token-2026" — calculé via python3 hashlib.sha256
INVITE_TOKEN_HASH='db2b2442ac665c1f60dfc4dcf0a7479d6148c83e95938b4b3f2755eb2d8d24fc'

generer_sql() {
  local admin_id="$1" user_id="$2" invite_id="$3"
  local hash_admin="$4" hash_user="$5" hash_invite="$6"
  local invite_email="$7" invite_token_hash="$8"
  cat <<SQL
DELETE FROM verifications WHERE identifier = 'invite:${invite_email}';
DELETE FROM users WHERE id IN ('${admin_id}', '${user_id}', '${invite_id}');

INSERT INTO users (id, email, email_verified, username, display_username, role,
                   must_change_credentials, created_at, updated_at)
VALUES
  ('${admin_id}', 'e2e-admin@test.local', true,  'e2eadmin', 'e2eadmin', 'admin', false, NOW(), NOW()),
  ('${user_id}',  'e2e-user@test.local',  true,  'e2euser',  'e2euser',  'user',  false, NOW(), NOW()),
  ('${invite_id}', '${invite_email}',     false, 'tmp_e2einv', 'tmp_e2einv', 'user', true,  NOW(), NOW());

INSERT INTO accounts (id, account_id, provider_id, user_id, password, created_at, updated_at)
VALUES
  (gen_random_uuid()::text, 'e2e-admin', 'credential', '${admin_id}', '${hash_admin}', NOW(), NOW()),
  (gen_random_uuid()::text, 'e2e-user',  'credential', '${user_id}',  '${hash_user}',  NOW(), NOW()),
  (gen_random_uuid()::text, 'e2e-invite-tmp', 'credential', '${invite_id}', '${hash_invite}', NOW(), NOW());

INSERT INTO verifications (id, identifier, value, expires_at)
VALUES (
  gen_random_uuid()::text,
  'invite:${invite_email}',
  '${invite_token_hash}',
  NOW() + INTERVAL '7 days'
);

-- e2euser suit le premier artiste avec des concerts au moins 2 jours dans le futur
-- (évite qu'un concert "aujourd'hui" expire pendant une suite de tests longue)
INSERT INTO user_artistes (user_id, artiste_normalise, artiste_affiche, artiste_id)
SELECT '${user_id}', a.nom_norm, a.nom, a.id
FROM artistes a
WHERE EXISTS (
  SELECT 1 FROM concerts c
  WHERE c.artiste_id = a.id AND c.date > CURRENT_DATE + INTERVAL '2 days'
)
LIMIT 1
ON CONFLICT DO NOTHING;

-- Peuple user_matches pour les concerts futurs de l'artiste suivi par e2euser
INSERT INTO user_matches (user_id, concert_id)
SELECT '${user_id}', c.id
FROM concerts c
JOIN user_artistes ua ON ua.artiste_id = c.artiste_id AND ua.user_id = '${user_id}'
WHERE c.date > CURRENT_DATE + INTERVAL '2 days'
ON CONFLICT DO NOTHING;

SELECT username, role FROM users WHERE username IN ('e2eadmin', 'e2euser', 'tmp_e2einv') ORDER BY username;
SQL
}

generer_sql \
  "$E2E_ADMIN_ID" "$E2E_USER_ID" "$E2E_INVITE_ID" \
  "$HASH_ADMIN" "$HASH_USER" "$HASH_INVITE" \
  "$E2E_INVITE_EMAIL" "$INVITE_TOKEN_HASH" \
  | ssh "$HOST" "su - postgres -c 'psql strapontin -v ON_ERROR_STOP=1'"

echo "Comptes E2E créés sur $HOST."
