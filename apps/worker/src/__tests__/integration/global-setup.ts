import { createConnection } from "net";
import { spawn } from "child_process";
import type { ChildProcess } from "child_process";

const LXC_HOST = "192.168.20.78";
const PORT_PG_LOCAL = 15432;
const PORT_REDIS_LOCAL = 16379;

let tunnelProcess: ChildProcess | null = null;

function portAccessible(port: number): Promise<boolean> {
  return new Promise((resolve) => {
    const socket = createConnection({ port, host: "127.0.0.1" });
    socket.setTimeout(500);
    socket.on("connect", () => {
      socket.destroy();
      resolve(true);
    });
    socket.on("error", () => resolve(false));
    socket.on("timeout", () => {
      socket.destroy();
      resolve(false);
    });
  });
}

function attendre(ms: number) {
  return new Promise((r) => setTimeout(r, ms));
}

export async function setup() {
  const pgOk = await portAccessible(PORT_PG_LOCAL);
  const redisOk = await portAccessible(PORT_REDIS_LOCAL);
  if (pgOk && redisOk) return;

  tunnelProcess = spawn(
    "ssh",
    [
      "-N",
      "-L",
      `${PORT_PG_LOCAL}:127.0.0.1:5432`,
      "-L",
      `${PORT_REDIS_LOCAL}:127.0.0.1:6379`,
      "-o",
      "StrictHostKeyChecking=no",
      "-o",
      "BatchMode=yes",
      `root@${LXC_HOST}`,
    ],
    { stdio: "ignore" },
  );

  for (let i = 0; i < 25; i++) {
    await attendre(200);
    if (
      (await portAccessible(PORT_PG_LOCAL)) &&
      (await portAccessible(PORT_REDIS_LOCAL))
    )
      return;
  }

  tunnelProcess.kill();
  throw new Error(
    `Tunnel SSH vers ${LXC_HOST} impossible.\n` +
      "Démarrer le LXC dev : pct start 114",
  );
}

export async function teardown() {
  if (tunnelProcess) {
    tunnelProcess.kill();
    tunnelProcess = null;
  }
}
