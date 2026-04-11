// Codes des départements métropolitains français (01–95)
// 2A et 2B (Corse) sont normalisés en "20" côté serveur
export const TOUS_LES_DEPTS: string[] = Array.from({ length: 95 }, (_, i) =>
  String(i + 1).padStart(2, "0"),
);
