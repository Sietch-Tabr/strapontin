import { ref, shallowRef, computed, watch, onMounted, onUnmounted } from "vue";
import { useRoute, useRouter } from "vue-router";
import { appelApi, lireJson } from "../utils/api.js";
import { useToasts } from "./useToasts.js";

/**
 * Liste de concerts filtrée par l'adresse de la page (recherche, mois, genre, classification,
 * tri, pagination), commune à Découverte et à Spectacles : chargement avec annulation de la
 * requête précédente, filtres locaux synchronisés avec l'adresse, changement de page.
 *
 * @param {object} options
 * @param {string} options.url chemin de l'API de la liste
 * @param {string} options.messageErreur message affiché si le chargement échoue
 * @param {(params: URLSearchParams) => void} [options.ajouterParametres] paramètres propres à la page
 */
export function useListeConcertsFiltree({
  url,
  messageErreur,
  ajouterParametres = () => {},
}) {
  const route = useRoute();
  const router = useRouter();
  const { afficherToast } = useToasts();

  const chargement = ref(false);
  const donnees = shallowRef(null);
  const filtresVisibles = ref(false);

  const qLocal = ref("");
  const moisLocal = ref("");
  const genreLocal = ref("");
  const classificationLocal = ref("");
  const triLocal = ref("date");

  const nbFiltresActifs = computed(() => {
    let n = 0;
    if (route.query.q) n++;
    if (route.query.mois) n++;
    if (route.query.genre) n++;
    if (route.query.classification) n++;
    return n;
  });

  function syncFiltresLocaux() {
    qLocal.value = route.query.q ?? "";
    moisLocal.value = route.query.mois ?? "";
    genreLocal.value = route.query.genre ?? "";
    classificationLocal.value = route.query.classification ?? "";
    triLocal.value = route.query.tri ?? "date";
  }

  // Un nouvel appel (changement de filtre pendant qu'un chargement précédent est en cours)
  // annule le précédent plutôt que de laisser deux requêtes concurrentes risquer d'appliquer
  // leurs résultats dans le désordre.
  let controleurActuel = null;

  async function charger() {
    controleurActuel?.abort();
    const controleur = new AbortController();
    controleurActuel = controleur;
    chargement.value = true;
    try {
      const params = new URLSearchParams();
      ajouterParametres(params);
      if (route.query.q) params.set("q", route.query.q);
      if (route.query.mois) params.set("mois", route.query.mois);
      if (route.query.genre) params.set("genre", route.query.genre);
      if (route.query.classification)
        params.set("classification", route.query.classification);
      if (triLocal.value && triLocal.value !== "date")
        params.set("tri", triLocal.value);
      if (route.query.page) params.set("page", route.query.page);
      const rep = await appelApi(url + "?" + params, {
        signal: controleur.signal,
      });
      if (!rep.ok) throw new Error();
      donnees.value = await lireJson(rep);
    } catch (e) {
      if (e.name === "AbortError") return;
      afficherToast(messageErreur, "erreur");
    } finally {
      if (controleurActuel === controleur) chargement.value = false;
    }
  }

  function appliquerFiltres() {
    const q = { ...route.query };
    delete q.page;
    if (qLocal.value) q.q = qLocal.value;
    else delete q.q;
    if (moisLocal.value) q.mois = moisLocal.value;
    else delete q.mois;
    if (genreLocal.value) q.genre = genreLocal.value;
    else delete q.genre;
    if (classificationLocal.value) q.classification = classificationLocal.value;
    else delete q.classification;
    if (triLocal.value && triLocal.value !== "date") q.tri = triLocal.value;
    else delete q.tri;
    router.push({ query: q });
  }

  function changerPage(page) {
    router.push({ query: { ...route.query, page: String(page) } });
  }

  watch(
    () => route.query,
    () => {
      syncFiltresLocaux();
      charger();
    },
    { deep: true },
  );

  onMounted(() => {
    syncFiltresLocaux();
    charger();
  });

  onUnmounted(() => controleurActuel?.abort());

  return {
    chargement,
    donnees,
    filtresVisibles,
    qLocal,
    moisLocal,
    genreLocal,
    classificationLocal,
    triLocal,
    nbFiltresActifs,
    charger,
    appliquerFiltres,
    changerPage,
  };
}
