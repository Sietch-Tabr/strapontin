import { env } from "$env/dynamic/private";

export const JAVA_API_URL = env.JAVA_API_URL ?? "http://localhost:8080";

// Entêtes communs pour les appels serveur → Java avec cookie de session.
export function entetesJava(sessionCookie: string | undefined): HeadersInit {
  const entetes: Record<string, string> = {
    "Content-Type": "application/json",
  };
  if (sessionCookie) {
    entetes["Cookie"] = `SESSION=${sessionCookie}`;
  }
  return entetes;
}

// Appel GET vers Java, retourne les données JSON ou null si erreur.
export async function getJava<T>(
  sessionCookie: string | undefined,
  chemin: string,
): Promise<T | null> {
  try {
    const rep = await fetch(`${JAVA_API_URL}${chemin}`, {
      headers: entetesJava(sessionCookie),
    });
    if (!rep.ok) return null;
    return (await rep.json()) as T;
  } catch {
    return null;
  }
}
