---
name: Restauration du dev depuis la prod
description: >
  Remplace la base du LXC dev par une copie de la base de prod, en conservant la
  configuration propre au dev (app_config chiffrée avec le secret du dev, .env du dev) et
  en retirant ce qui ne doit pas y être actif (abonnements push, sessions de prod) et en
  anonymisant les utilisateurs réels (hors administrateur et comptes E2E).
when_to_use: >
  Quand l'utilisateur demande de rafraîchir, réinitialiser ou recopier la base du dev
  depuis la prod, ou quand des données de dev divergentes faussent un test.
---

**Jamais `scripts/restore.sh` pour ce cas** : il restaure aussi le `.env` de l'archive (secrets et profil de prod), et le dev utiliserait alors les vraies clés Resend, Ticketmaster et VAPID.

L'opération écrase la base du dev : la confirmer avec l'utilisateur avant l'étape 3. Accès aux conteneurs : `.claude/scripts/acces.local.sh` (voir `acces.exemple.sh`), noté `acces` ci-dessous.

## 1. Préparer

- Démarrer le dev s'il est arrêté : `acces hote exec "pct start 114"`.
- Sauvegarder la configuration du dev, chiffrée avec son propre `APP_CONFIG_SECRET` :
  ```bash
  acces dev exec "su postgres -c 'pg_dump -d strapontin --data-only -t app_config' > /tmp/app_config_dev.sql && chmod 644 /tmp/app_config_dev.sql"
  ```
  Fichiers dans `/tmp` et non `/root` : l'utilisateur `postgres` ne peut pas lire `/root` (`pg_restore` et `\i` échouent, après le `dropdb`).

## 2. Copier la base de prod

```bash
acces prod exec "su postgres -c 'pg_dump -Fc -d strapontin -f /tmp/strapontin_prod.dump'"
acces prod copier /tmp/strapontin_prod.dump dev /tmp/strapontin_prod.dump
acces prod exec "rm -f /tmp/strapontin_prod.dump"
acces dev exec "chmod 644 /tmp/strapontin_prod.dump"
```

## 3. Restaurer sur le dev (après confirmation)

```bash
acces dev exec "systemctl stop strapontin-web strapontin-worker"
acces dev exec "su postgres -c 'dropdb strapontin && createdb -O strapontin strapontin'"
acces dev exec "su postgres -c 'pg_restore --no-owner --role=strapontin -d strapontin /tmp/strapontin_prod.dump'"
```

Erreurs attendues de `pg_restore` : celles liées à l'extension `dblink`, présente en prod et absente du dev. Toute autre erreur arrête la procédure.

Puis, dans une seule transaction, remettre la configuration du dev, retirer ce qui appartient à la prod et **anonymiser les utilisateurs réels** (pas de notification reçue par un vrai utilisateur depuis le dev, conformité RGPD). Cette étape précède le redémarrage de l'étape 4 : web et worker restent arrêtés tant que les adresses réelles existent. Sont conservés tels quels : les administrateurs (`role <> 'user'`, dont `kwaite`) et les comptes E2E (`e2euser`, `tmp_e2einv`). Tables qualifiées `public.` : le fichier issu de `pg_dump` vide le `search_path`.

```sql
BEGIN;
DELETE FROM public.app_config;
\i /tmp/app_config_dev.sql
DELETE FROM public.push_abonnements;
DELETE FROM public.spring_session_attributes;
DELETE FROM public.spring_session;

CREATE TEMP TABLE cibles AS
  SELECT id, row_number() OVER (ORDER BY created_at, id) AS n
  FROM public.users
  WHERE role = 'user' AND username NOT IN ('e2euser', 'tmp_e2einv');
UPDATE public.users u SET
  username = 'utilisateur' || c.n,
  display_username = 'Utilisateur ' || c.n,
  email = 'utilisateur' || c.n || '@anonyme.invalid',
  pending_email = NULL,
  two_factor_enabled = false,
  two_factor_secret = NULL
FROM cibles c WHERE u.id = c.id;
UPDATE public.accounts SET password = '!' WHERE user_id IN (SELECT id FROM cibles);
DELETE FROM public.session_appareils WHERE user_id IN (SELECT id FROM cibles);
DELETE FROM public.push_differes WHERE user_id IN (SELECT id FROM cibles);
COMMIT;
```

Vérifier avant de redémarrer : `SELECT username, email, role FROM users` ne montre plus que `kwaite`, les comptes administrateur/E2E et des `utilisateurN@anonyme.invalid`. Les adresses en `.invalid` ne sont jamais livrables ; le mot de passe `!` ne correspond à aucun hash. Si une table portant des données personnelles est ajoutée au schéma, l'ajouter ici.

(fichier SQL local passé à `acces dev psql <fichier>`, le `\i` lisant le chemin côté dev).

## 4. Redémarrer et vérifier

```bash
acces dev exec "systemctl start strapontin-web strapontin-worker"
bash scripts/seed-e2e.sh root@192.168.20.78
rm -f e2e/tests/e2e/.auth-*.json
```

- Démarrage propre : journaux web et worker sans erreur, migrations Flyway appliquées.
- Connexion admin et utilisateur E2E, console du navigateur sans erreur.

## 5. Nettoyer

- Supprimer la copie de prod du dev, qui contient des données personnelles : `acces dev exec "rm -f /tmp/strapontin_prod.dump /tmp/app_config_dev.sql"`.
- Arrêter le dev s'il n'était pas démarré au départ : `acces hote exec "pct stop 114"`.
