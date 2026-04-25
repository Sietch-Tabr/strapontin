import tailwindcss from "@tailwindcss/vite";
import { sveltekit } from "@sveltejs/kit/vite";
import { defineConfig } from "vite";
import { resolve } from "path";

// better-auth contient des annotations @__NO_SIDE_EFFECTS__ à des positions
// que Rollup (utilisé par adapter-node) ne sait pas interpréter.
// On les supprime pendant la phase de transform Vite pour qu'elles ne
// se retrouvent pas dans le bundle server retraité par adapter-node.
function stripBetterAuthAnnotations() {
  return {
    name: "strip-better-auth-no-side-effects",
    renderChunk(code: string) {
      if (code.includes("@__NO_SIDE_EFFECTS__")) {
        return { code: code.replace(/@__NO_SIDE_EFFECTS__/g, ""), map: null };
      }
      return null;
    },
  };
}

export default defineConfig({
  envDir: resolve(__dirname, "../.."),
  plugins: [stripBetterAuthAnnotations(), tailwindcss(), sveltekit()],
  server: {
    host: true,
  },
});
