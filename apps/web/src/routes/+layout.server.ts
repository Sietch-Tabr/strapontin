import type { LayoutServerLoad } from "./$types";

export const load: LayoutServerLoad = ({ locals }) => {
  return {
    theme: locals.theme as "auto" | "clair" | "sombre",
  };
};
