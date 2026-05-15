import { JAVA_API_URL, entetesJava } from "$lib/server/api-java";
import { error } from "@sveltejs/kit";
import type { PageServerLoad, Actions } from "./$types";

export const load: PageServerLoad = async ({ cookies, url }) => {
  const session = cookies.get("SESSION");

  const recherche = (url.searchParams.get("q")?.trim() ?? "").slice(0, 100);
  const mois = url.searchParams.get("mois")?.trim() ?? "";
  const classification = url.searchParams.get("classification")?.trim() ?? "";
  const genre = (url.searchParams.get("genre")?.trim() ?? "").slice(0, 50);
  const quinzaine = url.searchParams.get("quinzaine") === "1";
  const tri = url.searchParams.get("tri") === "nouveaute" ? "nouveaute" : "date";
  const afficherMasques = url.searchParams.get("masques") === "1";
  const budgetStr = url.searchParams.get("budget")?.trim() ?? "";
  const sourcesUrl = url.searchParams.getAll("source");
  const indisponibles = url.searchParams.get("indisponibles");
  const page = Math.max(0, parseInt(url.searchParams.get("page") ?? "0") || 0);

  const params = new URLSearchParams();
  if (recherche) params.set("q", recherche);
  if (mois) params.set("mois", mois);
  if (classification) params.set("classification", classification);
  if (genre) params.set("genre", genre);
  if (quinzaine) params.set("quinzaine", "true");
  params.set("tri", tri);
  if (afficherMasques) params.set("masques", "true");
  if (budgetStr) params.set("budget", budgetStr);
  for (const s of sourcesUrl) params.append("source", s);
  if (indisponibles) params.set("indisponibles", indisponibles);
  params.set("page", String(page));

  const reponse = await fetch(
    `${JAVA_API_URL}/api/spectacles?${params.toString()}`,
    { headers: entetesJava(session) },
  );

  if (!reponse.ok) {
    return {
      tousLesConcerts: [],
      recherche,
      mois,
      classification,
      genre,
      quinzaine,
      tri,
      budget: budgetStr,
      sourceArtistes: true,
      sourceSalles: true,
      genresDisponibles: [],
      moisDisponibles: [],
      afficherMasques,
      inclureIndisponibles: false,
      page,
      totalPages: 1,
      totalConcerts: 0,
    };
  }

  const donnees = (await reponse.json()) as {
    concerts: {
      id: string;
      artiste: string;
      date: string;
      heure: string | null;
      salle: string;
      ville: string | null;
      url: string | null;
      prixMin: number | null;
      prixMax: number | null;
      classification: string | null;
      firstSeen: string | null;
      statut: string | null;
      estNew: boolean;
    }[];
    page: number;
    totalPages: number;
    totalConcerts: number;
    genresDisponibles: string[];
    moisDisponibles: string[];
  };

  const sourceArtistes = sourcesUrl.length === 0 || sourcesUrl.includes("artistes");
  const sourceSalles = sourcesUrl.length === 0 || sourcesUrl.includes("salles");

  return {
    tousLesConcerts: donnees.concerts ?? [],
    recherche,
    mois,
    classification,
    genre,
    quinzaine,
    tri,
    budget: budgetStr,
    sourceArtistes,
    sourceSalles,
    genresDisponibles: donnees.genresDisponibles ?? [],
    moisDisponibles: donnees.moisDisponibles ?? [],
    afficherMasques,
    inclureIndisponibles: indisponibles === "1",
    page: donnees.page ?? page,
    totalPages: donnees.totalPages ?? 1,
    totalConcerts: donnees.totalConcerts ?? 0,
  };
};

export const actions: Actions = {
  demasquer: async ({ request, cookies }) => {
    const donnees = await request.formData();
    const concertId = donnees.get("concertId");
    if (!concertId || typeof concertId !== "string") {
      error(400, "Concert manquant");
    }
    const session = cookies.get("SESSION");
    await fetch(`${JAVA_API_URL}/api/spectacles/matches/${encodeURIComponent(concertId)}`, {
      method: "PATCH",
      headers: entetesJava(session),
      body: JSON.stringify({ masquer: false }),
    });
  },
};
