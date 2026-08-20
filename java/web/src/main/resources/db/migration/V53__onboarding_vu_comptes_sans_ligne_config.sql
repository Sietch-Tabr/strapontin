-- V53__onboarding_vu_comptes_sans_ligne_config.sql
--
-- V52 n'a backfillé onboarding_vu=true que pour les lignes user_config déjà existantes — un
-- compte existant n'ayant jamais touché ses préférences n'a aucune ligne du tout (pattern déjà
-- présent ailleurs dans ce schéma, cf. UserConfigService.lireConfig qui gère l'absence de ligne
-- comme valeurs par défaut). onboardingNonVu() traite alors cette absence comme "jamais vu",
-- affichant à tort la modale d'onboarding à un compte déjà ancien — constaté en E2E (dialog
-- natif showModal() rend le reste de la page inerte, bloquant toute interaction). Crée
-- explicitement la ligne (valeurs par défaut des autres colonnes) pour tout utilisateur qui n'en
-- a encore aucune.
INSERT INTO user_config (user_id, onboarding_vu)
SELECT id, true FROM users u WHERE NOT EXISTS (SELECT 1 FROM user_config uc WHERE uc.user_id = u.id);
