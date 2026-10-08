// Seed data for the isolated e2e stack: self-authored, PII-free (NFR-3), no workbook content
// (NFR-4). Applied through the public admin API only, idempotently: each item is matched by its
// title / prompt (the mission by topic + position, because missions have no name — CA-D17) and
// created or published only when missing.

/** V2-seeded grade-6 topic "Přirozená čísla" (self-authored reference data). */
export const SEED_TOPIC_ID = "6a000000-0000-4000-8000-000000000001";

export const SEED_SOURCE = { title: "E2E Seed Source", grade: 6, subject: "math", pages: 2 };

export const SEED_TASKS = [
  {
    prompt: "E2E Seed: Kolik je 6 · 7?",
    parts: [{ key: "a", label: "a", spec: { type: "INTEGER", accepted: ["42"] } }],
    hints: ["Sečti sedm šestek."],
    taskNo: "1",
  },
  {
    prompt: "E2E Seed: Kolik je 1,5 + 2,25?",
    parts: [{ key: "a", label: "a", spec: { type: "DECIMAL", accepted: ["3,75"] } }],
    hints: ["Zarovnej desetinné čárky pod sebe."],
    taskNo: "2",
  },
  {
    prompt: "E2E Seed: Které číslo je sudé?",
    parts: [
      {
        key: "a",
        label: "a",
        spec: {
          type: "CHOICE_SINGLE",
          accepted: ["B"],
          options: [
            { key: "A", text: "7" },
            { key: "B", text: "8" },
            { key: "C", text: "9" },
          ],
        },
      },
    ],
    hints: ["Sudé číslo je dělitelné dvěma."],
    taskNo: "3",
  },
];

/** "E2E Seed Mission" — the contract has no mission name, so it is the mission at this topic + position. */
export const SEED_MISSION = { label: "E2E Seed Mission", topicId: SEED_TOPIC_ID, position: 1 };

/** A valid blank PDF with `pages` empty A4 pages, generated at run time (no committed binary). */
export function blankPdf(pages) {
  const kids = Array.from({ length: pages }, (_, i) => `${3 + i} 0 R`).join(" ");
  const objects = [
    "<< /Type /Catalog /Pages 2 0 R >>",
    `<< /Type /Pages /Kids [${kids}] /Count ${pages} /Resources << >> >>`,
    ...Array.from({ length: pages }, () => "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] >>"),
  ];
  let body = "%PDF-1.4\n";
  const offsets = [];
  objects.forEach((obj, i) => {
    offsets.push(body.length);
    body += `${i + 1} 0 obj\n${obj}\nendobj\n`;
  });
  const xref = body.length;
  body += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
  for (const off of offsets) body += `${String(off).padStart(10, "0")} 00000 n \n`;
  body += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return Buffer.from(body, "latin1");
}

/** Minimal cookie-jar admin client over fetch (session cookie + CSRF header, NFR-15). */
export class AdminClient {
  constructor(baseUrl) {
    this.baseUrl = baseUrl;
    this.cookies = new Map();
  }

  remember(res) {
    for (const c of res.headers.getSetCookie?.() ?? []) {
      const [pair] = c.split(";");
      const eq = pair.indexOf("=");
      this.cookies.set(pair.slice(0, eq).trim(), pair.slice(eq + 1).trim());
    }
  }

  async request(method, path, body, { expect = [200], json = true } = {}) {
    const headers = {};
    if (this.cookies.size) headers.Cookie = [...this.cookies].map(([k, v]) => `${k}=${v}`).join("; ");
    if (method !== "GET" && this.cookies.has("XSRF-TOKEN")) headers["X-XSRF-TOKEN"] = this.cookies.get("XSRF-TOKEN");
    if (body !== undefined && !(body instanceof FormData)) headers["Content-Type"] = "application/json";
    const res = await fetch(this.baseUrl + path, {
      method,
      headers,
      body: body === undefined ? undefined : body instanceof FormData ? body : JSON.stringify(body),
    });
    this.remember(res);
    const text = await res.text();
    if (!expect.includes(res.status)) {
      throw new Error(`${method} ${path} answered ${res.status}: ${text.slice(0, 300)}`);
    }
    return json && text ? JSON.parse(text) : null;
  }

  async signIn(password) {
    await this.request("POST", "/api/admin/session", { password }, { expect: [204], json: false });
    if (!this.cookies.has("XSRF-TOKEN")) await this.request("GET", "/api/admin/session", undefined, { json: false });
  }
}

/** Applies the seed; returns what it found or created. Running it twice leaves the same state. */
export async function seed(client, log = () => {}) {
  const sources = (await client.request("GET", "/api/admin/sources")).filter((s) => s.title === SEED_SOURCE.title);
  if (sources.length > 1) throw new Error(`${sources.length} sources titled "${SEED_SOURCE.title}" — not a seeded state; run down and up`);
  let source = sources[0];
  if (source) {
    log(`source "${SEED_SOURCE.title}" exists`);
  } else {
    const form = new FormData();
    form.set("title", SEED_SOURCE.title);
    form.set("grade", String(SEED_SOURCE.grade));
    form.set("subject", SEED_SOURCE.subject);
    form.set("file", new Blob([blankPdf(SEED_SOURCE.pages)], { type: "application/pdf" }), "e2e-seed-blank.pdf");
    source = await client.request("POST", "/api/admin/sources", form, { expect: [201] });
    log(`source "${SEED_SOURCE.title}" created`);
  }

  const summaries = await client.request("GET", "/api/admin/tasks");
  const taskIds = [];
  for (const def of SEED_TASKS) {
    const matches = [];
    for (const s of summaries.filter((t) => t.promptExcerpt.startsWith("E2E Seed"))) {
      const full = await client.request("GET", `/api/admin/tasks/${s.taskId}`);
      if (full.prompt === def.prompt) matches.push(full);
    }
    if (matches.length > 1) throw new Error(`${matches.length} tasks with prompt "${def.prompt}" — not a seeded state; run down and up`);
    let task = matches[0] ?? null;
    if (task) {
      log(`task "${def.prompt}" exists (${task.status})`);
    } else {
      task = await client.request(
        "POST",
        "/api/admin/tasks",
        {
          prompt: def.prompt,
          topicId: SEED_TOPIC_ID,
          grade: SEED_SOURCE.grade,
          difficulty: 1,
          modality: "TEXT",
          sourceRef: { sourceId: source.sourceId, page: 1, taskNo: def.taskNo },
          parts: def.parts,
          hints: def.hints,
        },
        { expect: [201] },
      );
      log(`task "${def.prompt}" created`);
    }
    if (task.status !== "PUBLISHED") {
      await client.request("PUT", `/api/admin/tasks/${task.taskId}/status`, { status: "PUBLISHED" });
      log(`task "${def.prompt}" published`);
    }
    taskIds.push(task.taskId);
  }

  const missions = await client.request("GET", "/api/admin/missions");
  let mission = missions.find((m) => m.topicId === SEED_MISSION.topicId && m.position === SEED_MISSION.position);
  if (mission) {
    // Topic + position is the mission's only identity (CA-D17); refuse one that is not the seed's.
    if (JSON.stringify(mission.taskIds) !== JSON.stringify(taskIds)) {
      throw new Error(
        `the mission at topic ${SEED_MISSION.topicId} position ${SEED_MISSION.position} holds other tasks than the seed — not a seeded state; run down and up`,
      );
    }
    log(`mission "${SEED_MISSION.label}" exists (${mission.status})`);
  } else {
    mission = await client.request(
      "POST",
      "/api/admin/missions",
      { topicId: SEED_MISSION.topicId, position: SEED_MISSION.position, taskIds },
      { expect: [201] },
    );
    log(`mission "${SEED_MISSION.label}" created`);
  }
  if (mission.status !== "PUBLISHED") {
    await client.request("PUT", `/api/admin/missions/${mission.missionId}/status`, { status: "PUBLISHED" });
    log(`mission "${SEED_MISSION.label}" published`);
  }
  return { sourceId: source.sourceId, taskIds: mission.taskIds, missionId: mission.missionId };
}
