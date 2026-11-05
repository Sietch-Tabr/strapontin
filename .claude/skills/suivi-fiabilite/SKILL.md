---
name: Suivi Fiabilité Prod
description: Exécute un check delta complet ("360°") de la fiabilité des données de production Strapontin — déploiements, logs système LXC/hôte, cadence Quartz, intégrité BDD, correspondance floue TM/Eventim/Deezer, couverture exhaustive par rotation + tirage aléatoire complémentaire, vérification adversariale par subagent avant conclusion, revue de l'algorithme (correction automatique, parcours utilisateur) et plan de réparation des données exécuté après accord. À invoquer à chaque commande `date` de l'utilisateur dans ce projet, ou explicitement via /suivi-fiabilite.
---

Audit continu démarré le 2026-07-06 : chaque exécution compare l'état actuel au dernier point de contrôle enregistré dans `project_suivi_fiabilite_prod.md`, jamais un état des lieux isolé. Contexte, incidents passés et justification de chaque règle ci-dessous : [HISTORIQUE.md](HISTORIQUE.md).

**Prérequis locaux, non versionnés** (le skill ne fonctionne pas sans eux) :

- `.claude/scripts/acces.local.sh` : accès aux conteneurs prod/dev/CI et à leur hôte, à créer depuis `.claude/scripts/acces.exemple.sh`.
- `~/.claude/projects/<projet>/memory/project_suivi_fiabilite_prod.md` : état du suivi (historique des checks, bugs en cours), dans la mémoire personnelle Claude Code du projet (`<projet>` = chemin absolu du dépôt, `/` remplacés par `-`). Créé au premier check s'il n'existe pas.
- Scripts versionnés du dossier `scripts/` : `run_check.py` (sections 5/7), `run_rotation.py` (section 9), `verifier_rotation.py` (vérification Wikidata/Deezer des lots), `comparer_tm_lot.py` (volet TM de la section 8), `lot_rotation.py` (module partagé).
- `scripts/decrypt_config.py` : déchiffrement ponctuel d'une clé `app_config`, fichier local.
- `snapshots/` et `scripts/curseurs_rotation.json` : résultats et état d'exécution des checks, propres à chaque poste (données de prod, jamais versionnées).

**Contrôles nocturnes** : depuis 3.0.2, les invariants de données (orphelins de clés étrangères, sessions sans appareil, notifications non envoyées, salles multi-départements, salle suivie contaminée, migration Flyway en échec, comptes incohérents, identifiant externe dupliqué, dispersion géographique) sont vérifiés chaque nuit par le worker et publiés dans `signalements_matching` (onglet admin « À traiter »). Le check `signalements_matching_actifs` les remonte : les checks SQL du même nom ci-dessous servent à recouper et à quantifier, pas à les redécouvrir.

**Équilibre** : ce suivi porte sur la fiabilité des **données**, pas la traçabilité dev — la section 2 sert à comprendre le contexte, pas à y passer la majorité du temps. Réserver un temps réel à l'investigation exploratoire (section 9) à chaque check.

**Principes non négociables** (voir HISTORIQUE.md pour le contexte de chacun) :

- **Priorité n°1 : tout WARN/ERROR/anomalie — même « connu », « isolé » ou « sans conséquence » — se vérifie occurrence par occurrence, jamais par ratio, par échantillon ni par pattern déjà vu.** Lire le code du chemin concerné, identifier chaque occurrence (`venueId`/artiste/salle/message), quantifier le volume réel sur au moins un cycle concret, vérifier que l'auto-guérison au cycle suivant est réelle et non supposée. Faire correspondre un WARN à un « type de message connu » n'est jamais une vérification : quantifier l'impact en aval (ratio `candidats`/`enrichis`, tendance sur plusieurs jours) ; un motif stable n'est pas bénin par son ancienneté, il peut révéler un garde-fou mal calibré (cf. #1057).
- Un total agrégé stable ne prouve jamais que les éléments qui le composent sont inchangés — revérifier les identités individuelles, pas seulement le chiffre.
- Une conclusion "pas de corruption confirmée" basée uniquement sur la BDD (absence de log, `artiste_id`/`salle_id` NULL) ne suffit pas à écarter un doute — corroborer sur internet dès qu'un nom d'artiste/salle/événement semble suspect, pas seulement pour l'échantillonnage aléatoire de la section 9.
- Toute question nécessitant un arbitrage utilisateur doit être posée dans le tour où le constat est fait, jamais notée "en attente" sans être posée.
- Recherche web autorisée en standing pour ce skill, à tout moment du check, pour corroborer un constat de données (pas limitée à la section 9). Le déchiffrement `app_config` (`scripts/decrypt_config.py`) reste soumis à une demande explicite à chaque usage.
- Ticket créé seulement après confirmation du bug, verdict du subagent (section 11) ET accord explicite de l'utilisateur (section 14).
- Le check ne s'arrête pas au constat : chaque anomalie confirmée reçoit une réparation proposée puis exécutée par moi après accord (section 13), et la revue de l'algorithme cherche ce qui aurait dû l'éviter ou la corriger seul, parcours utilisateur compris (section 12).
- « Déjà documenté » dans la mémoire n'est pas une preuve : la documentation peut être une classification erronée jamais revérifiée, ou un statut « corrigé » jamais appliqué en prod. Revérifier l'état réel (base, logs, code) tant qu'aucun correctif n'est déployé et vérifié.
- **Complémentaire à la priorité n°1, sur la population générale (pas seulement les anomalies déjà signalées)** : couverture rotative par curseur persisté (section 9) sur les tables entières (salles, artistes, concerts), garantissant 100% de couverture sur un nombre borné de checks, en plus d'un tirage aléatoire indépendant à chaque check — le temps n'est pas une contrainte.
- **Un résumé `WebFetch` d'une entité Wikidata peut halluciner l'identité d'un Q-ID cité en passant** (Arkéa Arena le 07/08, Summum le 14/08) : toute affirmation « P131 = X (QNNN) » se recroise en interrogeant QNNN lui-même (`Special:EntityData/QNNN.json`).

## 1. Lire l'état avant tout

Lire `~/.claude/projects/<projet>/memory/project_suivi_fiabilite_prod.md` en entier :

- Dernière entrée de « Historique des checks » → borne basse de ce check.
- Sections « Bug(s) confirmé(s) » en cours → ne pas re-signaler avec un nouveau ticket ce qui est déjà pris en charge, mais **vérifier l'état réel en base/logs à chaque check** (pas seulement lire le statut noté) tant qu'aucun correctif de code n'est déployé et confirmé fonctionnel — un statut "en attente de correctif"/"connu" peut être resté figé sur une observation ancienne pendant que le problème continuait d'évoluer (cf. principe non négociable ci-dessus).

## 2. Santé système et déploiements

```
.claude/scripts/acces.local.sh prod exec "journalctl -u strapontin-web -u strapontin-worker --since '<dernier check>' --no-pager | grep -iE 'Stopping strapontin|Started strapontin|Starting strapontin'"
```

- Tout restart : vérifier si c'est un vrai déploiement (`.claude/scripts/acces.local.sh prod exec "ls -la /opt/strapontin/"` → tailles `web.jar`/`worker.jar` vs `.jar.prev`) et identifier le commit (`git log --oneline -5 main` ; sinon `git fetch origin main --quiet` puis `git log --oneline -5 origin/main`).
- **Montée de version : lire le changelog utilisateur** (règle du 17/10/2026). Si le restart est un déploiement (nouveau tag), lire dans `front/src/pages/changelogVersions.js` les entrées apparues depuis la dernière version vue (`git log -p -- front/src/pages/changelogVersions.js`) et rejouer chaque promesse faite aux utilisateurs par une requête SQL ou un `grep` qui prouve qu'elle tient en production (exemples dans HISTORIQUE.md). Une promesse contredite devient une anomalie des sections 11 à 14. Consigner les contrôles dans la mémoire, section « Contrôles issus du changelog » ; skill rechargé après une longue pause : rattraper les 5 dernières versions.
- **Taille de la base** :
  ```sql
  SELECT pg_size_pretty(pg_database_size('strapontin'));
  SELECT relname, pg_size_pretty(pg_total_relation_size(relid)) FROM pg_catalog.pg_statio_user_tables ORDER BY pg_total_relation_size(relid) DESC LIMIT 10;
  ```
  Comparer au dernier check — croissance brutale ou table qui grossit sans rapport avec son usage = signal d'alerte (à l'inverse, une purge trop agressive est aussi un signal).
- Recherche par motif texte sur salle/artiste : toujours envelopper dans `unaccent()` (`unaccent(nom) ILIKE '%motif%'`), jamais un motif brut — `ILIKE` n'est pas insensible aux accents en PostgreSQL.
- Échecs de job : `grep -iE 'JobExecutionException|job.*échoué'`.
- Erreurs génériques : `grep -iE 'ERROR'`, en excluant uniquement les patterns déjà confirmés connus dans la mémoire (jamais un pattern générique type "toute erreur Eventim" sans revérifier que le problème est toujours actif). Ne jamais filtrer `at fr.strapontin` d'une stack trace — ça masque l'appelant réel.

## 3. Logs système (LXC 113 + hôte Proxmox)

Distinct de la section 2 (logs applicatifs `strapontin-web`/`strapontin-worker` uniquement) — porte sur l'OS du LXC et l'hôte Proxmox, tous services confondus. Ajouté au skill le 2026-08-10 sur demande explicite de l'utilisateur (systématique à partir de ce check, pas seulement en réaction à un symptôme comme les précédents de juillet — cf. HISTORIQUE.md).

- **Logs système du LXC 113** (priorité warning et au-dessus, tout service) :
  ```
  .claude/scripts/acces.local.sh prod exec "journalctl --no-pager --since '<dernier check>' -p warning"
  ```
  Chercher OOM killer (`grep -iE 'out of memory|oom-kill|killed process'`), échecs de service tiers (PostgreSQL, sshd, cron, systemd-timesyncd), erreurs disque/filesystem. Exclure uniquement les patterns déjà confirmés bénins en mémoire — jamais un motif générique non revérifié.
- **Tentatives d'accès SSH non autorisées** :
  ```
  .claude/scripts/acces.local.sh prod exec "journalctl -u ssh --no-pager --since '<dernier check>' | grep -iE 'Failed password|Invalid user|authentication failure'"
  ```
  Noter le volume et les IPs sources. Un bruit résiduel stable de scans internet n'est pas alarmant en soi, mais un pic soudain ou une IP qui insiste (dizaines de tentatives rapprochées) mérite d'être creusé avant d'être classé "bruit normal".
- **Journaux d'accès Nginx Proxy Manager (CT 100, lecture seule)** : `.claude/scripts/acces.local.sh hote exec "pct exec 100 -- grep -h '<jj/Mon/aaaa>' /data/logs/proxy-host-26_access.log"`. Format : `[date] - STATUT STATUT - METHODE https hôte "uri" [Client ip] …` (le statut est le 4e champ). Compter les 5xx de la fenêtre, lister les `POST /` et les IP qui insistent, et recouper toute erreur applicative (stack web) avec le statut réellement servi avant de conclure à une réponse 500 : une exception levée après l'envoi de la réponse n'apparaît pas en 5xx (cas du 19/10/2026, clé de session dupliquée sans 5xx).
- **Espace disque LXC 113** : `.claude/scripts/acces.local.sh prod exec "df -h /"` — comparer au dernier check, croissance anormale = signal (backups mal purgés, logs qui s'accumulent hors `logs_admin`).
- **Hôte Proxmox** (charge, mémoire, reboot) :
  ```
  .claude/scripts/acces.local.sh hote exec "uptime; free -h"
  ```
  Comparer la charge à la fourchette habituelle (~0.2-0.5 documentée dans l'historique). Un écart identifié se creuse jusqu'au process en cause (`ps aux --sort=-%cpu | head`) avant de conclure quoi que ce soit — jamais deviner une cause par corrélation (cf. précédent du 14/07 : pic de charge causé par des process `ollama`, découvert par inspection directe, pas par supposition). Un reboot de l'hôte se corrèle avec les logs des conteneurs (patch noyau de maintenance = bénin, cf. 19/07 ; coupure imprévue = à investiguer).

## 4. Cadence des jobs Quartz

Vérifier sur la fenêtre (liste complète : `SELECT * FROM fetch_jobs`, 19 lignes depuis 3.1.1) : `fetch:region` (03h00), `reconciliation:nocturne` (04h15 ; chercher `démarré`/`terminé sallesVerifiees=…evenementsFusionnesArtiste=…`, les fusions automatiques une par une et les lignes `fiche source périmée … ignorée`), `fetch:artistes:batch` (cycles 1, 5, 7, …, 23h) et `fetch:salles:all` (aux :30), `recalculer:matches`, `notifier:digest`, `revoquer:admins` (horaire), `suivi:cycles-jobs` (aux :30, `enRetard=0`), `fetch:sites-salles` (14h), `notifier:version` (11h), purges quotidiennes (00:00 `purger:concerts`/`-hors-portee`/`-non-confirmes`, 00:05, 00:06, 00:15), `gerer:comptes-inactifs` (00:20) et `gerer:invites-expires` (00:25).

Raccourci : `SELECT * FROM fetch_jobs;` (table live, pas `fetch_jobs_historique`) donne en une requête le dernier statut de tous les jobs — en complément du detail journalctl, pas en remplacement.

- **Dérive de volume et retry ciblé** (jalon 2.9.0) : `journalctl -u strapontin-worker | grep -iE 'derive|retry:lots'` — `DeriveVolumeFetchService` (appelé à chaque cycle des jobs multi-lots) loggue en WARN une chute anormale du volume inséré/mis à jour vs l'historique récent ; `RetryLotsService` loggue en INFO un retry planifié et en WARN un échec de planification de retry. Silencieux par construction quand tout va bien (pas de ligne = sain), donc à chercher explicitement plutôt qu'à déduire de leur absence dans les grep habituels de la section 5.

## 5. Données (jamais sauter cette section)

Utiliser un fichier SQL temporaire (`.claude/scripts/acces.local.sh prod psql /tmp/check.sql`) plutôt que d'empiler les guillemets imbriqués dans une commande unique. **Chemins** : les commandes `python3 scripts/...` se lancent depuis `.claude/skills/suivi-fiabilite/` (pas depuis la racine du dépôt), et `acces.local.sh psql` copie le fichier sous `/tmp/acces_<nom>.sql` sur l'hôte : deux agents qui donnent le même nom de fichier s'écrasent (le subagent préfixe les siens, ex. `sub3_`). **Eventim** : l'API `public-api.eventim.com` répond 403 à tout User-Agent de type navigateur (y compris celui de `urllib` ou un UA personnalisé en Python) et 200 à `curl` par défaut : l'interroger avec `curl` depuis le LXC prod (`acces.local.sh prod exec "curl -s --max-time 30 '<url>'"`), les pages `eventim.fr` n'étant pas lisibles par l'agent de lecture web.

- **Bilan de fenêtre en un appel** : `python3 scripts/run_check.py bilan_fenetre --borne "AAAA-MM-JJ HH:MM"` (borne = dernier check, heure de Paris) regroupe taille de base, jobs, insertions par heure et par source, `logs_admin` warn/error, fusions et alignements, signalements par type, suspects, absences, fiches figées, file « à confirmer », notifications et sessions : le point de départ des sections 2 à 7, le détail d'une ligne suspecte se creuse ensuite à la main. Un diff de plus de 60 lignes est résumé par `run_check.py` (snapshot complet dans `snapshots/`).
- **Fiches flaguées (`concerts_a_confirmer`)** : le check donne par fiche les matches, les observations par source avec leur dernière mise à jour et les fiches jumelles de la salle (même artiste ou même jour). Une jumelle rafraîchie à une autre date ou heure signe un produit republié (ancienne fiche fantôme), une source plus renvoyée depuis des semaines un produit retiré ; la preuve reste le site officiel de la salle. **Eventim** : `python3 scripts/eventim_produits.py "terme" --ids <produits>` dit si un produit existe encore et dans quelle salle (via `curl` sur le LXC).
- **Concerts insérés** par source depuis le dernier check : `SELECT source, count(*) FROM concerts WHERE first_seen > '<dernier check>' GROUP BY 1`. Si 0 sur plusieurs cycles, vérifier `SELECT max(first_seen) FROM concerts` (gel plus long que la fenêtre) et le contexte saisonnier (juillet-août plus calme, normal).
- **`artiste_sources.necessite_intervention_le`** (proche de 0 sauf incident réel ; la colonne n'existe pas sur `salle_sources`) et **`suspect_depuis`** des deux tables (oscillation quotidienne normale : Opéra-Comique et Palais Garnier TM sont suspects chaque jour, TM y renvoie 0 légitimement).
- **Doublons salle** (cross-source) : `python3 scripts/run_check.py doublons_salle_nom_norm` (query figée, voir `scripts/checks/doublons_salle_nom_norm.sql` — jamais retaper la requête de mémoire, cf. HISTORIQUE.md). Le diff affiché contre le dernier snapshot dit ce qui a changé depuis le dernier check (stable/décroissant, jamais croissant sans explication). Ne jamais fusionner sur seule ressemblance textuelle — vérifier ville/adresse réelle.
  ⚠️ Ne jamais traiter un nom_norm partagé comme automatiquement sûr ("homonymes légitimes") — un mauvais rapprochement Wikidata produit _par construction_ ce partage. Vérifier pour chaque ligne (Wikidata + `concerts.ville` de chaque `salle_id`) que les deux villes sont bien deux entités réelles distinctes, pas la conséquence d'un bug déjà actif ailleurs.
- **Doublons salle par suffixe ville/qualificatif** (variante non enrichie vs déjà enrichie) : `python3 scripts/run_check.py doublons_salle_suffixe` (`scripts/checks/doublons_salle_suffixe.sql`). Filtrer les faux positifs (noms génériques recouvrant des villes différentes — croiser `concerts.ville`/`dept` réel, jamais juste le texte). Chaque ligne du résultat doit être vérifiée individuellement, jamais classée en bloc par catégorie apparente (cf. HISTORIQUE.md, récidive du 06/08/2026).
- **Doublon fuzzy artiste** :
  ```sql
  SELECT c1.artiste, c2.artiste, c1.date, c1.salle FROM concerts c1
  JOIN concerts c2 ON c1.salle_id = c2.salle_id AND c1.date = c2.date
    AND c1.heure IS NOT DISTINCT FROM c2.heure AND c1.id < c2.id
    AND c1.artiste_norm <> c2.artiste_norm AND similarity(c1.artiste_norm, c2.artiste_norm) > 0.4
  WHERE c1.date >= current_date;
  ```
- **`logs_admin`** warn/error depuis le dernier check : `WHERE ts > '<dernier check>'::timestamptz` (`ts` est un `timestamp with time zone` depuis 3.0.0 ; l'ancien piège epoch millis est décrit dans HISTORIQUE.md). Toujours lister le détail, jamais un count seul.
- **Utilisateurs** : nb comptes, caps (500 artistes/200 salles/10 push), révocations admin en attente, TOTP suspects.
- **Sauvegardes** : `.claude/scripts/acces.local.sh prod exec "systemctl list-timers 'strapontin-backup*' --no-pager"` — LAST/NEXT cohérents (00:00 NAS, 01:00 local).

## 6. Toutes les sources, pas seulement TM/Eventim

- **Eventim par lieu** (depuis v3.0.1, Digitick retirée) : `SELECT count(*) FROM signalements_matching WHERE type='chute_resultats_salle' AND resolu_le IS NULL` + salles suivies avec `salle_sources.libelle_source` renseigné et `dernier_nb_resultats`. Une chute brutale signalée = billetterie du lieu probablement déplacée (cas L'Empreinte, invisible 3 mois avant v3.0.1).
- **Sites de salles** (3.0.6, `fetch:sites-salles` à 14h : Le Triton, EMB Sannois, Usine à Chapeaux, Allmol) : `fetch_jobs` (durée, insérés au premier passage puis `0 insérés / N mis à jour` = idempotent), journal sans WARN/ERROR/robots, volume par site (`SELECT split_part(split_part(url,'//',2),'/',1), count(*) FROM observations WHERE source='site_salle' GROUP BY 1`). Contrôler : une seule salle par lieu (Sannois : une salle avec observations `eventim` et `site_salle`), villes renseignées (warn `villes-salles`), heure NULL (Usine à Chapeaux), titres qui ne sont pas des artistes (jam session, soirée), plusieurs graphies d'un même lieu (Allmol).
- **Deezer** : `journalctl -u strapontin-worker | grep -i 'Deezer'` — chercher erreurs/warn spécifiquement (`grep -iE 'erreur|error|warn'`), pas juste compter.
- **Wikidata Search (REST) / Nominatim** (module web, ajout interactif) : `journalctl -u strapontin-web | grep -i 'Nominatim\|rechercherWikidata\|wbsearchentities'` — noter l'absence d'activité plutôt que l'ignorer (un chemin jamais exercé peut cacher une régression).
- **Notifications push** : `SELECT count(*), count(DISTINCT user_id) FROM push_abonnements` + cap 10/user + `grep 'Push digest envoyé\|Push: échec\|Push: statut inattendu'` dans les logs (pas seulement les échecs email). Abonnements jamais nettoyés : `SELECT count(*) FROM push_abonnements WHERE cree_le < now() - interval '90 days'`.
  **Délivrance et avertissement des utilisateurs** : par abonnement, service push (`fcm.googleapis.com`, `web.push.apple.com`…), `echecs_consecutifs` (retrait au 5e échec consécutif, HTTP 410 = révocation immédiate), dernier envoi réussi (`digest — N email(s), N push envoyés` de `logs_admin`, zéros compris) ; un service dont aucun envoi n'a jamais réussi est une panne de l'application. Par compte : `user_config.dernier_appareil_retire_le`, `cause_dernier_retrait_appareil`, `avertissement_canal_perdu_le`, push coché sans appareil (toléré par le code tant que l'email est actif, `CanalNotificationPerdu`, mais l'utilisateur n'est pas averti : à rapporter), fréquence « jamais » avec push coché (doit être 0), email d'avertissement réellement envoyé, et le texte que recevra l'utilisateur (`core.EchecPush.causeLisible`), qui ne doit pas lui imputer une panne de l'application. Récapitulatif hebdomadaire : dimanche 12h (`notifier:digest démarré frequence=hebdomadaire`). Notification de nouvelle version : automatique depuis 3.0.6 (`notifier:version` à 11h, table `notifications_version`), à vérifier : seuls les comptes qui l'ont choisie la reçoivent. Push différés : aucun envoi entre 22h et 7h, `push_differes` vidée à 07:00.
- **Départements d'outre-mer (97x)** : `SELECT dept, count(*) FROM concerts WHERE date >= current_date AND dept LIKE '97%' GROUP BY 1`, salles `dept LIKE '97%'`, départements 97x choisis dans `user_config.departements`. Eventim n'interroge que les villes de salles déjà connues du département : sans salle 97x il n'en découvre jamais ; contrôler à l'API (`postal_code=97xxx`, `top=50`, depuis le LXC prod) que 0 est la réalité. Les concerts 97x actuels viennent de la source site_salle Allmol (971). Ticketmaster : comparer à une requête TM (autorisation de déchiffrer à demander) avec les paramètres exacts de `TicketmasterRegionFetcher` et **toujours un témoin Paris**, avec `locale=*` (sans lui, 0 même pour Paris) ; l'écart entre mon client et celui de l'application n'est pas identifié, donc juger la couverture sur les observations mises à jour par l'application, jamais sur un appel manuel. Ne jamais conclure à partir d'un 0 sans témoin.
- **Correctness des notifications, pas seulement leur volume** (ajouté le 08/09/2026) — deux sens, aucun ne remplace l'autre :
  1. **Faux négatif** (match éligible jamais notifié) : `python3 scripts/run_check.py notifications_non_envoyees` (`scripts/checks/notifications_non_envoyees.sql`) — `user_matches` réellement éligibles (préférence active, artiste/salle suivi) mais jamais notifiés alors que le concert est connu depuis plus de 24h. Toute ligne retournée = un digest resté silencieusement bloqué pour cet utilisateur (quota Resend, clé API absente, VAPID mal configuré...), à investiguer comme n'importe quelle autre anomalie de la section 7.
  2. **Faux positif inverse** (flag posé sans envoi réel confirmé) : `SELECT um.user_id, u.email, c.artiste, um.notification_envoyee_le FROM user_matches um JOIN users u ON u.id=um.user_id JOIN concerts c ON c.id=um.concert_id WHERE um.notification_envoyee_le > '<dernier check>' ORDER BY um.notification_envoyee_le` puis, pour chaque ligne, `journalctl -u strapontin-worker --since '<timestamp>' --until '<timestamp+1min>' | grep -iE 'Digest email envoyé|Push digest envoyé'` — le code ne pose `notification_envoyee_le` qu'après succès d'un canal, donc l'absence de ligne de confirmation correspondante signalerait un utilisateur qui croit avoir été notifié sans l'avoir vraiment été. Limité par la rétention `journalctl` (pas indéfinie), donc scopé à la fenêtre du check comme le reste de la section 5.
- **`user_config.departements` vide avec salles suivies** : `SELECT uc.user_id FROM user_config uc WHERE (uc.departements IS NULL OR uc.departements = '{}') AND EXISTS (SELECT 1 FROM user_salles us WHERE us.user_id = uc.user_id)`.
- **Sessions** (`spring_session`) : volumétrie, sessions expirées résiduelles (`expiry_time` en millis), `principal_name` distincts. À réintégrer périodiquement même si RAS répété.
- **`session_appareils`** (ajouté le 08/09/2026) : `python3 scripts/run_check.py sessions_sans_appareil` (`scripts/checks/sessions_sans_appareil.sql`) — toute session authentifiée (`principal_name` renseigné) sans ligne `session_appareils` correspondante. `AppareilEnregistrementInterceptor` n'enregistre l'appareil qu'au premier appel API suivant le login (pas le login lui-même) — un raté de cet enregistrement rendrait une session authentifiée invisible aux pages "Sessions actives" de `/parametres`/`/admin`, jamais vérifié jusqu'ici.
- **Impact utilisateur réel d'une salle contaminée** (ajouté le 08/09/2026) : `python3 scripts/run_check.py impact_utilisateur_contamination` (`scripts/checks/impact_utilisateur_contamination.sql`) — croise `user_salles`/`user_artistes` contre les salles déjà signalées comme géographiquement incohérentes ou en doublon par suffixe (`geo_incoherence_salles`/`doublons_salle_suffixe`, logique dupliquée pour permettre le JOIN direct — à garder synchronisée si ces deux fichiers changent). `orphelins_fk` vérifie que la salle suivie existe, jamais qu'elle est fiable ; ce check répond directement à "un vrai utilisateur est-il touché ?" sans le revérifier manuellement à chaque contamination trouvée (fait ad hoc pour #1177 le 04-08/09, désormais systématique).

## 7. Intégrité et robustesse des données

Audite la qualité intrinsèque des données stockées, indépendamment de tout bug de code — périodique, pas nécessairement à chaque check si RAS répété, mais pas plusieurs jours de suite.

- **Intégrité référentielle (orphelins)** : `python3 scripts/run_check.py orphelins_fk` (`scripts/checks/orphelins_fk.sql`). Résultat non nul = signal fort (contraintes FK ne devraient jamais avoir d'orphelins).

- **Signalements de matching actifs** : `python3 scripts/run_check.py signalements_matching_actifs` agrège les 4 sources de l'onglet « À traiter » (`signalements_matching`, `necessite_intervention_le`, `contamination_signalee`, `doublon_signale`), pas seulement la première. Un signalement non résolu est un candidat à investiguer, jamais classé en bloc par type : chaque élément, et chaque concert à confirmer, aboutit à une vraie anomalie (sections 13 et 14), un faux positif (section 13) ou un déjà résolu. Types : `departement_sans_concert` (Corse = code « 20 »), `creneau_double_meme_source`, `artistes_meme_nom_sans_espaces`, `wikidata_coordonnees_divergentes`, `dispersion_sources_salle`, `date_festival_ecartee`. Un signalement résolu à la main est recréé la nuit suivante tant que la condition reste vraie (#1418) ; `clore()` ferme seul ceux dont l'entité n'est plus en anomalie (à vérifier au contrôle post-nocturne).

- **File « concerts à confirmer »** (`suppression_suggeree_le`) : `python3 scripts/run_check.py concerts_a_confirmer`. Chaque ligne vérifiée individuellement, **site officiel de la salle d'abord**, puis billetterie, puis agrégateurs (un agenda tiers reste périmé : Diderot annoncé jusqu'au 26/11 alors que la salle s'arrêtait le 22/10). Inexistant ou annulé prouvé : suppression (durable, la source ne republie plus). Séance réelle retirée d'une billetterie : à garder, sans jamais remettre le compteur à zéro (temporaire, #1427). ⚠️ Plusieurs lignes avec le même `absences_consecutives` et la même date de flag signent une cause commune.

- **Alertes `logs_admin source='coherence-salle'`** (jalon 2.8.8, `CoherenceMultiSalleService`, journalisation seule) : `SELECT ts, message FROM logs_admin WHERE source='coherence-salle' AND ts > ...` — deux familles de message, "salle potentiellement multi-lieux — mesententes=…" (nom divergent entre sources internes pour un même `salle_id`, ex. Eventim `internal:` vs nom canonique) et "doublon de salle potentiel sans wikidata_id ni concert partagé" (généralise `doublons_salle_suffixe.sql` aux paires sans `wikidata_id` d'aucun côté). Fort taux de faux positifs sur simple variance de nommage (à vérifier via `salle_sources`/`concerts.ville`/`concerts.dept`, jamais le texte seul) — chaque ligne individuellement comme pour `coherence-wikidata`, jamais par catégorie apparente.

- **Complétude concerts futurs** : `salle_id IS NULL`, `ville IS NULL/''`, `url IS NULL/''`, `date` incohérente. `salle_id IS NULL` ne devrait normalement jamais arriver (upsert auto) — creuser si non nul, surtout si de nouvelles instances apparaissent.

- **Fraîcheur des données déjà en base** : `python3 scripts/run_check.py fraicheur_concerts` (`scripts/checks/fraicheur_concerts.sql` — regroupe déjà par `artiste_norm` et calcule `jamais_retouche`).

  ⚠️ `PersisteurConcertsDb` pose `updated_at = now()` **sans condition** à chaque upsert réussi — non rafraîchi depuis 14j signifie littéralement absent de **toute** réponse TM/Eventim depuis 14j, jamais "rien n'a changé" sans preuve.

  Pour chaque artiste/salle en tête : (1) comparer `artiste_sources.identifiant_externe` (DB) contre l'attractionId réellement envoyé dans les logs `fetch:artistes:batch` (`grep -o 'attractionId=[^&]*'`) — un mismatch, même d'un caractère de casse, peut être l'ID valide d'un autre artiste réel ; (2) si `salle_id IS NULL`, c'est un résidu structurel distinct (aucune salle candidate) ; (3) si l'ID envoyé est correct et le cycle réussit sans erreur, vérifier en direct sur l'API source (`countryCode=FR`) si l'artiste a bien 0 événement — corroborer par recherche web (tribute band ? tournée annulée ?) avant de conclure.

- **Observations figées** (#1417) : `SELECT count(*) FILTER (WHERE maj < now() - interval '14 days'), count(*) FROM (SELECT e.id, max(o.updated_at) maj FROM evenements e JOIN observations o ON o.evenement_id = e.id WHERE e.date >= current_date GROUP BY e.id) x` (1 523 sur 7 817 le 22/10, presque toutes Ticketmaster seul) : une variation brutale d'un check à l'autre est un signal.
- **Hommages rattachés à l'artiste imité** (#1421) : `SELECT e.artiste_norm, e.date, e.artiste_id IS NOT NULL FROM evenements e WHERE e.date >= current_date AND EXISTS (SELECT 1 FROM observations o WHERE o.evenement_id = e.id AND o.url ~* '(live-tribute|-tribute-|hommage)')` : tout événement avec `artiste_id` (donc avec des matches utilisateur) est une anomalie ; les autres portent le nom de l'artiste imité.
- **Même produit Eventim sous plusieurs fiches** (#1408, #1422) : `SELECT o.url, count(DISTINCT o.evenement_id) FROM observations o JOIN evenements e ON e.id = o.evenement_id WHERE o.source = 'eventim' AND e.date >= current_date GROUP BY 1 HAVING count(DISTINCT o.evenement_id) > 1` ; le check `creneau_double` ne les voit pas (heure ou titre différents).
- **Écart de position salle / Wikidata** : sur le lot de rotation, comparer `latitude`/`longitude` au P625 pour toutes les salles, pas seulement au-delà du seuil de signalement de 1 000 m (`verifier_rotation.py salles` le fait) ; une salle `origine_position = 'sources'` à 600-1 000 m de Wikidata est à aligner (Zénith La Villette, Brest Arena).
- **Statut référentiel admin réel** : `python3 scripts/run_check.py statut_referentiel` (`scripts/checks/statut_referentiel.sql`, première mesure le 02/11/2026 : 492 salles et 140 artistes a_jour) ; à l'origine, reproduire la logique `ReferentielSallesService`/`ReferentielArtistesService` (`a_jour`/`a_verifier`/`invalide`) en SQL pour voir la distribution telle qu'un admin la verrait, pas seulement le compteur brut.

- **Caps utilisateurs** :

  ```sql
  SELECT user_id, count(*) FROM user_artistes GROUP BY 1 HAVING count(*) > 500;
  SELECT user_id, count(*) FROM user_salles GROUP BY 1 HAVING count(*) > 200;
  ```

- **Audit trail auth** : `SELECT count(*), min(...), max(...) FROM logs_admin WHERE source='auth'`. 0 entrée avec une connexion récente confirmée (`spring_session.creation_time`) = signal de mécanisme mort, pas juste absence d'activité.

- **`app_config`** : `SELECT cle FROM app_config ORDER BY cle;` (noms de clés uniquement, jamais lire/déchiffrer sans demande explicite — voir `scripts/decrypt_config.py`). Vérifier présence de `TICKETMASTER_API_KEY`, `RESEND_API_KEY`, `RESEND_WEBHOOK_SECRET`, `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY`, `CONTACT_EMAIL`, `PUBLIC_BASE_URL`, `TM_QUOTA_JSON`, `ALERTE_LOGS_ADMIN_CURSEUR`, `SIGNAL_LIEN` (lien affiché en bannière admin, `AideRestController`/`ConfigAdminService` — absent de cette liste jusqu'au 27/09/2026, lacune de doc pure, la clé existe et fonctionne depuis toujours).

- **`flyway_schema_history`** : `SELECT * FROM flyway_schema_history WHERE success = false;` — doit toujours être vide. Piège : `version` est texte, `MAX(version)` trie lexicalement pas numériquement — se fier à `count(*)`/`success=false`, pas au max apparent.

- **`accounts`** : cohérence 1:1 avec `users`, format bcrypt (`password NOT LIKE '$2%'`), pas de doublon par `user_id`.

- **Référentiel mort** (`salles`/`artistes` jamais liés à aucun concert ni suivi) et **`wikidata_id` dupliqué** entre plusieurs lignes.

- **Cohérence `nom_norm` vs `nom`** :

  ```sql
  SELECT count(*) FROM salles WHERE nom_norm ~ '[A-ZÀ-Ý]' OR nom_norm ~ '['']';
  SELECT count(*) FROM salles WHERE nom_norm NOT LIKE '% %' AND nom LIKE '% %' AND length(nom_norm) > 12;
  ```

  Toute occurrence non nulle mérite une inspection ligne par ligne.

- **Salles déjà fusionnées mais géographiquement incohérentes** : `python3 scripts/run_check.py geo_incoherence_salles` (`scripts/checks/geo_incoherence_salles.sql`). Plusieurs départements distincts pour un même `salle_id` = signal fort de fusion incorrecte (attention aux salles frontalières, vérifier la distance réelle). Toujours croiser `concerts.ville`/`dept` réel, jamais la seule ressemblance textuelle du nom.

- **Comparaison systématique dept/ville pour toute salle enrichie** (complémentaire aux alertes `coherence-wikidata`, capture des cas qu'elles peuvent manquer — ex. "Espace Saint-Exupéry" trouvé par cette seule requête le 06/08/2026) : `python3 scripts/run_check.py coherence_wikidata_dept_ville` (`scripts/checks/coherence_wikidata_dept_ville.sql`). Fort taux de faux positifs sur les abréviations administratives — `dept` cohérent des deux côtés = signal fiable de faux positif, `dept` incohérent = vérifier sur Wikidata/web avant de conclure. Chaque ligne individuellement, jamais par catégorie apparente.

- **Alertes `logs_admin source='coherence-wikidata'`** : le message ne montre que `ville=X communeWikidata=Y` — ces deux valeurs peuvent être identiques tout en cachant un vrai signal (la divergence réelle est contre `concerts.ville`, jamais affichée). Toujours vérifier `concerts.ville`/`dept` réel avant de conclure à un faux positif.
  ⚠️ **Rejouer la requête complète à chaque check**, jamais sur un échantillon — un total stable ne prouve rien sur les identités individuelles : `python3 scripts/run_check.py coherence_wikidata_messages` (`scripts/checks/coherence_wikidata_messages.sql`).

  Pour chaque salle listée, croiser `concerts.ville` réel et statuer explicitement (faux positif de granularité déjà documenté, déjà ticketé, ou nouveau) — jamais laisser un message "déjà vu hier" sans re-vérification individuelle. Pour un cas classé "déjà ticketé/déjà documenté" (pas seulement les nouveaux), vérifier aussi `salle_sources` et `journalctl ... | grep 'renommage salle absorbé' <salleId>` pour ce `salle_id` — un statut "connu" peut dater d'un diagnostic partiel, la situation réelle peut avoir empiré depuis sans jamais avoir été revérifiée au-delà de `concerts.ville` seul (voir HISTORIQUE.md).
  ⚠️ L'absence d'alerte récente sur une salle n'est pas une preuve d'intégrité : `CoherenceWikidataSalleService` déclare une salle cohérente dès qu'AU MOINS UNE ville de concert réelle correspond — une salle majoritairement correcte mais partiellement contaminée peut n'alerter qu'une fois puis plus jamais. La requête géo-incohérence ci-dessus reste le seul contrôle fiable pour ce cas.
  ⚠️ Une salle dont le nom_norm est un descripteur générique national en plusieurs mots (ex. "theatre antique", "theatre municipal", "parc des expositions") est particulièrement à risque de fusion incorrecte via `chercherSalleParTokens`/`estDoublonSalle` — pas seulement via le critère Jaccard (>0.5), le critère pivot est aussi vulnérable dès que le token discriminant n'est pas dans `MOTS_GENERIQUES_SALLE` mais reste générique au niveau national. Vérifier `SELECT DISTINCT salle, ville, dept FROM concerts WHERE salle_id = '<uuid>'` pour toute salle de ce type, même sans alerte `coherence-wikidata` déclenchée.

- **Mêmes vérifications côté artistes** :
  ```sql
  SELECT id, nom, nom_norm FROM artistes WHERE nom_norm ~ '[A-ZÀ-Ý]' OR nom_norm ~ '['']';
  SELECT id, nom, nom_norm FROM artistes WHERE nom_norm NOT LIKE '% %' AND nom LIKE '% %' AND length(nom_norm) > 12;
  SELECT a.id, a.nom, count(DISTINCT c.classification) FROM artistes a JOIN concerts c ON c.artiste_id = a.id
    GROUP BY a.id, a.nom HAVING count(DISTINCT c.classification) > 1;
  SELECT artiste_id, count(*) FROM artiste_sources WHERE source='ticketmaster' GROUP BY 1 HAVING count(*) > 1;
  ```
  Le dernier cas n'est pas forcément un bug — vérifier les concerts réels associés (tournée cohérente = 2 fiches TM légitimes ; villes/dates incohérentes = vraie fusion à tort).

Toute anomalie trouvée ici suit le protocole des sections 11 à 14 : creuser la cause racine, vérification adversariale, revue de l'algorithme, réparation proposée et ticket proposé, attendre l'accord.

## 8. Mauvais rapprochement d'identité par correspondance floue

Mécanisme de correspondance par nom qui accepte un candidat sans le vérifier contre une source indépendante — distinct de la section 7 (pas une incohérence déjà en base). Balayer les trois angles à chaque check, pas seulement celui d'un ticket précédent :

- **TM artistes** (`TicketmasterSource.rechercherAttractionId`) : comparer le nom retourné par `GET /attractions/{attractionId}.json` au nom suivi (normalisé) — **par lot via le curseur de rotation de la section 9** (même mécanisme, même taille de lot ~30), jamais un balayage complet de tous les artistes suivis à chaque check (le nombre d'artistes ne cesse de croître ; l'exhaustivité de ce skill porte sur les sections/anomalies, pas sur un balayage intégral d'une population entière en une fois — cf. principe non négociable). Nécessite `TICKETMASTER_API_KEY` déchiffrée (`scripts/decrypt_config.py`, autorisation explicite à chaque fois, voir section 11) puis `python3 scripts/comparer_tm_lot.py <fichier_clé>` (lot du snapshot ; `--disjoint` pour le subagent), qui supprime le fichier de clé en fin d'exécution. Cadence 250ms/appel.
- **Eventim artistes** (`EventimSource.rechercherAttractionId`) : `journalctl -u strapontin-worker | grep 'correspondance par containment'` sur tout l'historique disponible — chaque terme signalé vérifié contre le `deezer_id`/`wikidata_id` déjà connu (API Deezer publique, sans clé).
- **Résolution canonique Deezer** (`ResolveurNomCanonique.rechercherDeezerArtiste`) : `journalctl -u strapontin-worker | grep 'Deezer résolution artiste'` sur tout l'historique, ne garder que les paires non-cosmétiques (ratio de similarité < ~0.95 après normalisation accents/casse/ponctuation) pour inspection manuelle. Croiser avec `classification` (`SELECT artiste, classification, count(*) FROM concerts WHERE artiste IN (...) GROUP BY 1,2`) — Deezer ne référence que des musiciens, un humoriste confondu avec un musicien sans rapport est un signal classique.

Point commun aux trois : aucun ne vérifie l'identité retournée contre une source indépendante déjà connue, ni ne considère qu'un type de contenu (theatre/comédie) n'a pas d'équivalent dans la source interrogée.

- **Équivalent référentiel `artistes`** (symétrique de la requête containment salles, section 5) :
  ```sql
  SELECT a1.id, a1.nom, a1.wikidata_id, a2.id, a2.nom, a2.wikidata_id
  FROM artistes a1 JOIN artistes a2 ON a1.id <> a2.id
    AND a2.nom_norm LIKE '%' || a1.nom_norm || '%' AND a1.nom_norm <> a2.nom_norm
  WHERE length(a1.nom_norm) > 3;
  ```
  Distinct du cas bénin de deux variantes orthographiques d'un même nom non suivi (`artiste_id` NULL des deux côtés, `artiste_norm` identique) — pas un bug tant qu'aucun des deux n'est suivi.

## 9. Couverture exhaustive par rotation + tirage aléatoire complémentaire

Les sections 5/7 re-exécutent des requêtes déjà connues — nécessaire mais insuffisant. Deux mécanismes complémentaires, aucun ne remplace l'autre :

1. **Rotation par curseur** (garantit 100% de couverture à terme pour un référentiel stable — salles/artistes/users/user_matches) — un tirage `ORDER BY random() LIMIT N` peut indéfiniment ne jamais retomber sur certaines lignes ; le curseur avance à chaque check et couvre systématiquement toute la table sur un nombre borné de checks. Le temps n'est pas une contrainte — préférer un lot large à un lot minimal. ⚠️ **Ne s'applique pas à `concerts`** (voir HISTORIQUE.md, 08/09/2026) : table ~12x plus grosse que salles pour un lot comparable, population non stable (insertions/purges continues) — un tour complet n'est structurellement jamais atteint (`tour_complet_le` toujours `null`), le tirage aléatoire complémentaire reste le seul filet réel sur les concerts.
2. **Tirage aléatoire complémentaire, petit lot** (contrôle statistique indépendant) — détecte un problème frais introduit ailleurs dans la table sans attendre que la rotation y arrive ; s'exécute en plus du lot de rotation, jamais à sa place.

**Outillage versionné** : `python3 scripts/run_rotation.py --all` (ou une entité, ex. `salles`) exécute le lot suivant de chaque entité (`scripts/rotation/*.sql`, requêtes figées, jamais retapées de mémoire), écrit un snapshot horodaté dans `snapshots/rotation_<entité>/` et avance le curseur de `scripts/curseurs_rotation.json` (état courant : lire ce fichier plutôt que la mémoire prose). Le script gère lui-même le rebouclage en fin de table et horodate `tour_complet_le`. `python3 scripts/verifier_rotation.py artistes|salles` vérifie le lot contre Wikidata et Deezer (libellé, `deezer_id` à moins de 2 000 fans, écart de position) ; `--disjoint` prend le lot suivant sans toucher au curseur.

**Lot de contre-vérification disjoint (règle du 22/10/2026)** : le subagent ne reprend pas le lot que l'agent principal vient de tirer. Il lit dans le snapshot de l'agent principal le dernier `id` de chaque lot, puis exécute lui-même la requête figée de `scripts/rotation/<entité>.sql` avec `__CURSEUR__` = cet `id` et `__LOT__` = même taille, par substitution manuelle (psql direct) : c'est le lot suivant, sans lancer `run_rotation.py` ni écrire `curseurs_rotation.json`. Ce lot s'ajoute à la couverture ; le curseur n'avance pas, il sera tiré normalement plus tard. Concerts : un tirage aléatoire de même taille, hors des identifiants du snapshot.

Puis, séparément, un petit tirage aléatoire complémentaire (5 par type, `ORDER BY random() LIMIT 5`, même colonnes que les requêtes de `scripts/rotation/`) — sur les mêmes tables, sans lien avec le curseur, à exécuter directement (pas de script dédié, volume trop faible pour justifier un snapshot).

Pour chaque ligne (rotation **et** tirage aléatoire, artistes/salles/concerts), vérifier **de bout en bout** contre une source indépendante (Wikidata `Special:EntityData`, API Deezer, recherche web) : le `wikidata_id`/`deezer_id` correspond-il vraiment (pas un homonyme/tribute) ? La `ville`/`dept` est-elle cohérente avec l'entité Wikidata réelle (P131, remonter la chaîne si besoin — une commune peut pointer vers un canton/EPCI) ? Pour utilisateurs/matches, vérification interne (le match correspond-il à un suivi réel). Documenter chaque lot dans `project_suivi_fiabilite_prod.md` (correct ou incident) — un lot entièrement correct est une donnée utile.

**Consacrer aussi du temps à une question exploratoire nouvelle** à chaque check (varier, ne pas se figer) :

- Distribution complète d'une colonne censée fiable (`salles.dept`, `salles.ville`) plutôt que seulement compter les NULL.
- "Quelle métrique je n'ai jamais regardée depuis le début de ce suivi ?"

Ne pas traiter cette section comme optionnelle en cas de manque de temps — c'est elle qui trouve le plus de vrais problèmes. Peu importe qu'un bug provienne d'une modification faite ailleurs : la responsabilité de vérifier la fiabilité prod reste entière.

## 10. Analyse

Toute déviation creusée jusqu'à la cause racine avant de conclure « normal » — jamais une explication d'ambiance (« ça doit être calme ») sans preuve concrète.

## 11. Vérification adversariale (subagent `verificateur-fiabilite`)

Passage indépendant (modèle du 26/08/2026). Avant de créer un ticket ou d'écrire une conclusion « bénin »/« faux positif » en mémoire, lancer le subagent `verificateur-fiabilite` (`.claude/agents/verificateur-fiabilite.md`) avec **uniquement** la fenêtre du check (borne basse = dernier check en mémoire, haute = maintenant) et la liste des sections 2 à 9 (les 12 et 13 relèvent de l'agent principal), en lui demandant « exécutée » ou « sautée (raison) » pour chacune. **Jamais** mes constats, mes réparations, une conclusion ni une directive de priorité (« concentre-toi sur X ») : c'est ce qui rend le passage indépendant (récidives du 18/08, 21/08, 24/08 et 26/08, détail dans HISTORIQUE.md). Il refait sa propre passe complète sur les données brutes ; je compare ensuite son résultat au mien et réconcilie les écarts.

- **Rotation** : il ne relance jamais `run_rotation.py` (curseur partagé). Il lit mon snapshot pour les identifiants et vérifie un **lot disjoint** (section 9).
- **Volet TM de la section 8** (règle du 22/10/2026) : fait deux fois, par moi (lot du jour) et par lui (lot disjoint). Poser en début de check une seule question à l'utilisateur (« déchiffrer la clé TM deux fois : moi et le subagent »). Si accordé, ne lui transmettre que « autorisation donnée pour un usage unique » ; il déchiffre lui-même, clé jamais affichée, fichier supprimé. Sans accord : volet TM noté non exécuté par les deux. Ne jamais écrire « sautée faute d'autorisation transmise » quand l'autorisation n'a pas été demandée.
- **Fin de mission** : ne conclure qu'après l'avoir vu terminé (`ListAgents` : `completed`, rapport final reçu). Une notification « en attente de son propre travail de fond » ou « may be interim » n'est pas une fin.
- **Ticket** seulement après son verdict, jamais en parallèle ni avant (récidive du 26/08).
- **Réconciliation avant les sections 12 à 16** : un « infirmé » annule le rejet correspondant et redevient un candidat bug ; un « non re-vérifiable » reste ouvert, jamais classé confirmé par défaut ; un écart entre son résultat et le mien (trouvé quelque chose que je n'ai pas vu, ou l'inverse) se documente dans la mémoire, jamais absorbé dans « tout confirmé ».
- **Chargement** : un subagent défini ou modifié dans `.claude/agents/` n'est chargé qu'au démarrage de Claude Code ; s'il manque ou si sa définition a changé, le signaler et proposer un redémarrage plutôt que de sauter l'étape.
- **Limites connues** (à garder en tête en réconciliant) : même modèle que moi, donc angles morts communs ; il croit parfois des agendas tiers périmés (Diderot, Cortot) : tout verdict « existe » sans site officiel ou organisateur est à refaire par moi ; il n'a pas le delta des snapshots (`run_check.py` écrit un snapshot, il ne le lance pas) ; il ne voit ni le parcours utilisateur ni l'interface ; il ne peut pas contredire ce qu'il ne voit pas (mes constats et réparations).

## 12. Revue de l'algorithme

Ajoutée le 06/10/2026 sur demande de l'utilisateur : le check ne se contente pas de constater, il cherche ce que l'algorithme aurait dû éviter ou corriger seul, et ce que nous avons manqué. Pour chaque anomalie confirmée, réparée ou signalée de ce check :

- **Mécanisme en cause** : quel code l'a produite ou aurait dû l'empêcher (lu dans le dépôt, jamais supposé ; distinguer établi et supposé).
- **Correction automatique** : pourquoi elle ne l'a pas résolue, et si elle pourrait le faire sans risque — critère mesuré sur les cas réels du check (combien de vrais cas, combien de faux positifs), jamais une intuition.
- **Parcours utilisateur** : l'utilisateur concerné est-il informé de ce qui l'affecte ? Notification qui ne part plus (appareil push révoqué, canal désactivé), concert disparu ou fusionné, salle renommée ou répartie, suivi devenu muet. Cas fondateur : appareil push révoqué automatiquement le 26/09/2026, utilisateur jamais averti, découvert seulement par un signalement mal présenté (#1310).
- **Effets de bord des outils** : une réparation ou un outil admin peut lui-même créer une anomalie au cycle suivant (06/10/2026 : répartition défaite par un lieu Ticketmaster non déplacé, #1303 ; lieux Eventim supprimés par une fusion, #1304) — revérifier au cycle suivant toute réparation de la veille.

Chaque amélioration retenue devient une proposition de ticket (section 14), au format constat / analyse / attendu. Jamais de correction de code dans le check.

## 13. Plan de réparation des données

Les anomalies de données confirmées se réparent, elles ne restent pas en attente d'un outil admin.

- **Plan** : pour chaque anomalie, l'action (supprimer, fusionner, répartir, accepter un faux positif, déclarer deux salles distinctes, rattacher une sous-salle, maintenir un concert), la preuve, et le nombre de lignes attendu.
- **Preuve en ligne** pour tout concert ou lieu douteux : d'abord le **site officiel de la salle** (agenda, page de l'événement), puis la billetterie d'origine, et seulement ensuite les agrégateurs (Songkick, Bandsintown, JDS), qui gardent des informations périmées (Milano au Bataclan, annulé selon bataclan.fr, toujours listé ailleurs le 06/10/2026). Un cas n'est déclaré indécidable qu'après avoir épuisé ces sources.
- **Durabilité** (règle du 22/10/2026) : avant de supprimer ou fusionner une fiche, vérifier si une source la republie. Tiennent : les fusions de salles (le lieu suit), la suppression d'une fiche que plus aucune source ne publie (annulée, série terminée). Ne tiennent pas : la suppression d'une fiche encore publiée (recréée à 02h00), la fusion d'une fiche dont l'heure ou le titre diffère de celui publié (#1422, #1408), et jamais de remise à zéro de `absences_consecutives` pour masquer un symptôme. Noter dans la mémoire ce qui est non durable et le recontrôler le lendemain.
- **Validation** : plan présenté dans le même tour, jamais noté « à faire plus tard » ; une écriture en prod demande l'accord explicite de l'utilisateur.
- **Exécution, par moi** : l'utilisateur attend que j'exécute la réparation, pas qu'il la refasse dans l'admin (« c'est toi qui est censé le faire », 05/10/2026). Chaque action reproduit fidèlement le service web qui ferait la même chose (`RepartitionSalleService`, `SignalementsAdminService`, `ConcertsAdminService`, fusion de `FusionSallesClePorteeService`), dans une transaction qui compte avant d'écrire, échoue sur tout écart, journalise dans `logs_admin` comme l'outil, puis est relue. Écart volontaire documenté : la déduplication des sources de la fusion de salles n'est pas reproduite tant que #1304 n'est pas corrigé (elle supprime des lieux Eventim légitimes).
- **Permission refusée** : si l'écriture est refusée, préparer le fichier SQL et donner la commande `!` à lancer, puis relire le résultat.

## 14. Tickets

**Jamais de ticket sans confirmation explicite de bug ET accord de l'utilisateur.** Présenter le constat, la preuve, la cause racine si identifiée, l'impact concret vérifié — proposer, attendre la réponse. Si un correctif est déjà en cours ailleurs, ne pas dupliquer : noter le statut, vérifier au prochain check.

**Étape bloquante avant tout brouillon : intérêt et durabilité** (règle du 05/11/2026, après l'audit des 235 tickets de ce suivi : 11 repris explicitement par un ticket ultérieur, jusqu'à 76 cités avec un motif de persistance, et 33 tickets « fusion/rapprochement de salles » corrigés cas par cas). Cette étape prime sur la règle « un constat mineur se ticket quand même » du skill `/ticket` : pour ce suivi, un constat sans intérêt ou sans correctif durable ne devient pas un ticket.

1. **Tout vérifier moi-même d'abord** : site officiel du lieu, OSM, API de la billetterie, position, base. Ne lister en « non vérifié » que ce qui exige une autorisation explicite (déchiffrement de clé) ; jamais demander la permission de vérifier.
2. **Impact chiffré** : nombre de salles, fiches, comptes, notifications touchés (requête, pas impression), et conséquence visible pour l'utilisateur. Un impact nul ou cosmétique : recommander « pas de ticket ».
3. **Durabilité du correctif** :
   - La cause racine est-elle établie, ou seul un cas est traité ? Chercher la famille (`tea issues -k` sur le mécanisme) : si 3 tickets ou plus corrigent la même classe de défaut cas par cas, le ticket demande la cause de la classe, pas un cas de plus.
   - La donnée corrigée sera-t-elle réécrite par une source ou un job (nom suivi de la billetterie, alignement, purge) ? Si oui, le dire et ne proposer un ticket que pour le mécanisme.
   - Le mécanisme touche-t-il d'autres lignes encore non traitées ? Mesurer le taux d'erreur sur les lignes déjà traitées et le projeter sur celles à venir.
4. **Présenter** : impact chiffré, durabilité, et une recommandation explicite « ticket » ou « pas de ticket » avec la raison. Un brouillon n'est rédigé que si la recommandation est « ticket ».
5. **Après création** : au check suivant, revérifier en données réelles que le correctif tient (donnée non réécrite, signalement non recréé), et le consigner dans la mémoire.

**Avant de conclure le résumé du check, relire la liste complète des constats confirmés de ce tour et vérifier explicitement que chacun a (a) une réparation proposée (section 13), (b) un ticket proposé ou créé, (c) les deux, ou (d) une raison explicite de n'avoir ni l'un ni l'autre.** Un constat simplement narré dans le résumé sans proposition de ticket est le même défaut qu'un constat marqué "en attente de décision" sans jamais poser la question — cf. HISTORIQUE.md, récidive du 13/08 (#1057 fermé mais confirmé non résolu sur le fond, narré dans le résumé mais jamais proposé comme ticket dans le même tour que les 3 autres constats du check).

**Structure du ticket : Constat / Analyse / Attendu — jamais de section "piste de correction"/implémentation proposée.** Un ticket écrit au moment de la découverte n'a pas la vue d'ensemble du jalon qui le traitera plus tard (autres tickets, refactors en cours) — imposer une implémentation biaiserait à tort la session qui l'implémentera. Exception : un ticket qui documente une correction directe de données déjà exécutée en BDD prod peut décrire l'opération SQL faite (compte-rendu d'action, pas une piste de code).

Si un ticket est créé : jalon courant (vérifier la version dans `java/pom.xml`/CLAUDE.md, jamais codé en dur), label `Bug`. `tea issue create --labels X --milestone Y` combinés échoue silencieusement — créer avec `--milestone` seul puis `tea issue edit <idx> --add-labels Bug`.

## 15. Mettre à jour la mémoire

En fin de check, éditer `project_suivi_fiabilite_prod.md` :

- Entrée en tête de « Historique des checks » (date, delta, constats détaillés — même RAS complet).
- Bug confirmé : section dédiée avec constat/preuve/cause racine/impact/statut.
- Point laissé « à confirmer au prochain check » : le résoudre explicitement (confirmé/infirmé), jamais le laisser trainer silencieusement.
- Verdicts du subagent `verificateur-fiabilite` : consigner tout "infirmé"/"non re-vérifiable", pas seulement les confirmations.

## 16. Résumé présenté à l'utilisateur

Jamais un résumé condensé type « Check terminé, RAS, mémoire à jour ». Chiffres réels par dimension même quand tout est sain.

**Format obligatoire : point par point, un item par section, à la toute fin du résumé** :

- **2.** Déploiement/santé applicative : commit/PR le cas échéant, taille DB (delta), erreurs.
- **3.** Logs système LXC/hôte : warning+ OS, tentatives SSH, espace disque, charge Proxmox.
- **4.** Cadence : jobs conformes ou non.
- **5.** Données : inserts (détaillés par cycle si bas/surprenant), `necessite_intervention_le`/`suspect_depuis`, doublons (avec évolution), `logs_admin` warn/error, utilisateurs, sauvegardes.
- **6.** Autres sources : Eventim par lieu, Deezer, push, `user_config.departements`, sessions.
- **7.** Intégrité : orphelins FK, complétude, fraîcheur, statut référentiel, caps, `app_config`, `accounts`, audit auth, référentiel mort, `wikidata_id`/`deezer_id` dupliqués, `nom_norm`, géo-incohérence, coherence-wikidata (liste complète rejouée).
- **8.** Correspondance floue : TM (si retesté), grep Eventim, grep Deezer, artiste-containment référentiel.
- **9.** Exploratoire : échantillonnage — préciser pour CHAQUE type d'entité s'il a été vérifié en profondeur (source externe) ou seulement examiné ; angle exploratoire du jour.
- **10.** Analyse : déviations creusées jusqu'à la cause racine (incluses dans les points ci-dessus, jamais « ambiance »).
- **11.** Vérification adversariale : verdicts du subagent `verificateur-fiabilite` (confirmé/infirmé/non re-vérifiable par ligne), écarts avec mon passage, et qui a fait le volet TM de la section 8 ; ou explicitement non exécuté.
- **12.** Revue de l'algorithme : mécanismes en cause, corrections automatiques possibles (mesurées), parcours utilisateur, tickets proposés.
- **13.** Réparations : proposées, validées, exécutées (lignes touchées, relecture), ou en attente avec la raison.
- **14.** Tickets : créés (numéros), ou proposés et refusés.
- **15.** Mémoire : mise à jour effectuée.

Chaque ligne dit ce qui a été fait et le résultat — si un item n'a pas été fait, l'écrire explicitement plutôt que l'omettre.

**Étape bloquante avant le rapport** (règle du 31/10/2026) : lister chaque action possible que je m'apprête à écarter ou que je n'ai pas faite dans le tour (commentaire sur un ticket ouvert, ticket mineur, réparation, vérification) et la poser en AskUserQuestion. Jamais « non demandé, donc non fait » : l'absence de demande vient de l'absence de question.

**Décisions à prendre** (règle du 17/10/2026) : ne jamais les noyer dans les conclusions. Les poser dans le tour (AskUserQuestion) et, si elles restent ouvertes, les rappeler en toute dernière section du rapport, sous un titre « Décisions en attente », une ligne par décision.

**Utilisateurs** (règle du 17/10/2026) : toujours donner l'identifiant affiché (`users.display_username`, à défaut `username`) en plus des 8 premiers caractères de l'`id`, par exemple « carlthor (2e9988d6) », pour que l'utilisateur reconnaisse les comptes. Dans `spring_session`, `principal_name` est l'identifiant affiché, pas l'`id`.
