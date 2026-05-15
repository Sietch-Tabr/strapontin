import { JAVA_API_URL, entetesJava } from "$lib/server/api-java";
import { error } from "@sveltejs/kit";
import type { PageServerLoad, Actions } from "./$types";

export const load: PageServerLoad = async ({ cookies, url }) => {
  const session = cookies.get("SESSION");
  const afficherMasques = url.searchParams.get("masques") === "1";
  const indisponibles = url.searchParams.get("indisponibles");

  const params = new URLSearchParams();
  if (afficherMasques) params.set("masques", "true");
  if (indisponibles) params.set("indisponibles", indisponibles);

  const reponse = await fetch(
    `${JAVA_API_URL}/api/accueil?${params.toString()}`,
    { headers: entetesJava(session) },
  );

  if (!reponse.ok) {
    return {
      artistesGroupes: [],
      nbArtistes: 0,
      nbSalles: 0,
      inclureIndisponibles: false,
      afficherMasques,
      totalConcerts: 0,
      tronque: false,
    };
  }

  const donnees = (await reponse.json()) as {
    artistesGroupes: {
      artiste: string;
      artisteNorm: string;
      estTournee: boolean;
      estNew: boolean;
      concerts: {
        id: string;
        artiste: string;
        artisteNorm: string;
        date: string;
        heure: string | null;
        salle: string;
        ville: string | null;
        url: string | null;
        statut: string | null;
        estNew: boolean;
      }[];
    }[];
    nbArtistes: number;
    nbSalles: number;
    totalConcerts: number;
    tronque: boolean;
  };

  return {
    artistesGroupes: donnees.artistesGroupes ?? [],
    nbArtistes: donnees.nbArtistes ?? 0,
    nbSalles: donnees.nbSalles ?? 0,
    inclureIndisponibles: indisponibles === "1",
    afficherMasques,
    totalConcerts: donnees.totalConcerts ?? 0,
    tronque: donnees.tronque ?? false,
  };
};

export const actions: Actions = {
  masquer: async ({ request, cookies }) => {
    const donnees = await request.formData();
    const concertId = donnees.get("concertId");
    if (!concertId || typeof concertId !== "string") {
      error(400, "Concert manquant");
    }
    const session = cookies.get("SESSION");
    await fetch(
      `${JAVA_API_URL}/api/spectacles/matches/${encodeURIComponent(concertId)}`,
      {
        method: "PATCH",
        headers: entetesJava(session),
        body: JSON.stringify({ masquer: true }),
      },
    );
  },

  masquerSerie: async ({ request, cookies }) => {
    const donnees = await request.formData();
    const artisteNorm = donnees.get("artisteNorm");
    if (!artisteNorm || typeof artisteNorm !== "string") {
      error(400, "Artiste manquant");
    }
    const session = cookies.get("SESSION");
    await fetch(`${JAVA_API_URL}/api/spectacles/masquer-artiste`, {
      method: "POST",
      headers: entetesJava(session),
      body: JSON.stringify({ artisteNorm }),
    });
  },

  demasquer: async ({ request, cookies }) => {
    const donnees = await request.formData();
    const concertId = donnees.get("concertId");
    if (!concertId || typeof concertId !== "string") {
      error(400, "Concert manquant");
    }
    const session = cookies.get("SESSION");
    await fetch(
      `${JAVA_API_URL}/api/spectacles/matches/${encodeURIComponent(concertId)}`,
      {
        method: "PATCH",
        headers: entetesJava(session),
        body: JSON.stringify({ masquer: false }),
      },
    );
  },
};
