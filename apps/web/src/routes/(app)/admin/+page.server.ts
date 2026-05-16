import { JAVA_API_URL, entetesJava } from "$lib/server/api-java";
import { fail, redirect } from "@sveltejs/kit";
import type { PageServerLoad, Actions } from "./$types";
import { FORMAT_EMAIL } from "$lib/constantes/validations";

export const load: PageServerLoad = async ({ locals, cookies }) => {
  if (locals.user?.role !== "admin") {
    redirect(302, "/");
  }

  const session = cookies.get("SESSION");

  const [repUsers, repJobs, repLogs, repConfig] = await Promise.all([
    fetch(`${JAVA_API_URL}/api/admin/utilisateurs`, {
      headers: entetesJava(session),
    }),
    fetch(`${JAVA_API_URL}/api/admin/jobs`, { headers: entetesJava(session) }),
    fetch(`${JAVA_API_URL}/api/admin/logs`, { headers: entetesJava(session) }),
    fetch(`${JAVA_API_URL}/api/admin/config`, {
      headers: entetesJava(session),
    }),
  ]);

  const utilisateurs = repUsers.ok
    ? ((await repUsers.json()) as {
        id: string;
        name: string;
        email: string;
        role: string;
        username: string;
        mustChangeCredentials: boolean;
        createdAt: string;
        derniereActivite: string | null;
      }[])
    : [];

  type StatJobJava = {
    typeJob: string;
    derniereExec: string | null;
    dureeMs: number | null;
    erreur: string | null;
  };

  const jobsReponse = repJobs.ok
    ? ((await repJobs.json()) as { statsJobs: StatJobJava[]; quotaTM: unknown })
    : null;

  const statsJobsArr: StatJobJava[] = jobsReponse?.statsJobs ?? [];

  function transformerStatJob(stat: StatJobJava) {
    return {
      finRun: stat.derniereExec,
      durationMs: stat.dureeMs,
      statut: stat.erreur ? "error" : stat.derniereExec ? "ok" : null,
      erreur: stat.erreur,
      debutRun: null as string | null,
    };
  }

  const statsJobs = {
    artistesBatch: statsJobsArr.find(
      (s) => s.typeJob === "fetch:artistes:batch",
    )
      ? transformerStatJob(
          statsJobsArr.find((s) => s.typeJob === "fetch:artistes:batch")!,
        )
      : null,
    region: statsJobsArr.find((s) => s.typeJob === "fetch:region")
      ? transformerStatJob(
          statsJobsArr.find((s) => s.typeJob === "fetch:region")!,
        )
      : null,
  };

  const workerLastSuccess =
    statsJobsArr
      .filter((s) => !s.erreur && s.derniereExec)
      .map((s) => s.derniereExec as string)
      .sort()
      .pop() ?? null;

  const quotaTM = jobsReponse?.quotaTM ?? null;
  const logsAdmin = repLogs.ok ? await repLogs.json() : [];
  const configStatuts = repConfig.ok ? await repConfig.json() : [];

  return {
    utilisateur: locals.user,
    utilisateurs,
    statsJobs,
    workerLastSuccess,
    quotaTM,
    logsAdmin,
    configStatuts,
  };
};

export const actions: Actions = {
  creerCompte: async ({ request, cookies, locals }) => {
    if (locals.user?.role !== "admin")
      return fail(403, { erreur: "Accès refusé." });

    const donnees = await request.formData();
    const email = donnees.get("email")?.toString().trim() ?? "";

    if (!email || !FORMAT_EMAIL.test(email)) {
      return fail(400, { erreur: "Email invalide." });
    }

    const session = cookies.get("SESSION");
    const rep = await fetch(`${JAVA_API_URL}/api/admin/utilisateurs`, {
      method: "POST",
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
        erreur: corps.erreur ?? "Impossible de créer le compte.",
      });
    }
    return { succes: "creerCompte" };
  },

  supprimerCompte: async ({ request, cookies, locals }) => {
    if (locals.user?.role !== "admin")
      return fail(403, { erreur: "Accès refusé." });

    const donnees = await request.formData();
    const userId = donnees.get("userId")?.toString() ?? "";

    if (!userId)
      return fail(400, { erreur: "Identifiant utilisateur manquant." });
    if (userId === locals.user.id)
      return fail(400, {
        erreur: "Impossible de supprimer son propre compte.",
      });

    const session = cookies.get("SESSION");
    const rep = await fetch(
      `${JAVA_API_URL}/api/admin/utilisateurs/${encodeURIComponent(userId)}`,
      {
        method: "DELETE",
        headers: entetesJava(session),
      },
    );

    if (!rep.ok) {
      const corps = (await rep
        .json()
        .catch(() => ({ erreur: "Erreur inattendue." }))) as {
        erreur?: string;
      };
      return fail(rep.status, {
        erreur: corps.erreur ?? "Erreur lors de la suppression.",
      });
    }
    return { succes: "supprimerCompte" };
  },

  resetMdp: async ({ request, cookies, locals }) => {
    if (locals.user?.role !== "admin")
      return fail(403, { erreur: "Accès refusé." });

    const donnees = await request.formData();
    const userId = donnees.get("userId")?.toString() ?? "";

    if (!userId)
      return fail(400, { erreur: "Identifiant utilisateur manquant." });

    const session = cookies.get("SESSION");
    const rep = await fetch(
      `${JAVA_API_URL}/api/admin/utilisateurs/${encodeURIComponent(userId)}/reset-mdp`,
      {
        method: "POST",
        headers: entetesJava(session),
      },
    );

    if (!rep.ok) {
      const corps = (await rep
        .json()
        .catch(() => ({ erreur: "Erreur inattendue." }))) as {
        erreur?: string;
      };
      return fail(rep.status, {
        erreur: corps.erreur ?? "Erreur lors du reset.",
      });
    }
    const corps = (await rep.json().catch(() => ({}))) as { email?: string };
    return { succes: "resetMdp", email: corps.email ?? "" };
  },

  majConfig: async ({ request, cookies, locals }) => {
    if (locals.user?.role !== "admin")
      return fail(403, { erreur: "Accès refusé." });

    const donnees = await request.formData();
    const corps: Record<string, string> = {};
    for (const [cle, val] of donnees.entries()) {
      if (typeof val === "string" && val.trim()) {
        corps[cle] = val.trim();
      }
    }

    const session = cookies.get("SESSION");
    const rep = await fetch(`${JAVA_API_URL}/api/admin/config`, {
      method: "PATCH",
      headers: entetesJava(session),
      body: JSON.stringify(corps),
    });

    if (!rep.ok) {
      const corpsRep = (await rep
        .json()
        .catch(() => ({ erreur: "Erreur inattendue." }))) as {
        erreur?: string;
      };
      return fail(rep.status, {
        erreur: corpsRep.erreur ?? "Erreur lors de la sauvegarde.",
      });
    }
    return { succes: "majConfig", cle: corps["cle"] ?? "" };
  },

  genererVapid: async ({ cookies, locals }) => {
    if (locals.user?.role !== "admin")
      return fail(403, { erreur: "Accès refusé." });

    const session = cookies.get("SESSION");
    const rep = await fetch(`${JAVA_API_URL}/api/admin/vapid/generer`, {
      method: "POST",
      headers: entetesJava(session),
    });

    if (!rep.ok) {
      return fail(rep.status, {
        erreur: "Erreur lors de la génération VAPID.",
      });
    }
    return { succes: "genererVapid" };
  },

  modifierCompte: async ({ request, cookies, locals }) => {
    if (locals.user?.role !== "admin")
      return fail(403, { erreur: "Accès refusé." });

    const donnees = await request.formData();
    const userId = donnees.get("userId")?.toString() ?? "";
    const nom = donnees.get("nom")?.toString().trim() ?? "";
    const email = donnees.get("email")?.toString().trim().toLowerCase() ?? "";

    if (!userId) return fail(400, { erreur: "Identifiant utilisateur manquant." });

    const session = cookies.get("SESSION");
    const rep = await fetch(
      `${JAVA_API_URL}/api/admin/utilisateurs/${encodeURIComponent(userId)}`,
      {
        method: "PATCH",
        headers: entetesJava(session),
        body: JSON.stringify({ nom, email }),
      },
    );

    if (!rep.ok) {
      const corps = (await rep
        .json()
        .catch(() => ({ erreur: "Erreur inattendue." }))) as {
        erreur?: string;
      };
      return fail(rep.status, {
        erreur: corps.erreur ?? "Erreur lors de la modification.",
      });
    }
    return { succes: "modifierCompte" };
  },

  relancerFetches: async ({ cookies, locals }) => {
    if (locals.user?.role !== "admin")
      return fail(403, { erreur: "Accès refusé." });

    const session = cookies.get("SESSION");
    const rep = await fetch(`${JAVA_API_URL}/api/admin/relancer`, {
      method: "POST",
      headers: entetesJava(session),
    });

    if (!rep.ok) {
      const corps = (await rep
        .json()
        .catch(() => ({ erreur: "Worker inaccessible." }))) as {
        erreur?: string;
      };
      return fail(rep.status, {
        erreur: corps.erreur ?? "Impossible de déclencher les fetches.",
      });
    }
    return { succes: "relancerFetches" };
  },
};
