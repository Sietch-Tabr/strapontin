import { appelApi, lireJson } from "../utils/api.js";
import { ref, shallowRef, reactive, onMounted, onUnmounted } from "vue";
import { useToasts } from "./useToasts.js";
import { useDialog } from "./useDialog.js";

/// Logique partagée des onglets admin Référentiel artistes/salles : pagination,
/// sélection pour fusion, édition du nom affiché, réinitialisation d'enrichissement.
///
/// @param endpoint       segment d'URL REST (`artistes` ou `salles`)
/// @param cleListe       clé JSON de la liste paginée dans la réponse (`artistes` ou `salles`)
/// @param cleTotal       clé JSON du total dans la réponse (`totalArtistes` ou `totalSalles`)
/// @param messageFusionne message de succès de fusion (accord grammatical propre à l'entité)
/// @param libellePluriel  libellé pluriel pour les messages génériques (`artistes`/`salles`)
/// @param filtresExtra    callback retournant les filtres additionnels propres au composant
///                        appelant (ex. ville/dept pour les salles)
export function useReferentielAdmin({
  endpoint,
  cleListe,
  cleTotal,
  messageFusionne,
  libellePluriel,
  filtresExtra = () => ({}),
}) {
  const { afficherToast } = useToasts();

  const chargement = ref(true);
  const items = shallowRef([]);
  const page = ref(0);
  const totalPages = ref(1);
  const total = ref(0);
  const filtreNom = ref("");
  const filtreStatut = ref("");
  const filtreWikidataManquant = ref(false);
  const selection = reactive([]);

  const {
    dialogRef: dialogEditionRef,
    ouvrir: ouvrirDialogEdition,
    fermer: fermerEdition,
  } = useDialog();
  const edition = reactive({ id: null, nomAffiche: "", nomOriginal: "" });
  const enCoursEdition = ref(false);

  const {
    dialogRef: dialogFusionRef,
    ouvrir: ouvrirDialogFusion,
    fermer: fermerFusion,
  } = useDialog();
  const itemA = ref(null);
  const itemB = ref(null);
  const cibleChoisie = ref(null);
  const enCoursFusion = ref(false);

  // controleur : durée de vie du composant, pour les mutations ponctuelles ci-dessous
  // (édition/réinitialisation/fusion), jamais rappelées en rafale contrairement à charger().
  const controleur = new AbortController();
  // controleurActuel : recréé à chaque appel de charger() — un changement rapide de
  // page/filtre (allerPage/rechargerPage0) annule sinon seulement la requête précédente au
  // démontage, pas à la requête suivante, laissant deux réponses s'appliquer dans le désordre.
  let controleurActuel = null;

  async function charger() {
    controleurActuel?.abort();
    const controleurCourant = new AbortController();
    controleurActuel = controleurCourant;

    chargement.value = true;
    try {
      const params = new URLSearchParams();
      params.set("page", page.value);
      if (filtreNom.value) params.set("nom", filtreNom.value);
      if (filtreStatut.value) params.set("statut", filtreStatut.value);
      if (filtreWikidataManquant.value) params.set("wikidataManquant", "true");
      for (const [cle, valeur] of Object.entries(filtresExtra())) {
        if (valeur) params.set(cle, valeur);
      }

      const rep = await appelApi(`/api/admin/${endpoint}?${params}`, {
        signal: controleurCourant.signal,
      });
      if (!rep.ok) return;
      const data = await lireJson(rep);
      items.value = data[cleListe] ?? [];
      page.value = data.page ?? 0;
      totalPages.value = data.totalPages ?? 1;
      total.value = data[cleTotal] ?? 0;
    } catch (e) {
      if (e.name === "AbortError") return;
      afficherToast(
        `Erreur lors du chargement du référentiel ${libellePluriel}.`,
        "erreur",
      );
    } finally {
      if (controleurActuel === controleurCourant) chargement.value = false;
    }
  }

  // la sélection (UUID) ne référence que des éléments de la page courante — un changement
  // de page/filtre sans réinitialisation laissait ouvrirFusion() chercher un UUID absent de la
  // page rechargée, ouvrant un dialog de fusion vide sans aucun message explicite.
  function reinitialiserSelectionSiNonVide() {
    if (selection.length > 0) {
      selection.splice(0, selection.length);
      afficherToast(
        "Sélection réinitialisée (changement de page ou de filtre).",
        "avertissement",
      );
    }
  }

  function rechargerPage0() {
    page.value = 0;
    reinitialiserSelectionSiNonVide();
    charger();
  }

  function allerPage(n) {
    page.value = Math.max(0, Math.min(n, totalPages.value - 1));
    reinitialiserSelectionSiNonVide();
    charger();
  }

  function basculerSelection(id) {
    const index = selection.indexOf(id);
    if (index !== -1) {
      selection.splice(index, 1);
    } else if (selection.length < 2) {
      selection.push(id);
    } else {
      afficherToast(
        `Sélectionnez au maximum 2 ${libellePluriel} pour une fusion.`,
        "avertissement",
      );
    }
  }

  function ouvrirEdition(item) {
    edition.id = item.id;
    edition.nomAffiche = item.nomAffiche ?? "";
    edition.nomOriginal = item.nom;
    ouvrirDialogEdition();
  }

  async function sauvegarderEdition() {
    if (enCoursEdition.value) return;
    enCoursEdition.value = true;
    try {
      const rep = await appelApi(`/api/admin/${endpoint}/${edition.id}`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ nomAffiche: edition.nomAffiche || null }),
        signal: controleur.signal,
      });
      if (rep.ok) {
        fermerEdition();
        afficherToast("Nom affiché mis à jour.", "succes");
        await charger();
      } else {
        afficherToast("Erreur lors de la mise à jour.", "erreur");
      }
    } catch (e) {
      if (e.name !== "AbortError") afficherToast("Erreur réseau.", "erreur");
    } finally {
      enCoursEdition.value = false;
    }
  }

  async function reinitialiserEnrichissement(item) {
    try {
      const rep = await appelApi(
        `/api/admin/${endpoint}/${item.id}/reinitialiser-enrichissement`,
        {
          method: "POST",
          signal: controleur.signal,
        },
      );
      if (rep.ok) {
        afficherToast(
          "Enrichissement réinitialisé — redécouverte au prochain cycle.",
          "succes",
        );
        await charger();
      } else {
        afficherToast("Erreur lors de la réinitialisation.", "erreur");
      }
    } catch (e) {
      if (e.name !== "AbortError") afficherToast("Erreur réseau.", "erreur");
    }
  }

  function ouvrirFusion() {
    if (selection.length !== 2) return;
    itemA.value = items.value.find((i) => i.id === selection[0]);
    itemB.value = items.value.find((i) => i.id === selection[1]);
    cibleChoisie.value = null;
    ouvrirDialogFusion();
  }

  async function confirmerFusion() {
    if (!cibleChoisie.value || enCoursFusion.value) return;
    const cible = cibleChoisie.value;
    const source = cible === itemA.value.id ? itemB.value.id : itemA.value.id;
    enCoursFusion.value = true;
    try {
      const rep = await appelApi(
        `/api/admin/${endpoint}/${source}/fusionner/${cible}`,
        {
          method: "POST",
          signal: controleur.signal,
        },
      );
      if (rep.ok) {
        fermerFusion();
        afficherToast(messageFusionne, "succes");
        selection.splice(0, selection.length);
        await charger();
      } else {
        const data = await lireJson(rep);
        afficherToast(data.erreur || "Erreur lors de la fusion.", "erreur");
      }
    } catch (e) {
      if (e.name !== "AbortError") afficherToast("Erreur réseau.", "erreur");
    } finally {
      enCoursFusion.value = false;
    }
  }

  onMounted(charger);
  onUnmounted(() => {
    controleur.abort();
    controleurActuel?.abort();
  });

  return {
    chargement,
    items,
    page,
    totalPages,
    total,
    filtreNom,
    filtreStatut,
    filtreWikidataManquant,
    selection,
    dialogEditionRef,
    edition,
    enCoursEdition,
    dialogFusionRef,
    itemA,
    itemB,
    cibleChoisie,
    enCoursFusion,
    charger,
    rechargerPage0,
    allerPage,
    basculerSelection,
    ouvrirEdition,
    sauvegarderEdition,
    reinitialiserEnrichissement,
    ouvrirFusion,
    confirmerFusion,
    fermerEdition,
    fermerFusion,
  };
}
