import adapter from "@sveltejs/adapter-node";
import { fileURLToPath } from "url";
import { resolve, dirname } from "path";

const __dirname = dirname(fileURLToPath(import.meta.url));

/** @type {import('@sveltejs/kit').Config} */
const config = {
  compilerOptions: {
    runes: ({ filename }) =>
      filename.split(/[/\\]/).includes("node_modules") ? undefined : true,
  },
  kit: {
    adapter: adapter({
      // better-auth contient des annotations @__NO_SIDE_EFFECTS__ que Rollup
      // ne sait pas interpréter à certaines positions — on les ignore silencieusement.
      rollupOptions: {
        onwarn(warning, warn) {
          if (warning.code === "INVALID_ANNOTATION") return;
          warn(warning);
        },
      },
    }),
    env: {
      dir: resolve(__dirname, "../.."),
    },
  },
};

export default config;
