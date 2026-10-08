// Test harness for tools/lib/e2e-seed.mjs (used by E2eStackToolTest): runs seed() against an in-memory
// fake of the admin API and prints the resulting state as JSON. `node e2e-seed-harness.mjs <scenario> [pdf-out]`
//   twice    — seed twice on an empty store
//   foreign  — a mission with other tasks already holds the seed's topic + position
//   pdf      — write blankPdf(2) to [pdf-out]
import { writeFileSync } from "node:fs";
import { SEED_MISSION, blankPdf, seed } from "../lib/e2e-seed.mjs";

class FakeAdminApi {
  constructor() {
    this.sources = [];
    this.tasks = [];
    this.missions = [];
    this.n = 0;
  }

  id() {
    this.n += 1;
    return `00000000-0000-4000-8000-${String(this.n).padStart(12, "0")}`;
  }

  async request(method, path, body) {
    const route = `${method} ${path.replace(/[0-9a-f-]{36}/, ":id")}`;
    const id = path.match(/[0-9a-f-]{36}/)?.[0];
    switch (route) {
      case "GET /api/admin/sources":
        return structuredClone(this.sources);
      case "POST /api/admin/sources": {
        const pdf = Buffer.from(await body.get("file").arrayBuffer()).toString("latin1");
        if (!pdf.startsWith("%PDF-")) throw new Error("not a PDF");
        const source = { sourceId: this.id(), title: body.get("title"), grade: Number(body.get("grade")) };
        this.sources.push(source);
        return structuredClone(source);
      }
      case "GET /api/admin/tasks":
        return this.tasks.map((t) => ({ taskId: t.taskId, promptExcerpt: t.prompt, status: t.status }));
      case "GET /api/admin/tasks/:id":
        return structuredClone(this.tasks.find((t) => t.taskId === id));
      case "POST /api/admin/tasks": {
        const task = { ...structuredClone(body), taskId: this.id(), status: "DRAFT" };
        this.tasks.push(task);
        return structuredClone(task);
      }
      case "PUT /api/admin/tasks/:id/status":
        this.tasks.find((t) => t.taskId === id).status = body.status;
        return null;
      case "GET /api/admin/missions":
        return structuredClone(this.missions);
      case "POST /api/admin/missions": {
        const mission = { ...structuredClone(body), missionId: this.id(), status: "DRAFT" };
        this.missions.push(mission);
        return structuredClone(mission);
      }
      case "PUT /api/admin/missions/:id/status":
        this.missions.find((m) => m.missionId === id).status = body.status;
        return null;
      default:
        throw new Error(`unexpected ${route}`);
    }
  }

  state() {
    return {
      sources: this.sources.map((s) => s.title),
      tasks: this.tasks.map((t) => `${t.status} ${t.parts[0].spec.type} ${t.prompt}`),
      missions: this.missions.map((m) => `${m.status} ${m.position} ${m.taskIds.length}`),
    };
  }
}

const [scenario, out] = process.argv.slice(2);
const api = new FakeAdminApi();
if (scenario === "twice") {
  const first = await seed(api);
  const second = await seed(api);
  console.log(JSON.stringify({ same: JSON.stringify(first) === JSON.stringify(second), ...api.state() }));
} else if (scenario === "foreign") {
  api.missions.push({ missionId: api.id(), topicId: SEED_MISSION.topicId, position: SEED_MISSION.position, taskIds: [], status: "DRAFT" });
  try {
    await seed(api);
    console.log("SEEDED");
  } catch (e) {
    console.log(`refused: ${e.message}`);
  }
} else if (scenario === "pdf") {
  writeFileSync(out, blankPdf(2));
  console.log("written");
} else {
  console.error("usage: twice | foreign | pdf <out>");
  process.exit(2);
}
