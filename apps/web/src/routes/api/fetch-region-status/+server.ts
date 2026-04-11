import type { RequestHandler } from "./$types";
import { QueueEvents } from "bullmq";
import { queue, connexionRedis } from "$lib/server/queue";
import { error } from "@sveltejs/kit";

const TIMEOUT_MS = 90_000;

export const GET: RequestHandler = async ({ url, locals, request }) => {
  if (!locals.user) throw error(401, "Non autorisé");

  const jobId = url.searchParams.get("jobId");
  if (!jobId) throw error(400, "jobId manquant");

  const stream = new ReadableStream({
    async start(controller) {
      const envoyer = (payload: string) => {
        controller.enqueue(new TextEncoder().encode(`data: ${payload}\n\n`));
      };

      // Si le job est déjà terminé (worker très rapide), notifier immédiatement
      const job = await queue.getJob(jobId).catch(() => null);
      if (job && (await job.isCompleted().catch(() => false))) {
        envoyer(JSON.stringify({ type: "completed" }));
        controller.close();
        return;
      }

      const queueEvents = new QueueEvents("concerts", {
        connection: connexionRedis,
      });

      const timer = setTimeout(() => {
        envoyer(JSON.stringify({ type: "timeout" }));
        controller.close();
        queueEvents.close();
      }, TIMEOUT_MS);

      const nettoyage = () => {
        clearTimeout(timer);
        queueEvents.close();
      };

      queueEvents.on("completed", ({ jobId: idTermine }) => {
        if (idTermine !== jobId) return;
        envoyer(JSON.stringify({ type: "completed" }));
        nettoyage();
        controller.close();
      });

      queueEvents.on("failed", ({ jobId: idEchoue }) => {
        if (idEchoue !== jobId) return;
        envoyer(JSON.stringify({ type: "failed" }));
        nettoyage();
        controller.close();
      });

      // Nettoyage si le client se déconnecte
      request.signal.addEventListener("abort", () => {
        nettoyage();
        controller.close();
      });
    },
  });

  return new Response(stream, {
    headers: {
      "Content-Type": "text/event-stream",
      "Cache-Control": "no-cache",
      Connection: "keep-alive",
    },
  });
};
