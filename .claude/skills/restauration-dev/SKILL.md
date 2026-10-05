---
name: Restauration du dev depuis la prod
description: >
  Remplace la base du LXC dev par une copie de la base de prod, en conservant la
  configuration propre au dev (app_config chiffrée avec le secret du dev, .env du dev) et
  en retirant ce qui ne doit pas y être actif (abonnements push, sessions de prod).
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
  acces dev exec "su postgres -c 'pg_dump -d strapontin --data-only -t app_config' > /root/app_config_dev.sql"
  ```

## 2. Copier la base de prod

```bash
acces prod exec "su postgres -c 'pg_dump -Fc -d strapontin -f /tmp/strapontin_prod.dump'"
acces prod copier /tmp/strapontin_prod.dump dev /root/strapontin_prod.dump
acces prod exec "rm -f /tmp/strapontin_prod.dump"
```

## 3. Restaurer sur le dev (après confirmation)

```bash
acces dev exec "systemctl stop strapontin-web strapontin-worker"
acces dev exec "su postgres -c 'dropdb strapontin && createdb -O strapontin strapontin'"
acces dev exec "su postgres -c 'pg_restore --no-owner --role=strapontin -d strapontin /root/strapontin_prod.dump'"
```

Erreurs attendues de `pg_restore` : celles liées à l'extension `dblink`, présente en prod et absente du dev. Toute autre erreur arrête la procédure.

Puis, dans une seule transaction, remettre la configuration du dev et retirer ce qui appartient à la prod. Tables qualifiées `public.` : le fichier issu de `pg_dump` vide le `search_path`.

```sql
BEGIN;
DELETE FROM public.app_config;
\i /root/app_config_dev.sql
DELETE FROM public.push_abonnements;
DELETE FROM public.spring_session_attributes;
DELETE FROM public.spring_session;
COMMIT;
```

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

- Supprimer la copie de prod du dev, qui contient des données personnelles : `acces dev exec "rm -f /root/strapontin_prod.dump /root/app_config_dev.sql"`.
- Arrêter le dev s'il n'était pas démarré au départ : `acces hote exec "pct stop 114"`.
