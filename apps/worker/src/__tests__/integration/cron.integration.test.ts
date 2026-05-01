import { describe, it, expect, beforeEach, afterEach } from "vitest";
import { Queue } from "bullmq";
import { planifierJobsUsers } from "../../cron.js";
import {
  dbTest,
  nettoyerUser,
  users,
  userArtistes,
  userConfig,
} from "./db-test.js";
import type { JobData } from "../../jobs.js";

const CONNEXION_REDIS_TEST = { host: "127.0.0.1", port: 16379, db: 1 };
let queue: Queue<JobData>;

beforeEach(async () => {
  queue = new Queue("concerts-test", { connection: CONNEXION_REDIS_TEST });
  await queue.obliterate({ force: true });
});

afterEach(async () => {
  await queue.obliterate({ force: true });
  await queue.close();
});

describe("planifierJobsUsers", () => {
  it("crée_jobs_base_avec_db_vide", async () => {
    await planifierJobsUsers(queue);

    const jobs = await queue.getJobs(["waiting", "delayed"]);
    const noms = jobs.map((j) => j.name);

    expect(noms).toContain("recalculer:matches");
    expect(noms).toContain("purger:concerts");
    expect(noms).toContain("notifier:digest");
    expect(noms).not.toContain("fetch:artistes:batch");
    expect(noms).not.toContain("fetch:region");
  });

  it("ajoute_batch_si_artistes_en_db", async () => {
    await dbTest.insert(users).values({
      id: "test-cron-u1",
      name: "Test",
      email: "cron1@integration.test",
      emailVerified: false,
      createdAt: new Date(),
      updatedAt: new Date(),
    });
    await dbTest.insert(userArtistes).values({
      userId: "test-cron-u1",
      artisteNormalise: "mika",
      artisteAffiche: "Mika",
    });

    try {
      await planifierJobsUsers(queue);

      const jobs = await queue.getJobs(["waiting", "delayed"]);
      const noms = jobs.map((j) => j.name);
      expect(noms).toContain("fetch:artistes:batch");
    } finally {
      await nettoyerUser("test-cron-u1");
    }
  });

  it("ajoute_region_si_depts_configurés", async () => {
    await dbTest.insert(users).values({
      id: "test-cron-u2",
      name: "Test2",
      email: "cron2@integration.test",
      emailVerified: false,
      createdAt: new Date(),
      updatedAt: new Date(),
    });
    await dbTest.insert(userConfig).values({
      userId: "test-cron-u2",
      departements: ["75", "69"],
    });

    try {
      await planifierJobsUsers(queue);

      const jobs = await queue.getJobs(["waiting", "delayed"]);
      const noms = jobs.map((j) => j.name);
      expect(noms).toContain("fetch:region");

      const regionJob = jobs.find((j) => j.name === "fetch:region");
      const data = regionJob?.data as { depts?: string[] } | undefined;
      expect(data?.depts).toEqual(expect.arrayContaining(["75", "69"]));
    } finally {
      await nettoyerUser("test-cron-u2");
    }
  });

  it("idempotent_deuxieme_appel_ne_duplique_pas_jobs_fixes", async () => {
    await planifierJobsUsers(queue);
    const apresPremiereExecution = await queue.getJobs(["waiting", "delayed"]);

    await planifierJobsUsers(queue);
    const apresDeuxiemeExecution = await queue.getJobs(["waiting", "delayed"]);

    // Les jobs à jobId fixe (matches__all, region__all, etc.) ne se doublent pas
    const jobnomsCounts = apresDeuxiemeExecution.reduce<Record<string, number>>(
      (acc, j) => {
        acc[j.name] = (acc[j.name] ?? 0) + 1;
        return acc;
      },
      {},
    );

    // Aucun job avec jobId fixe ne doit apparaître deux fois
    expect(jobnomsCounts["recalculer:matches"]).toBeLessThanOrEqual(1);
    expect(apresDeuxiemeExecution.length).toBeLessThanOrEqual(
      apresPremiereExecution.length + 1,
    );
  });
});
