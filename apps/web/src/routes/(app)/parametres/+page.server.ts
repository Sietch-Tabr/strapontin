import { JAVA_API_URL, entetesJava } from "$lib/server/api-java";
import { fail, redirect } from "@sveltejs/kit";
import { dev } from "$app/environment";
import type { PageServerLoad, Actions } from "./$types";
import { FORMAT_DEPT } from "$lib/constantes/validations";

function normaliserDept(d: string): string | null {
  const valeur = d.trim().toUpperCase();
  if (/^2[AB]$/.test(valeur)) return "20";
  if (FORMAT_DEPT.test(valeur)) return valeur;
  return null;
}

export const load: PageServerLoad = async ({ cookies }) => {
  const session = cookies.get("SESSION");

  const reponse = await fetch(`${JAVA_API_URL}/api/user/config`, {
    headers: entetesJava(session),
  });

  let config: {
    departements?: string[];
    filtreDisponibles?: boolean;
    frequenceNotifications?: string;
    notifArtistes?: boolean;
    notifSalles?: boolean;
    theme?: string;
    resendDisponible?: boolean;
  } | null = null;
  if (reponse.ok) {
    config = await reponse.json();
  }

  return {
    config,
    theme: (cookies.get("theme") ?? "auto") as "auto" | "clair" | "sombre",
    resendDisponible: config?.resendDisponible ?? false,
  };
};

export const actions: Actions = {
  departements: async ({ request, cookies }) => {
    const donnees = await request.formData();
    const depts = (donnees.get("departements")?.toString() ?? "")
      .split(",")
      .map((d) => normaliserDept(d))
      .filter((d): d is string => d !== null);

    const session = cookies.get("SESSION");
    const rep = await fetch(`${JAVA_API_URL}/api/user/config/departements`, {
      method: "PATCH",
      headers: entetesJava(session),
      body: JSON.stringify({ departements: depts.join(",") }),
    });

    if (!rep.ok) {
      return fail(rep.status, { erreur: "Erreur lors de la mise à jour." });
    }
    return { succes: "departements" };
  },

  rechercheRapide: async ({ request, cookies }) => {
    const donnees = await request.formData();
    const filtreDisponibles =
      donnees.get("filtreDisponibles") === "on" ||
      donnees.get("filtreDisponibles") === "true";

    const session = cookies.get("SESSION");
    const rep = await fetch(`${JAVA_API_URL}/api/user/config/filtres`, {
      method: "PATCH",
      headers: entetesJava(session),
      body: JSON.stringify({ filtreDisponibles }),
    });

    if (!rep.ok)
      return fail(rep.status, { erreur: "Erreur lors de la mise à jour." });
    return { succes: "rechercheRapide" };
  },

  notifications: async ({ request, cookies }) => {
    const donnees = await request.formData();
    const valeur = donnees.get("frequenceNotifications")?.toString();
    const valeurs = ["jamais", "temps_reel", "quotidien"] as const;
    type FrequenceNotifications = (typeof valeurs)[number];

    if (!valeurs.includes(valeur as FrequenceNotifications)) {
      return fail(400, {
        erreur: "Valeur invalide.",
        section: "notifications",
      });
    }

    const frequenceNotifications = valeur as FrequenceNotifications;
    const notifArtistes = donnees.get("notifArtistes") === "on";
    const notifSalles = donnees.get("notifSalles") === "on";

    const session = cookies.get("SESSION");
    const rep = await fetch(`${JAVA_API_URL}/api/user/config/notifications`, {
      method: "PATCH",
      headers: entetesJava(session),
      body: JSON.stringify({
        frequenceNotifications,
        notifArtistes,
        notifSalles,
      }),
    });

    if (!rep.ok)
      return fail(rep.status, {
        erreur: "Erreur lors de la mise à jour.",
        section: "notifications",
      });
    return { succes: "notifications" };
  },

  theme: async ({ request, cookies }) => {
    const donnees = await request.formData();
    const valeur = donnees.get("valeur")?.toString();

    if (!["auto", "clair", "sombre"].includes(valeur ?? "")) {
      return fail(400, {
        erreur: "Valeur de thème invalide.",
        section: "theme",
      });
    }

    const theme = valeur as "auto" | "clair" | "sombre";
    cookies.set("theme", theme, {
      path: "/",
      maxAge: 60 * 60 * 24 * 365,
      httpOnly: false,
      secure: !dev,
      sameSite: "lax",
    });

    const session = cookies.get("SESSION");
    await fetch(`${JAVA_API_URL}/api/user/config/theme`, {
      method: "PATCH",
      headers: entetesJava(session),
      body: JSON.stringify({ valeur: theme }),
    });

    return { succes: "theme", theme };
  },

  compte: async ({ request, cookies }) => {
    const donnees = await request.formData();
    const nom = donnees.get("nom")?.toString().trim() ?? "";

    if (!nom)
      return fail(400, {
        erreur: "Le nom ne peut pas être vide.",
        section: "compte",
      });
    if (nom.length > 100)
      return fail(400, {
        erreur: "Nom trop long (100 caractères max).",
        section: "compte",
      });

    const session = cookies.get("SESSION");
    const rep = await fetch(`${JAVA_API_URL}/api/user/profile`, {
      method: "PATCH",
      headers: entetesJava(session),
      body: JSON.stringify({ nom }),
    });

    if (!rep.ok) {
      const corps = (await rep
        .json()
        .catch(() => ({ erreur: "Erreur inattendue." }))) as {
        erreur?: string;
      };
      return fail(rep.status, {
        erreur: corps.erreur ?? "Erreur inattendue.",
        section: "compte",
      });
    }
    return { succes: "compte" };
  },

  identifiant: async ({ request, cookies }) => {
    const donnees = await request.formData();
    const identifiant =
      donnees.get("identifiant")?.toString().trim().toLowerCase() ?? "";

    const session = cookies.get("SESSION");
    const rep = await fetch(`${JAVA_API_URL}/api/user/identifiant`, {
      method: "PATCH",
      headers: entetesJava(session),
      body: JSON.stringify({ identifiant }),
    });

    if (!rep.ok) {
      const corps = (await rep
        .json()
        .catch(() => ({ erreur: "Erreur inattendue." }))) as {
        erreur?: string;
      };
      return fail(rep.status, {
        erreur: corps.erreur ?? "Erreur inattendue.",
        section: "identifiant",
      });
    }
    return { succes: "identifiant" };
  },

  email: async ({ request, cookies }) => {
    const donnees = await request.formData();
    const email = donnees.get("email")?.toString().trim().toLowerCase() ?? "";

    const session = cookies.get("SESSION");
    const rep = await fetch(`${JAVA_API_URL}/api/user/profile`, {
      method: "PATCH",
      headers: entetesJava(session),
      body: JSON.stringify({ email }),
    });

    if (!rep.ok) {
      const corps = (await rep
        .json()
        .catch(() => ({ erreur: "Erreur inattendue." }))) as {
        erreur?: string;
      };
      return fail(rep.status, {
        erreur: corps.erreur ?? "Erreur inattendue.",
        section: "email",
      });
    }
    return { succes: "email" };
  },

  supprimerCompte: async ({ cookies }) => {
    const session = cookies.get("SESSION");
    await fetch(`${JAVA_API_URL}/api/user`, {
      method: "DELETE",
      headers: entetesJava(session),
    });
    cookies.delete("theme", { path: "/" });
    redirect(302, "/login");
  },

  motDePasse: async ({ request, cookies }) => {
    const donnees = await request.formData();
    const motDePasseActuel = donnees.get("motDePasseActuel")?.toString() ?? "";
    const motDePasseNouveau =
      donnees.get("motDePasseNouveau")?.toString() ?? "";
    const motDePasseConfirmation =
      donnees.get("motDePasseConfirmation")?.toString() ?? "";

    if (!motDePasseActuel || !motDePasseNouveau) {
      return fail(400, {
        erreur: "Tous les champs sont obligatoires.",
        section: "motDePasse",
      });
    }
    if (motDePasseNouveau.length < 12) {
      return fail(400, {
        erreur: "Le nouveau mot de passe doit contenir au moins 12 caractères.",
        section: "motDePasse",
      });
    }
    if (motDePasseNouveau !== motDePasseConfirmation) {
      return fail(400, {
        erreur: "Les mots de passe ne correspondent pas.",
        section: "motDePasse",
      });
    }

    const session = cookies.get("SESSION");
    const rep = await fetch(`${JAVA_API_URL}/api/user/password`, {
      method: "POST",
      headers: entetesJava(session),
      body: JSON.stringify({ motDePasseActuel, motDePasseNouveau }),
    });

    if (!rep.ok) {
      const corps = (await rep
        .json()
        .catch(() => ({ erreur: "Mot de passe actuel incorrect." }))) as {
        erreur?: string;
      };
      return fail(rep.status, {
        erreur: corps.erreur ?? "Mot de passe actuel incorrect.",
        section: "motDePasse",
      });
    }
    return { succes: "motDePasse" };
  },

  importerPreferences: async () => {
    return fail(501, {
      erreur: "L'import de préférences n'est pas encore disponible en v2.",
      section: "import",
    });
  },
};
