// Supabase Edge Function "vocab-mcp": an MCP server (Streamable HTTP transport) that lets
// AI chat apps (Claude, ChatGPT, Gemini) save Thai vocabulary into vocab_items.
//
// Address, one per chat app:
//   https://<project>.supabase.co/functions/v1/vocab-mcp/<MCP_TOKEN>/<client>
//   <client> = claude | chatgpt | gemini
// The secret code in the address is the access control: keep the address private. The
// <client> part selects the context label (Claude / ChatGPT / Gemini) of new words.
//
// Deploy with JWT verification off (chat apps send no Supabase login):
//   supabase functions deploy vocab-mcp --no-verify-jwt
//
// Secrets (Supabase dashboard -> Edge Functions -> Secrets):
//   MCP_TOKEN        the secret code in the address
//   VOCAB_USER_ID    the auth user whose vocabulary this server writes
// Provided by Supabase: SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY.
//
// The server uses the service-role key, which ignores Row Level Security, so every query
// below filters on VOCAB_USER_ID itself.
//
// New words get source = 'chat_mcp'. The chat model is asked to send the romanization
// (strict Paiboon+ rules, in the tool description), the English meaning, the entry type and
// the part of speech itself. A word with romanization and English is saved as it is, with
// enrichment_status = 'enriched'. For a word without them, the server calls the "enrich"
// step of the "process" function in the background, and keeps the chat's English (if any)
// in written_english as a hint.

import { createClient } from "npm:@supabase/supabase-js@2";
import { PARTS_OF_SPEECH, ROMANIZATION_RULES } from "../_shared/romanization.ts";

const SERVER_INFO = { name: "thai-vocab", version: "1.0.0" };
const PROTOCOL_VERSIONS = ["2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05"];
const SOURCE = "chat_mcp";

/** Context label per chat app (key in vocab_items.context / capture_contexts). */
const CLIENTS: Record<string, { key: string; label: string }> = {
  claude: { key: "chat_claude", label: "Claude" },
  chatgpt: { key: "chat_chatgpt", label: "ChatGPT" },
  gemini: { key: "chat_gemini", label: "Gemini" },
};

const INSTRUCTIONS = `This server saves Thai vocabulary into the user's personal vocabulary
database, which their phone and tablet apps use for review and drills.
Use add_vocab when the user asks to save, note or remember Thai words or phrases from the
conversation. Save the Thai script (not only romanization). Filling in the romanization,
English meaning, entry type and part of speech is your job: follow the Paiboon+ rules in
the add_vocab description exactly. Before adding several words, use find_vocab to avoid
duplicates if unsure.`;

// ---------------------------------------------------------------------------------------
// Tools

const TOOLS = [
  {
    name: "add_vocab",
    title: "Save Thai vocabulary",
    description:
      "Save one or more Thai words or short phrases from this conversation into the user's " +
      "vocabulary database. Use Thai script in standard dictionary spelling. Words that are " +
      "already saved are skipped unless allow_duplicates is true.\n\n" +
      "YOUR JOB: fill in romanization, english, entry_type and part_of_speech for every word. " +
      "The database does not check them; the user drills with them. Romanize exactly by these " +
      "rules:\n\n" + ROMANIZATION_RULES,
    inputSchema: {
      type: "object",
      properties: {
        items: {
          type: "array",
          minItems: 1,
          maxItems: 50,
          items: {
            type: "object",
            properties: {
              thai: { type: "string", description: "The word or phrase in Thai script." },
              romanization: { type: "string", description: "Paiboon+ romanization, exactly by the rules above." },
              english: { type: "string", description: "Short English meaning (at most 8 words); for a phrase, a natural translation." },
              entry_type: { type: "string", enum: ["word", "phrase"], description: "\"word\" for one word (compound words too), else \"phrase\"." },
              part_of_speech: {
                type: "string",
                enum: PARTS_OF_SPEECH,
                description: "For a word only; leave out for a phrase.",
              },
              note: {
                type: "string",
                description: "Optional context: an example sentence, usage, or why the user wants it.",
              },
            },
            required: ["thai", "romanization", "english", "entry_type"],
            additionalProperties: false,
          },
        },
        allow_duplicates: { type: "boolean", description: "Save even if the same Thai text already exists." },
      },
      required: ["items"],
      additionalProperties: false,
    },
    annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: false },
  },
  {
    name: "find_vocab",
    title: "Find saved vocabulary",
    description:
      "Search the user's saved vocabulary (all sources: phone, tablet, chats) by Thai text, " +
      "English meaning or romanization. Without a query, returns the most recent words.",
    inputSchema: {
      type: "object",
      properties: {
        query: { type: "string", description: "Text to search for. Leave empty for the newest words." },
        limit: { type: "integer", minimum: 1, maximum: 50, description: "Maximum results, default 20." },
      },
      additionalProperties: false,
    },
    annotations: { readOnlyHint: true, openWorldHint: false },
  },
  {
    name: "update_vocab",
    title: "Correct saved vocabulary",
    description:
      "Correct a word that was saved from a chat (source chat_mcp): its Thai text, " +
      "romanization, English meaning or note. Use the id from add_vocab or find_vocab. When " +
      "you change the Thai text, also send the new romanization and English.",
    inputSchema: {
      type: "object",
      properties: {
        id: { type: "string", description: "The id of the word." },
        thai: { type: "string" },
        romanization: { type: "string", description: "Paiboon+ romanization (rules in add_vocab)." },
        english: { type: "string" },
        note: { type: "string" },
      },
      required: ["id"],
      additionalProperties: false,
    },
    annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: false },
  },
  {
    name: "delete_vocab",
    title: "Delete saved vocabulary",
    description:
      "Delete a word that was saved from a chat (source chat_mcp). Words from the phone or " +
      "tablet cannot be deleted here. The word disappears from the apps at their next sync.",
    inputSchema: {
      type: "object",
      properties: { id: { type: "string", description: "The id of the word." } },
      required: ["id"],
      additionalProperties: false,
    },
    annotations: { readOnlyHint: false, destructiveHint: true, idempotentHint: true, openWorldHint: false },
  },
];

// ---------------------------------------------------------------------------------------
// Database

// deno-lint-ignore no-explicit-any
type Row = Record<string, any>;

const USER_ID = Deno.env.get("VOCAB_USER_ID") ?? "";
const db = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
  auth: { persistSession: false },
});

const PUBLIC_COLUMNS =
  "id, thai, romanization, english, written_english, notes, context, teacher, source, " +
  "enrichment_status, captured_at";

function publicRow(r: Row) {
  return {
    id: r.id,
    thai: r.thai,
    romanization: r.romanization,
    english: r.english,
    chat_meaning: r.written_english,
    note: r.notes,
    context: r.context,
    source: r.source,
    saved_at: r.captured_at,
  };
}

/** Normalizes Thai text for duplicate checks: trims, removes zero-width spaces, NFC. */
function norm(s: string): string {
  return s.replace(/[​‌‍﻿]/g, "").trim().normalize("NFC");
}

/** Creates the label of the chat app if the user does not have it yet (keeps user edits). */
async function ensureLabel(client: { key: string; label: string }) {
  const { data } = await db.from("capture_contexts").select("key")
    .eq("user_id", USER_ID).eq("key", client.key).maybeSingle();
  if (data) return;
  await db.from("capture_contexts").insert({
    user_id: USER_ID, key: client.key, label: client.label, sort_order: 200,
  });
}

/** Runs the "enrich" step of the "process" function for the given rows, in the background. */
function enrichLater(ids: string[]) {
  const url = `${Deno.env.get("SUPABASE_URL")}/functions/v1/process`;
  const key = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
  const work = (async () => {
    for (const id of ids) {
      let problem: string | null = null;
      try {
        const res = await fetch(url, {
          method: "POST",
          headers: { "Content-Type": "application/json", Authorization: `Bearer ${key}`, apikey: key },
          body: JSON.stringify({ item_id: id, step: "enrich" }),
          signal: AbortSignal.timeout(45_000),
        });
        const body = await res.text();
        if (!res.ok) problem = `enrich call: HTTP ${res.status} ${body.slice(0, 200)}`;
      } catch (e) {
        problem = `enrich call: ${e}`;
      }
      if (problem) {
        // Saved in the row for diagnosis. The phone and tablet apps enrich rows with status
        // "new" at their next sync, but they skip rows with an error, so this is cleared below
        // only when the problem is a timeout or a network error.
        console.warn(`${id}: ${problem}`);
        const temporary = /timeout|network|connect/i.test(problem);
        if (!temporary) {
          await db.from("vocab_items").update({ enrichment_error: problem.slice(0, 300) })
            .eq("user_id", USER_ID).eq("id", id).eq("enrichment_status", "new");
        }
      }
    }
  })();
  // deno-lint-ignore no-explicit-any
  const runtime = (globalThis as any).EdgeRuntime;
  if (runtime?.waitUntil) runtime.waitUntil(work);
}

async function addVocab(args: Row, client: { key: string; label: string }) {
  const items: Row[] = Array.isArray(args.items) ? args.items.slice(0, 50) : [];
  if (items.length === 0) throw new ToolError("items must contain at least one word");
  await ensureLabel(client);

  const wanted: Row[] = items.map((i): Row => ({ ...i, thai: norm(String(i.thai ?? "")) })).filter((i) => i.thai);
  const existing = new Map<string, Row>();
  if (!args.allow_duplicates) {
    const { data, error } = await db.from("vocab_items").select(PUBLIC_COLUMNS)
      .eq("user_id", USER_ID).is("deleted_at", null).in("thai", wanted.map((i) => i.thai));
    if (error) throw new Error(error.message);
    for (const r of (data ?? []) as Row[]) existing.set(norm(r.thai), r);
  }

  const now = new Date().toISOString();
  const seen = new Set<string>();
  const rows: Row[] = [];
  const skipped: Row[] = [];
  for (const i of wanted) {
    if (existing.has(i.thai)) {
      skipped.push({ thai: i.thai, reason: "already saved", existing: publicRow(existing.get(i.thai)!) });
      continue;
    }
    if (!args.allow_duplicates && seen.has(i.thai)) continue;
    seen.add(i.thai);
    const english = i.english ? String(i.english).trim().slice(0, 300) : null;
    const romanization = i.romanization ? String(i.romanization).trim().slice(0, 300) : null;
    // The chat did the enrichment: save it as it is. Otherwise the server's enrich step runs.
    const complete = Boolean(english && romanization);
    const entryType = ["word", "phrase"].includes(i.entry_type) ? i.entry_type : null;
    const pos = entryType !== "phrase" && PARTS_OF_SPEECH.includes(i.part_of_speech) ? i.part_of_speech : null;
    rows.push({
      id: crypto.randomUUID(),
      user_id: USER_ID,
      thai: i.thai,
      ...(complete
        ? { romanization, english, entry_type: entryType, part_of_speech: pos, enrichment_status: "enriched" }
        : { written_english: english, enrichment_status: "new" }),
      notes: i.note ? String(i.note).trim().slice(0, 1000) : null,
      context: client.key,
      source: SOURCE,
      transcription_status: "manual",
      captured_at: now,
    });
  }

  let saved: Row[] = [];
  if (rows.length > 0) {
    const { data, error } = await db.from("vocab_items").insert(rows).select(PUBLIC_COLUMNS);
    if (error) throw new Error(error.message);
    saved = data ?? [];
    const missing = saved.filter((r) => r.enrichment_status === "new").map((r) => r.id);
    if (missing.length) enrichLater(missing);
  }
  return {
    saved: saved.map(publicRow),
    skipped,
    message: `${saved.length} saved, ${skipped.length} skipped.` +
      (saved.some((r) => r.enrichment_status === "new")
        ? " Words without romanization or English get them from the database in a few seconds."
        : ""),
  };
}

async function findVocab(args: Row) {
  const limit = Math.min(Math.max(Number(args.limit) || 20, 1), 50);
  let q = db.from("vocab_items").select(PUBLIC_COLUMNS)
    .eq("user_id", USER_ID).is("deleted_at", null)
    .order("captured_at", { ascending: false }).limit(limit);
  const text = norm(String(args.query ?? ""));
  if (text) {
    // Characters with a meaning in PostgREST filters are replaced by the "any character" wildcard.
    const t = text.replace(/[%,()*\\]/g, "_");
    q = q.or(`thai.ilike.*${t}*,english.ilike.*${t}*,written_english.ilike.*${t}*,romanization.ilike.*${t}*`);
  }
  const { data, error } = await q;
  if (error) throw new Error(error.message);
  return { results: (data ?? []).map(publicRow), count: data?.length ?? 0 };
}

async function chatRow(id: string): Promise<Row> {
  if (!/^[0-9a-f-]{36}$/i.test(id ?? "")) throw new ToolError("id is not a valid word id");
  const { data, error } = await db.from("vocab_items").select("id, source, enrichment_status, deleted_at")
    .eq("user_id", USER_ID).eq("id", id).maybeSingle();
  if (error) throw new Error(error.message);
  if (!data || data.deleted_at) throw new ToolError("No saved word with this id");
  if (data.source !== SOURCE) {
    throw new ToolError("This word was not saved from a chat. Edit it in the phone or tablet app.");
  }
  return data;
}

async function updateVocab(args: Row) {
  const row = await chatRow(args.id);
  const update: Row = {};
  const roman = typeof args.romanization === "string" ? args.romanization.trim().slice(0, 300) : "";
  const english = typeof args.english === "string" ? args.english.trim().slice(0, 300) : "";
  if (typeof args.thai === "string" && norm(args.thai)) {
    update.thai = norm(args.thai);
    // New Thai text without new values from the chat: the server's enrich step fills them.
    if (row.enrichment_status !== "reviewed" && !(roman && english)) {
      Object.assign(update, { romanization: null, english: null, enrichment_status: "new", enrichment_error: null });
    }
  }
  if (roman) update.romanization = roman;
  if (english) update.english = english;
  if (roman && english && update.enrichment_status === "new") update.enrichment_status = "enriched";
  if (typeof args.note === "string") update.notes = args.note.trim().slice(0, 1000) || null;
  if (Object.keys(update).length === 0) throw new ToolError("Nothing to change: give thai, romanization, english or note");
  const { data, error } = await db.from("vocab_items").update(update)
    .eq("user_id", USER_ID).eq("id", args.id).select(PUBLIC_COLUMNS).single();
  if (error) throw new Error(error.message);
  if (update.enrichment_status === "new") enrichLater([args.id]);
  return { updated: publicRow(data) };
}

async function deleteVocab(args: Row) {
  await chatRow(args.id);
  // Soft delete, like the apps: the row stays with deleted_at, and the apps remove it at sync.
  const { error } = await db.from("vocab_items").update({ deleted_at: new Date().toISOString() })
    .eq("user_id", USER_ID).eq("id", args.id);
  if (error) throw new Error(error.message);
  return { deleted: args.id };
}

// ---------------------------------------------------------------------------------------
// MCP over HTTP (JSON-RPC 2.0; the server answers each POST with one JSON response)

class ToolError extends Error {}

type Rpc = { jsonrpc: "2.0"; id?: string | number | null; method?: string; params?: Row };

function rpcResult(id: Rpc["id"], result: unknown) {
  return { jsonrpc: "2.0", id, result };
}

function rpcError(id: Rpc["id"], code: number, message: string) {
  return { jsonrpc: "2.0", id: id ?? null, error: { code, message } };
}

async function callTool(name: string, args: Row, client: { key: string; label: string }) {
  try {
    const result = name === "add_vocab" ? await addVocab(args, client)
      : name === "find_vocab" ? await findVocab(args)
      : name === "update_vocab" ? await updateVocab(args)
      : name === "delete_vocab" ? await deleteVocab(args)
      : null;
    if (result === null) return null;
    return { content: [{ type: "text", text: JSON.stringify(result, null, 1) }], structuredContent: result };
  } catch (e) {
    // Errors the model can act on are returned as tool results, so the model sees them.
    const text = e instanceof ToolError ? e.message : `Server error: ${e instanceof Error ? e.message : e}`;
    if (!(e instanceof ToolError)) console.error(`${name}: ${e}`);
    return { content: [{ type: "text", text }], isError: true };
  }
}

async function handle(msg: Rpc, client: { key: string; label: string }) {
  const isRequest = msg.id !== undefined && msg.id !== null;
  switch (msg.method) {
    case "initialize": {
      const asked = msg.params?.protocolVersion;
      const version = PROTOCOL_VERSIONS.includes(asked) ? asked : PROTOCOL_VERSIONS[0];
      return rpcResult(msg.id, {
        protocolVersion: version,
        capabilities: { tools: { listChanged: false } },
        serverInfo: SERVER_INFO,
        instructions: INSTRUCTIONS,
      });
    }
    case "ping":
      return rpcResult(msg.id, {});
    case "tools/list":
      return rpcResult(msg.id, { tools: TOOLS });
    case "tools/call": {
      const result = await callTool(String(msg.params?.name), msg.params?.arguments ?? {}, client);
      if (result === null) return rpcError(msg.id, -32602, `Unknown tool: ${msg.params?.name}`);
      return rpcResult(msg.id, result);
    }
    case "resources/list":
      return rpcResult(msg.id, { resources: [] });
    case "prompts/list":
      return rpcResult(msg.id, { prompts: [] });
    default:
      // Notifications (no id), e.g. "notifications/initialized", need no answer.
      if (!isRequest) return null;
      return rpcError(msg.id, -32601, `Method not found: ${msg.method}`);
  }
}

/** Constant-time comparison, so the response time does not reveal the secret code. */
function sameSecret(a: string, b: string): boolean {
  const x = new TextEncoder().encode(a);
  const y = new TextEncoder().encode(b);
  let diff = x.length ^ y.length;
  for (let i = 0; i < Math.max(x.length, y.length); i++) diff |= (x[i] ?? 0) ^ (y[i] ?? 0);
  return diff === 0;
}

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "POST, GET, DELETE, OPTIONS",
  "Access-Control-Allow-Headers":
    "Content-Type, Accept, Authorization, Mcp-Session-Id, Mcp-Protocol-Version, Last-Event-ID",
};

function reply(body: unknown, status = 200): Response {
  return new Response(body === null ? null : JSON.stringify(body), {
    status,
    headers: { ...CORS, "Content-Type": "application/json" },
  });
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });

  // Path: .../vocab-mcp/<token>/<client>
  const parts = new URL(req.url).pathname.split("/").filter(Boolean);
  const at = parts.indexOf("vocab-mcp");
  const [token, clientName] = at >= 0 ? parts.slice(at + 1, at + 3) : [];
  const secret = Deno.env.get("MCP_TOKEN") ?? "";
  if (!secret || !USER_ID || !token || !sameSecret(token, secret)) {
    return reply({ error: "Not found" }, 404);
  }
  const client = CLIENTS[(clientName ?? "").toLowerCase()];
  if (!client) return reply({ error: "Unknown client; use /claude, /chatgpt or /gemini" }, 404);

  // This server sends no messages of its own, so there is no event stream to open.
  if (req.method === "GET") return new Response(null, { status: 405, headers: { ...CORS, Allow: "POST" } });
  if (req.method === "DELETE") return new Response(null, { status: 204, headers: CORS });
  if (req.method !== "POST") return new Response(null, { status: 405, headers: CORS });

  let body: Rpc | Rpc[];
  try {
    body = await req.json();
  } catch {
    return reply(rpcError(null, -32700, "Parse error"), 400);
  }
  if (Array.isArray(body)) {
    const answers = (await Promise.all(body.map((m) => handle(m, client)))).filter((a) => a !== null);
    return answers.length ? reply(answers) : new Response(null, { status: 202, headers: CORS });
  }
  const answer = await handle(body, client);
  return answer === null ? new Response(null, { status: 202, headers: CORS }) : reply(answer);
});
