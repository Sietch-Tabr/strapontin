---
name: Suivi Fiabilité Prod
description: Exécute un check delta complet ("360°") de la fiabilité des données de production Strapontin — déploiements, logs système LXC/hôte, cadence Quartz, intégrité BDD, correspondance floue TM/Eventim/Deezer, couverture exhaustive par rotation + tirage aléatoire complémentaire, vérification adversariale par subagent avant conclusion, revue de l'algorithme (correction automatique, parcours utilisateur) et plan de réparation des données exécuté après accord. À invoquer à chaque commande `date` de l'utilisateur dans ce projet, ou explicitement via /suivi-fiabilite.
---

Audit continu démarré le 2026-07-06 : chaque exécution compare l'état actuel au dernier point de contrôle enregistré dans `project_suivi_fiabilite_prod.md`, jamais un état des lieux isolé. Contexte, incidents passés et justification de chaque règle ci-dessous : [HISTORIQUE.md](HISTORIQUE.md).

**Prérequis locaux, non versionnés** (le skill ne fonctionne pas sans eux) :

- `.claude/scripts/acces.local.sh` : accès aux conteneurs prod/dev/CI et à leur hôte, à créer depuis `.claude/scripts/acces.exemple.sh`.
- `~/.claude/projects/<projet>/memory/project_suivi_fiabilite_prod.md` : état du suivi (historique des checks, bugs en cours), dans la mémoire personnelle Claude Code du projet (`<projet>` = chemin absolu du dépôt, `/` remplacés par `-`). Créé au premier check s'il n'existe pas.
- `scripts/decrypt_config.py` : déchiffrement ponctuel d'une clé `app_config`, fichier local.
- `snapshots/` et `scripts/curseurs_rotation.json` : résultats et état d'exécution des checks, propres à chaque poste (données de prod, jamais versionnées).

**Contrôles nocturnes** : depuis 3.0.2, les invariants de données (orphelins de clés étrangères, sessions sans appareil, notifications non envoyées, salles multi-départements, salle suivie contaminée, migration Flyway en échec, comptes incohérents, identifiant externe dupliqué, dispersion géographique) sont vérifiés chaque nuit par le worker et publiés dans `signalements_matching` (onglet admin « À traiter »). Le check `signalements_matching_actifs` les remonte : les checks SQL du même nom ci-dessous servent à recouper et à quantifier, pas à les redécouvrir.

**Équilibre** : ce suivi porte sur la fiabilité des **données**, pas la traçabilité dev — la section 2 sert à comprendre le contexte, pas à y passer la majorité du temps. Réserver un temps réel à l'investigation exploratoire (section 9) à chaque check.

**Principes non négociables** (voir HISTORIQUE.md pour le contexte de chacun) :

- **Priorité n°1 de ce skill : tout WARN/ERROR/anomalie détecté — même documenté comme "connu"/"isolé"/"sans conséquence" — se vérifie occurrence par occurrence, jamais par ratio ni ne se ferme sur un "pattern déjà vu".** Avant de qualifier un pattern de bénin : lire le code du chemin concerné, identifier individuellement chaque occurrence (chaque `venueId`/artiste/salle/message en cause) et statuer sur son sort, quantifier le volume réel affecté sur au moins un cycle concret, vérifier si l'auto-guérison au cycle suivant est réelle ou supposée.
- Un total agrégé stable ne prouve jamais que les éléments qui le composent sont inchangés — revérifier les identités individuelles, pas seulement le chiffre.
- Une conclusion "pas de corruption confirmée" basée uniquement sur la BDD (absence de log, `artiste_id`/`salle_id` NULL) ne suffit pas à écarter un doute — corroborer sur internet dès qu'un nom d'artiste/salle/événement semble suspect, pas seulement pour l'échantillonnage aléatoire de la section 9.
- Toute question nécessitant un arbitrage utilisateur doit être posée dans le tour où le constat est fait, jamais notée "en attente" sans être posée.
- Recherche web autorisée en standing pour ce skill, à tout moment du check, pour corroborer un constat de données (pas limitée à la section 9). Le déchiffrement `app_config` (`scripts/decrypt_config.py`) reste soumis à une demande explicite à chaque usage.
- Ticket créé seulement après confirmation du bug, verdict du subagent (section 11) ET accord explicite de l'utilisateur (section 14).
- Le check ne s'arrête pas au constat : chaque anomalie confirmée reçoit une réparation proposée puis exécutée par moi après accord (section 13), et la revue de l'algorithme cherche ce qui aurait dû l'éviter ou la corriger seul, parcours utilisateur compris (section 12).
- Un pattern déjà documenté dans la mémoire comme "connu"/"sans conséquence"/"déjà corrigé" n'est pas une preuve qu'il l'est réellement — la documentation passée peut elle-même être une classification erronée jamais revérifiée au fond, ou un statut "corrigé" jamais réellement appliqué en prod. Ne jamais citer "déjà documenté" comme raison suffisante de clore une anomalie — revérifier l'état réel (base, logs, code) à chaque occurrence tant qu'aucun correctif de code n'est déployé et vérifié.
- **Faire correspondre un WARN à un "type de message déjà connu" n'est jamais une vérification suffisante.** Quantifier l'impact réel en aval (ratio `candidats`/`enrichis` ou équivalent d'un service, pas seulement le nombre d'occurrences du message), et regarder la tendance sur plusieurs jours quand un compteur agrégé existe (`journalctl --since <N jours> | grep '<service> — <job> candidats='`). Un motif stable/répété sur plusieurs jours n'est jamais automatiquement bénin par sa seule ancienneté — il peut au contraire signaler qu'un garde-fou de détection existant est structurellement inadapté (seuil trop strict, ex. "0 succès" au lieu de "taux de succès faible") ; cf. HISTORIQUE.md, #1057.
- **Complémentaire à la priorité n°1, sur la population générale (pas seulement les anomalies déjà signalées)** : couverture rotative par curseur persisté (section 9) sur les tables entières (salles, artistes, concerts), garantissant 100% de couverture sur un nombre borné de checks, en plus d'un tirage aléatoire indépendant à chaque check — le temps n'est pas une contrainte.
- **Un résumé `WebFetch` d'une entité Wikidata peut halluciner l'identité d'un Q-ID cité en passant** (ex. confondre le Q-ID d'un P131 avec l'entité qu'il désigne réellement) — récidive constatée deux fois (Arkéa Arena le 07/08, Summum le 14/08). Toute affirmation du type "P131 = X (QNNN)" doit être recroisée en interrogeant QNNN lui-même (`Special:EntityData/QNNN.json`) avant d'être citée comme preuve, jamais acceptée telle quelle depuis le résumé d'une requête sur une autre entité.

## 1. Lire l'état avant tout

Lire `~/.claude/projects/<projet>/memory/project_suivi_fiabilite_prod.md` en entier :

- Dernière entrée de « Historique des checks » → borne basse de ce check.
- Sections « Bug(s) confirmé(s) » en cours → ne pas re-signaler avec un nouveau ticket ce qui est déjà pris en charge, mais **vérifier l'état réel en base/logs à chaque check** (pas seulement lire le statut noté) tant qu'aucun correctif de code n'est déployé et confirmé fonctionnel — un statut "en attente de correctif"/"connu" peut être resté figé sur une observation ancienne pendant que le problème continuait d'évoluer (cf. principe non négociable ci-dessus).

## 2. Santé système et déploiements

```
.claude/scripts/acces.local.sh prod exec "journalctl -u strapontin-web -u strapontin-worker --since '<dernier check>' --no-pager | grep -iE 'Stopping strapontin|Started strapontin|Starting strapontin'"
```

- Tout restart : vérifier si c'est un vrai déploiement (`.claude/scripts/acces.local.sh prod exec "ls -la /opt/strapontin/"` → tailles `web.jar`/`worker.jar` vs `.jar.prev`) et identifier le commit (`git log --oneline -5 main` ; sinon `git fetch origin main --quiet` puis `git log --oneline -5 origin/main`).
- **Taille de la base** :
  ```sql
  SELECT pg_size_pretty(pg_database_size('strapontin'));
  SELECT relname, pg_size_pretty(pg_total_relation_size(relid)) FROM pg_catalog.pg_statio_user_tables ORDER BY pg_total_relation_size(relid) DESC LIMIT 10;
  ```
  Comparer au dernier check — croissance brutale ou table qui grossit sans rapport avec son usage = signal d'alerte (à l'inverse, une purge trop agressive est aussi un signal).
- Recherche par motif texte sur salle/artiste : toujours envelopper dans `unaccent()` (`unaccent(nom) ILIKE '%motif%'`), jamais un motif brut — `ILIKE` n'est pas insensible aux accents en PostgreSQL.
- Échecs de job : `grep -iE 'JobExecutionException|job.*échoué'`.
- Erreurs génériques : `grep -iE 'ERROR'`, en excluant uniquement les patterns déjà confirmés connus dans la mémoire (jamais un pattern générique type "toute erreur Eventim" sans revérifier que le problème est toujours actif). Ne jamais filtrer `at fr.strapontin` d'une stack trace — ça masque l'appelant réel.
- **Tout WARN/ERROR trouvé — même documenté comme "connu"/"pattern déjà vu" — se vérifie occurrence par occurrence, pas par ratio ni par échantillon.** C'est là, plus que sur la population générale des tables, que porte l'exigence d'exhaustivité de ce skill : un WARN "1/19" ou "429 isolé" n'est jamais clos sans que chaque occurrence (chaque `venueId`/artiste/salle en cause) ait été identifiée individuellement et son sort statué (transitoire confirmé, doublon réel, bug de log...). La couverture par rotation de la section 9 est un contrôle complémentaire sur la population saine, pas le mécanisme principal d'exhaustivité.

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
- **Espace disque LXC 113** : `.claude/scripts/acces.local.sh prod exec "df -h /"` — comparer au dernier check, croissance anormale = signal (backups mal purgés, logs qui s'accumulent hors `logs_admin`).
- **Hôte Proxmox** (charge, mémoire, reboot) :
  ```
  .claude/scripts/acces.local.sh hote exec "uptime; free -h"
  ```
  Comparer la charge à la fourchette habituelle (~0.2-0.5 documentée dans l'historique). Un écart identifié se creuse jusqu'au process en cause (`ps aux --sort=-%cpu | head`) avant de conclure quoi que ce soit — jamais deviner une cause par corrélation (cf. précédent du 14/07 : pic de charge causé par des process `ollama`, découvert par inspection directe, pas par supposition). Un reboot de l'hôte se corrèle avec les logs des conteneurs (patch noyau de maintenance = bénin, cf. 19/07 ; coupure imprévue = à investiguer).

## 4. Cadence des jobs Quartz

Vérifier sur la fenêtre : `fetch:region` (1×/jour, 02h00 — `Planificateur.java`), `fetch:artistes:batch`/`fetch:salles:all` (H:00-ish), `recalculer:matches`, `revoquer:admins` (horaire), purges quotidiennes (00:00/00:05/00:06/00:15), `reconciliation:nocturne` (3h, chercher `démarré`/`terminé sallesVerifiees=…redecouvertes=…ambigues=…enrichissementsSalle=…enrichissementsArtiste=…`).

Raccourci : `SELECT * FROM fetch_jobs;` (table live, pas `fetch_jobs_historique`) donne en une requête le dernier statut de tous les jobs — en complément du detail journalctl, pas en remplacement.

- **Dérive de volume et retry ciblé** (jalon 2.9.0) : `journalctl -u strapontin-worker | grep -iE 'derive|retry:lots'` — `DeriveVolumeFetchService` (appelé à chaque cycle des jobs multi-lots) loggue en WARN une chute anormale du volume inséré/mis à jour vs l'historique récent ; `RetryLotsService` loggue en INFO un retry planifié et en WARN un échec de planification de retry. Silencieux par construction quand tout va bien (pas de ligne = sain), donc à chercher explicitement plutôt qu'à déduire de leur absence dans les grep habituels de la section 5.

## 5. Données (jamais sauter cette section)

Utiliser un fichier SQL temporaire (`.claude/scripts/acces.local.sh prod psql /tmp/check.sql`) plutôt que d'empiler les guillemets imbriqués dans une commande unique.

- **Concerts insérés** par source depuis le dernier check : `SELECT source, count(*) FROM concerts WHERE first_seen > '<dernier check>' GROUP BY 1`. Si 0 sur plusieurs cycles, vérifier `SELECT max(first_seen) FROM concerts` (gel plus long que la fenêtre) et le contexte saisonnier (juillet-août plus calme, normal).
- **`artiste_sources`/`salle_sources`** : `necessite_intervention_le` (proche de 0 sauf incident réel), `suspect_depuis` (oscillation quotidienne normale).
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
- **`logs_admin`** warn/error depuis le dernier check (`ts` est `timestamp with time zone` depuis le jalon 3.0.0/#1238, plus un bigint epoch millis — `WHERE ts > '<dernier check>'::timestamptz`, affichage direct sans `to_timestamp()`) — toujours lister le détail, jamais un count seul. ⚠️ Piège historique désormais caduc (gardé pour mémoire) : avant #1238, `ts` était en bigint epoch millis et nécessitait `(extract(epoch from '<dernier check>'::timestamptz) * 1000)::bigint` avec cast explicite en `::timestamptz` avant `extract(epoch from ...)` — un `extract(epoch from timestamp '<literal>')` sans cast traitait le literal comme UTC au lieu du fuseau de session, décalant la borne de +2h (CEST)/+1h (CET) (trouvé le 24/08/2026 par le subagent adversarial, voir HISTORIQUE.md). Si une requête échoue avec « l'opérateur n'existe pas : timestamp with time zone / integer », c'est cet ancien pattern encore utilisé par erreur — repasser en comparaison directe.
- **Utilisateurs** : nb comptes, caps (500 artistes/200 salles/10 push), révocations admin en attente, TOTP suspects.
- **Sauvegardes** : `.claude/scripts/acces.local.sh prod exec "systemctl list-timers 'strapontin-backup*' --no-pager"` — LAST/NEXT cohérents (00:00 NAS, 01:00 local).

## 6. Toutes les sources, pas seulement TM/Eventim

- **Eventim par lieu** (depuis v3.0.1, Digitick retirée) : `SELECT count(*) FROM signalements_matching WHERE type='chute_resultats_salle' AND resolu_le IS NULL` + salles suivies avec `salle_sources.libelle_source` renseigné et `dernier_nb_resultats`. Une chute brutale signalée = billetterie du lieu probablement déplacée (cas L'Empreinte, invisible 3 mois avant v3.0.1).
- **Deezer** : `journalctl -u strapontin-worker | grep -i 'Deezer'` — chercher erreurs/warn spécifiquement (`grep -iE 'erreur|error|warn'`), pas juste compter.
- **Wikidata Search (REST) / Nominatim** (module web, ajout interactif) : `journalctl -u strapontin-web | grep -i 'Nominatim\|rechercherWikidata\|wbsearchentities'` — noter l'absence d'activité plutôt que l'ignorer (un chemin jamais exercé peut cacher une régression).
- **Notifications push** : `SELECT count(*), count(DISTINCT user_id) FROM push_abonnements` + cap 10/user + `grep 'Push digest envoyé\|Push: échec\|Push: statut inattendu'` dans les logs (pas seulement les échecs email). Abonnements jamais nettoyés : `SELECT count(*) FROM push_abonnements WHERE cree_le < now() - interval '90 days'`.
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

- **Signalements de matching actifs** (jalon 2.9.0) : `python3 scripts/run_check.py signalements_matching_actifs` (`scripts/checks/signalements_matching_actifs.sql`) — agrège les **4 sources** de l'onglet admin « À traiter » (`signalements_matching`, `necessite_intervention_le`, `contamination_signalee`, `doublon_signale`), pas seulement `signalements_matching` (élargi le 26/09/2026 — l'ancienne version ne couvrait qu'une seule des 4 sources, laissant invisibles 29 signalements salle actifs, dont certains depuis plusieurs semaines, voir HISTORIQUE.md). Complète les checks ad hoc de ce skill sans les remplacer — un signalement non résolu (`resolu_le IS NULL` ou colonne encore renseignée) est un candidat direct à investiguer, jamais classé en bloc par type. Chaque élément des 4 sources, et chaque concert à confirmer ci-dessous, aboutit dans le même check : vraie anomalie (réparation, section 13, et/ou ticket, section 14), faux positif (acceptation, paire distincte, maintien, section 13) ou déjà résolu. Ne jamais traiter une seule des 4 sources (récidive du 06/10/2026 : seule `signalements_matching` traitée, l'utilisateur a dû signaler les contaminations, doublons et concerts à confirmer restants).

- **File « concerts à confirmer »** (`suppression_suggeree_le`, `ConcertsAdminService`, ajouté au skill le 26/09/2026 — jusqu'ici jamais couvert par aucune section, découvert par une revue manuelle hors check) : `python3 scripts/run_check.py concerts_a_confirmer` (`scripts/checks/concerts_a_confirmer.sql` — requête figée, jamais retapée de mémoire, cf. HISTORIQUE.md). Chaque ligne vérifiée individuellement (corroboration web : le concert existe-t-il encore réellement à cette date/salle ?) avant de conclure — un concert réel peut être flagué à tort (doublon de salle jamais fusionné, source cassée pour cette salle/cet artiste, `artiste_id` jamais résolu). ⚠️ Plusieurs lignes partageant le même `absences_consecutives` et la même date de flag signent une cause commune (même salle/source), pas des cas indépendants — creuser la cause partagée avant de traiter ligne par ligne.

- **Alertes `logs_admin source='coherence-salle'`** (jalon 2.8.8, `CoherenceMultiSalleService`, journalisation seule) : `SELECT ts, message FROM logs_admin WHERE source='coherence-salle' AND ts > ...` — deux familles de message, "salle potentiellement multi-lieux — mesententes=…" (nom divergent entre sources internes pour un même `salle_id`, ex. Eventim `internal:` vs nom canonique) et "doublon de salle potentiel sans wikidata_id ni concert partagé" (généralise `doublons_salle_suffixe.sql` aux paires sans `wikidata_id` d'aucun côté). Fort taux de faux positifs sur simple variance de nommage (à vérifier via `salle_sources`/`concerts.ville`/`concerts.dept`, jamais le texte seul) — chaque ligne individuellement comme pour `coherence-wikidata`, jamais par catégorie apparente.

- **Complétude concerts futurs** : `salle_id IS NULL`, `ville IS NULL/''`, `url IS NULL/''`, `date` incohérente. `salle_id IS NULL` ne devrait normalement jamais arriver (upsert auto) — creuser si non nul, surtout si de nouvelles instances apparaissent.

- **Fraîcheur des données déjà en base** : `python3 scripts/run_check.py fraicheur_concerts` (`scripts/checks/fraicheur_concerts.sql` — regroupe déjà par `artiste_norm` et calcule `jamais_retouche`).

  ⚠️ `PersisteurConcertsDb` pose `updated_at = now()` **sans condition** à chaque upsert réussi — non rafraîchi depuis 14j signifie littéralement absent de **toute** réponse TM/Eventim depuis 14j, jamais "rien n'a changé" sans preuve.

  Pour chaque artiste/salle en tête : (1) comparer `artiste_sources.identifiant_externe` (DB) contre l'attractionId réellement envoyé dans les logs `fetch:artistes:batch` (`grep -o 'attractionId=[^&]*'`) — un mismatch, même d'un caractère de casse, peut être l'ID valide d'un autre artiste réel ; (2) si `salle_id IS NULL`, c'est un résidu structurel distinct (aucune salle candidate) ; (3) si l'ID envoyé est correct et le cycle réussit sans erreur, vérifier en direct sur l'API source (`countryCode=FR`) si l'artiste a bien 0 événement — corroborer par recherche web (tribute band ? tournée annulée ?) avant de conclure.

- **Statut référentiel admin réel** : reproduire la logique `ReferentielSallesService`/`ReferentielArtistesService` (`a_jour`/`a_verifier`/`invalide`) en SQL pour voir la distribution telle qu'un admin la verrait, pas seulement le compteur brut.

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

- **TM artistes** (`TicketmasterSource.rechercherAttractionId`) : comparer le nom retourné par `GET /attractions/{attractionId}.json` au nom suivi (normalisé) — **par lot via le curseur de rotation de la section 9** (même mécanisme, même taille de lot ~30), jamais un balayage complet de tous les artistes suivis à chaque check (le nombre d'artistes ne cesse de croître ; l'exhaustivité de ce skill porte sur les sections/anomalies, pas sur un balayage intégral d'une population entière en une fois — cf. principe non négociable). Nécessite `TICKETMASTER_API_KEY` déchiffrée (`scripts/decrypt_config.py` — déchiffrement ponctuel, jamais affiché en clair, fichiers supprimés après usage, autorisation explicite à chaque fois). Cadence 250ms/appel.
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

**Outillage versionné (ajouté le 24/08/2026, même pattern que `run_check.py`/`scripts/checks/` des sections 5/7)** : `python3 scripts/run_rotation.py --all` (ou un nom seul, ex. `salles`) exécute le lot suivant de chaque entité (`scripts/rotation/*.sql`, requêtes figées — jamais les retaper de mémoire), sauvegarde un snapshot horodaté dans `snapshots/rotation_<entité>/` (audit trail des lignes réellement tirées, absent avant cette date — voir HISTORIQUE.md) et avance automatiquement le curseur persisté dans `scripts/curseurs_rotation.json`. Le script gère lui-même le rebouclage en fin de table (relance depuis `''` dans la même exécution pour compléter le lot, remet le curseur à `''` et horodate `tour_complet_le` — comportement prescrit par cette section, plus besoin de le faire à la main). Lire `curseurs_rotation.json` pour l'état courant plutôt que la mémoire prose (`project_suivi_fiabilite_prod.md` reste le résumé humain de ce qui a été vérifié sur chaque lot, mais plus la source de vérité du curseur lui-même).

Puis, séparément, un petit tirage aléatoire complémentaire (5 par type, `ORDER BY random() LIMIT 5`, même colonnes que les requêtes de `scripts/rotation/`) — sur les mêmes tables, sans lien avec le curseur, à exécuter directement (pas de script dédié, volume trop faible pour justifier un snapshot).

Pour chaque ligne (rotation **et** tirage aléatoire, artistes/salles/concerts), vérifier **de bout en bout** contre une source indépendante (Wikidata `Special:EntityData`, API Deezer, recherche web) : le `wikidata_id`/`deezer_id` correspond-il vraiment (pas un homonyme/tribute) ? La `ville`/`dept` est-elle cohérente avec l'entité Wikidata réelle (P131, remonter la chaîne si besoin — une commune peut pointer vers un canton/EPCI) ? Pour utilisateurs/matches, vérification interne (le match correspond-il à un suivi réel). Documenter chaque lot dans `project_suivi_fiabilite_prod.md` (correct ou incident) — un lot entièrement correct est une donnée utile.

**Consacrer aussi du temps à une question exploratoire nouvelle** à chaque check (varier, ne pas se figer) :

- Distribution complète d'une colonne censée fiable (`salles.dept`, `salles.ville`) plutôt que seulement compter les NULL.
- "Quelle métrique je n'ai jamais regardée depuis le début de ce suivi ?"

Ne pas traiter cette section comme optionnelle en cas de manque de temps — c'est elle qui trouve le plus de vrais problèmes. Peu importe qu'un bug provienne d'une modification faite ailleurs : la responsabilité de vérifier la fiabilité prod reste entière.

## 10. Analyse

Toute déviation creusée jusqu'à la cause racine avant de conclure « normal » — jamais une explication d'ambiance (« ça doit être calme ») sans preuve concrète.

## 11. Vérification adversariale (subagent `verificateur-fiabilite`)

**Modèle depuis le 26/08/2026 : passage indépendant, pas confirmation d'un résumé.** Avant de créer un ticket ou d'écrire une conclusion "bénin"/"faux positif" en mémoire : donner au subagent `verificateur-fiabilite` (`.claude/agents/verificateur-fiabilite.md`) uniquement la fenêtre du check (borne basse = dernier check en mémoire, borne haute = maintenant) et la liste des sections à couvrir — **jamais mon propre résultat, mes constats déjà rédigés, ni une conclusion quelconque**. Il exécute sa propre passe complète et indépendante sur les données brutes (mêmes outils : SSH prod, SQL direct, WebFetch/WebSearch, lecture du dépôt, lecture de `project_suivi_fiabilite_prod.md` pour le contexte historique) — pas seulement une re-vérification ligne par ligne de ce que je lui présente. Je compare ensuite son résultat au mien et réconcilie les écarts, plutôt que de lui demander de valider une liste que j'ai déjà compilée. Exception technique : ne jamais lui faire relancer `scripts/run_rotation.py` (mutation d'un curseur partagé, une seconde exécution consommerait un lot supplémentaire à ma place) — il lit le snapshot déjà écrit par mon exécution (`snapshots/rotation_<entité>/`, donnée brute non retouchée) plutôt que de regénérer le lot.

Contexte de ce changement : jusqu'au 25/08/2026, le mécanisme consistait à compiler la liste des constats du check et à la soumettre pour confirmation — ce qui a permis, à plusieurs reprises (18/08, 21/08, 24/08, 26/08, voir HISTORIQUE.md), à l'agent principal d'orienter consciemment ou non ce que le subagent voyait (périmètre restreint, directive de priorité, conclusion déjà résolue transmise pour "confirmation"). Un passage réellement indépendant, parti de la fenêtre brute plutôt que de mon résumé, ne peut pas hériter de ces biais par construction.

⚠️ **Jamais de directive de priorité dans le prompt du subagent** ("concentre l'effort sur X", "focus sur le point Y") — même en donnant par ailleurs la liste des sections à couvrir dans le cadre du passage indépendant ci-dessus. La valeur du subagent tient précisément à l'absence des a priori de l'agent principal sur où chercher ; lui indiquer où concentrer son effort réintroduit un biais, sous une forme plus ou moins discrète selon le cas — récidives du 21/08 (périmètre restreint), 24/08 (langage de priorisation malgré la liste complète des sections), 26/08 (conclusion déjà résolue transmise pour "confirmation" plutôt que des données brutes, voir HISTORIQUE.md pour le détail des trois). Le passage indépendant (modèle du 26/08 ci-dessus) élimine structurellement la plupart de ces formes, mais la vigilance reste nécessaire sur la formulation de la fenêtre/sections transmises.

⚠️ **Créer un ticket seulement après le verdict du subagent, jamais en parallèle ni avant.** Même sur un constat qui semble évident, ne jamais proposer/faire approuver un ticket à l'utilisateur avant d'avoir lancé le passage indépendant du subagent et reçu son verdict — récidive du 26/08 où un ticket a été proposé à l'utilisateur (`AskUserQuestion`) avant même le lancement de la vérification adversariale, alors que la section 11 du skill précède explicitement les sections 12-16.

Réconcilier son verdict avant de passer aux sections 12-16 : un "infirmé" annule le rejet correspondant et redevient un candidat bug à traiter comme n'importe quel autre ; un "non re-vérifiable" reste explicitement ouvert, jamais classé confirmé par défaut ; un écart entre son résultat et le mien (trouvé quelque chose que je n'ai pas vu, ou l'inverse) se documente explicitement dans la mémoire, jamais silencieusement absorbé dans "tout confirmé".

⚠️ Un subagent défini dans un nouveau dossier `.claude/agents/` n'est chargé qu'au démarrage de Claude Code — si le subagent n'apparaît pas disponible dans la session courante, le signaler et proposer un redémarrage plutôt que de sauter cette étape silencieusement.

⚠️ **Le mandat du subagent porte par défaut sur le contenu des constats soumis, jamais sur la complétude du périmètre du check lui-même — l'un ne garantit pas l'autre.** Si l'agent principal restreint seul son propre périmètre (ex. "focus sur les correctifs du jalon", sautant les sections 6-9), un prompt du subagent qui ne liste que les constats de ce périmètre restreint hérite du même angle mort : le subagent vérifiera fidèlement ce qu'on lui donne sans jamais remarquer ce qui n'y figure pas. Toujours joindre au prompt du subagent la liste des sections 2 à 9 du skill (les sections 12 et 13 relèvent de l'agent principal) avec, pour chacune, "exécutée" ou "sautée (raison)" — lui demander explicitement de signaler toute section sautée sans justification explicite de l'utilisateur, en plus de vérifier les constats eux-mêmes (récidive du 21/08 : check restreint aux 4 correctifs #1070-1073 sans validation utilisateur, subagent sollicité avec le même périmètre restreint, alerte Overpass — niveau error, email réellement envoyé — jamais remontée par l'un ni par l'autre ; détecté seulement par une question directe de l'utilisateur, "j'ai eu un mail d'erreur Overpass, pourquoi tu ne l'as pas vu ?").

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
- **Validation** : plan présenté dans le même tour, jamais noté « à faire plus tard » ; une écriture en prod demande l'accord explicite de l'utilisateur.
- **Exécution, par moi** : l'utilisateur attend que j'exécute la réparation, pas qu'il la refasse dans l'admin (« c'est toi qui est censé le faire », 05/10/2026). Chaque action reproduit fidèlement le service web qui ferait la même chose (`RepartitionSalleService`, `SignalementsAdminService`, `ConcertsAdminService`, fusion de `FusionSallesClePorteeService`), dans une transaction qui compte avant d'écrire, échoue sur tout écart, journalise dans `logs_admin` comme l'outil, puis est relue. Écart volontaire documenté : la déduplication des sources de la fusion de salles n'est pas reproduite tant que #1304 n'est pas corrigé (elle supprime des lieux Eventim légitimes).
- **Permission refusée** : si l'écriture est refusée, préparer le fichier SQL et donner la commande `!` à lancer, puis relire le résultat.

## 14. Tickets

**Jamais de ticket sans confirmation explicite de bug ET accord de l'utilisateur.** Présenter le constat, la preuve, la cause racine si identifiée, l'impact concret vérifié — proposer, attendre la réponse. Si un correctif est déjà en cours ailleurs, ne pas dupliquer : noter le statut, vérifier au prochain check.

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
- **11.** Vérification adversariale : verdicts du subagent `verificateur-fiabilite` (confirmé/infirmé/non re-vérifiable par ligne), ou explicitement noté non exécuté si le subagent n'était pas encore chargé dans la session.
- **12.** Revue de l'algorithme : mécanismes en cause, corrections automatiques possibles (mesurées), parcours utilisateur, tickets proposés.
- **13.** Réparations : proposées, validées, exécutées (lignes touchées, relecture), ou en attente avec la raison.

Chaque ligne dit ce qui a été fait et le résultat — si un item n'a pas été fait, l'écrire explicitement plutôt que l'omettre.
