// Regroupement des jobs du tableau de bord admin par thème, à partir du catalogue renvoyé par
// GET /api/admin/jobs (nom, rôle et thème de chaque job, défini côté serveur).

export const THEME_A_CLASSER = "A_CLASSER";

/// Groupes de jobs dans l'ordre des thèmes, chaque job complété de son nom et de son rôle ; un
/// job absent du catalogue est rangé dans un groupe « À classer » sous son identifiant technique,
/// pour qu'il ne passe pas inaperçu.
export function grouperJobsParTheme(statsJobs, catalogue, themes) {
  const parType = new Map(catalogue.map((job) => [job.type, job]));
  const ordre = new Map(catalogue.map((job, index) => [job.type, index]));
  const groupes = themes.map((theme) => ({ ...theme, jobs: [] }));
  const aClasser = { code: THEME_A_CLASSER, libelle: "À classer", jobs: [] };
  for (const stat of statsJobs) {
    const job = parType.get(stat.typeJob);
    const groupe = job && groupes.find((g) => g.code === job.theme);
    if (groupe) {
      groupe.jobs.push({
        ...stat,
        libelle: job.libelle,
        description: job.description,
      });
    } else {
      aClasser.jobs.push({ ...stat, libelle: stat.typeJob, description: null });
    }
  }
  for (const groupe of groupes) {
    groupe.jobs.sort((a, b) => ordre.get(a.typeJob) - ordre.get(b.typeJob));
  }
  return [...groupes, aClasser].filter((groupe) => groupe.jobs.length > 0);
}
