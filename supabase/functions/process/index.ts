// Supabase Edge Function "process"
//
// Input:  POST {"item_id": "<uuid>", "step": "transcribe" | "read" | "enrich" | "all"}
//         (with the user's login token; "step" defaults to "all")
// Output: the updated vocab_items row (JSON object)
//
// The app calls the two steps separately, so the Thai text appears on the phone after
// the first step, and romanization and English follow after the second.
//
//   "transcribe": if the item has an audio clip (the learner's own, or else a native
//      speaker's recording made with the app's "Native speaker" switch) and no Thai text,
//      send the clip to ElevenLabs speech-to-text (Thai) and save the text.
//   "read": if the item has a handwriting image from the tablet app, send the image(s) to
//      Claude, which reads the Thai script or the phonetic Latin letters (`written_script`).
//   "enrich": if the item has Thai text and is not marked "reviewed", ask Claude for the
//      standard spelling, Paiboon+ romanization, English meaning, word/phrase and part of
//      speech. A corrected spelling replaces `thai`; the original stays in
//      `thai_alternatives`.
// The function works with the caller's own permissions (Row Level Security).
//
// Secrets (Supabase dashboard -> Edge Functions -> Secrets):
//   ELEVENLABS_API_KEY     required for "transcribe"
//   ANTHROPIC_API_KEY      required for "read" and "enrich" ("enrich" is skipped without it)
// Optional:
//   ELEVENLABS_STT_MODEL   default "scribe_v1"
//   CLAUDE_MODEL           default "claude-sonnet-5-5"
//   CLAUDE_EFFORT          default "low" (short dictionary-style answer, fast)

import { createClient, SupabaseClient } from "npm:@supabase/supabase-js@2";
import Anthropic from "npm:@anthropic-ai/sdk";
import { encodeBase64 } from "jsr:@std/encoding@1/base64";

const PARTS_OF_SPEECH = [
  "noun", "verb", "adjective", "adverb", "preposition", "article",
  "pronoun", "classifier", "particle", "conjunction", "number", "other",
];

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

// deno-lint-ignore no-explicit-any
type Row = Record<string, any>;

async function transcribe(supabase: SupabaseClient, audioPath: string): Promise<string> {
  const apiKey = Deno.env.get("ELEVENLABS_API_KEY");
  if (!apiKey) throw new Error("ELEVENLABS_API_KEY is not set in Supabase secrets");

  const { data: audio, error } = await supabase.storage.from("vocab-audio").download(audioPath);
  if (error || !audio) throw new Error(`Cannot read audio: ${error?.message ?? "not found"}`);

  const form = new FormData();
  form.append("model_id", Deno.env.get("ELEVENLABS_STT_MODEL") ?? "scribe_v1");
  form.append("language_code", "tha");
  form.append("tag_audio_events", "false");
  form.append("file", audio, "clip.wav");

  const res = await fetch("https://api.elevenlabs.io/v1/speech-to-text", {
    method: "POST",
    headers: { "xi-api-key": apiKey },
    body: form,
    signal: AbortSignal.timeout(45_000),
  });
  const body = await res.text();
  if (!res.ok) throw new Error(`ElevenLabs ${res.status}: ${body.slice(0, 300)}`);
  return (JSON.parse(body).text ?? "").trim();
}


interface Enrichment {
  thai: string;
  romanization: string;
  english: string;
  entry_type: "word" | "phrase";
  part_of_speech: string | null;
}

/** Temporary problem (rate limit, server error, timeout, network): retry at the next sync. */
class TemporaryError extends Error {}

const ROMANIZATION_RULES = `Paiboon+ romanization rules (follow exactly):
Initial consonants:
  ก g | ข ฃ ค ฅ ฆ k | ง ng | จ j | ฉ ช ฌ ch | ซ ศ ษ ส s | ญ ย y | ฎ ด d | ฏ ต dt
  ฐ ฑ ฒ ถ ท ธ t | ณ น n | บ b | ป bp | ผ พ ภ p | ฝ ฟ f | ม m | ร r | ล ฬ l | ว w
  ห ฮ h | อ (no letter). Clusters: กร gr, กล gl, กว gw, คร kr, คล kl, คว kw, ปร bpr,
  ปล bpl, พร pr, พล pl, ตร dtr. Leading ห or อ (หน, หม, อย ...) only changes the tone.
Final consonants:
  k (ก ข ค ฆ) | ng (ง) | t (จ ช ซ ด ต ถ ท ธ ศ ษ ส ฎ ฏ ฐ ฑ ฒ) | n (น ณ ญ ร ล ฬ)
  p (บ ป พ ฟ ภ) | m (ม) | final ว = o (แมว mɛɛo, เสียว sǐao) | final ย = i (ขาย kǎai).
Vowels (short / long):
  a / aa | i / ii | ʉ / ʉʉ (อึ / อือ) | u / uu | e / ee | ɛ / ɛɛ (แ) | o / oo
  ɔ / ɔɔ (เอาะ / ออ) | ə / əə (เออ) | ia / iia (เอีย = ia) | ʉa (เอือ) | ua (อัว)
  ai (ไ ใ อัย) | aai (อาย) | ao (เอา) | aao (อาว) | am (อำ) | ɔɔi (ออย) | ooi (โอย) | uai (อวย)
  Silent letters (ุ in เหตุ, ิ in ญาติ, letters under ์) are not written.
Tones (mark on the first vowel letter of the syllable):
  mid: no mark (maa) | low: grave (màa) | falling: circumflex (mâa) | high: acute (máa)
  rising: caron (mǎa). Use the real spoken tone of each syllable.
Format: lowercase; syllables of one word joined with "-"; words separated by a space.
Examples:
  อุบัติเหตุ ù-bàt-dtì-hèet | ปลา bplaa | ตลาด dtà-làat | จั๊กจี้ ják-jîi | เสียว sǐao
  ขอบคุณครับ kɔ̀ɔp-kun kráp | แม่น้ำ mɛ̂ɛ-náam | เธอ təə | มือถือ mʉʉ-tʉ̌ʉ | เด็ก dèk
  สวัสดี sà-wàt-dii | กินข้าว gin kâao | ไม่เป็นไร mâi bpen rai`;


const ENRICHMENT_SCHEMA = {
  type: "object",
  properties: {
    thai: { type: "string" },
    romanization: { type: "string" },
    english: { type: "string" },
    entry_type: { type: "string", enum: ["word", "phrase"] },
    part_of_speech: { anyOf: [{ type: "string", enum: PARTS_OF_SPEECH }, { type: "null" }] },
  },
  required: ["thai", "romanization", "english", "entry_type", "part_of_speech"],
  additionalProperties: false,
};

let anthropic: Anthropic | null = null;

// deno-lint-ignore no-explicit-any
async function askClaude(content: any, schema: unknown): Promise<any> {
  // Reads ANTHROPIC_API_KEY from the environment. One retry inside the SDK, then the
  // next sync tries again; 30 s limit so the device (60 s limit) always gets an answer.
  anthropic ??= new Anthropic({ timeout: 30_000, maxRetries: 1 });
  let response;
  try {
    response = await anthropic.beta.messages.create({
      model: Deno.env.get("CLAUDE_MODEL") ?? "claude-sonnet-5-5",
      max_tokens: 4000,
      // If a safety classifier declines, the API re-runs the request on a fallback model.
      betas: ["server-side-fallback-2026-07-01"],
      fallbacks: "default",
      output_config: {
        effort: (Deno.env.get("CLAUDE_EFFORT") ?? "low") as "low" | "medium" | "high" | "xhigh" | "max",
        // deno-lint-ignore no-explicit-any
        format: { type: "json_schema", schema: schema as any },
      },
      messages: [{ role: "user", content }],
    });
  } catch (e) {
    if (
      e instanceof Anthropic.RateLimitError ||
      e instanceof Anthropic.InternalServerError ||
      e instanceof Anthropic.APIConnectionError // includes timeouts
    ) {
      throw new TemporaryError(`Claude: ${e.message}`);
    }
    throw e; // 400/401/403/404: permanent, saved as an error in the row
  }
  if (response.stop_reason === "refusal") throw new Error("Claude declined the request");
  if (response.stop_reason === "max_tokens") throw new Error("Claude answer was cut off");
  let text = "";
  for (const block of response.content) {
    if (block.type === "text") text += block.text;
  }
  if (!text) throw new Error("Claude returned no text");
  return JSON.parse(text);
}

async function enrich(
  thai: string, notes: string | null, handwriting: boolean, writtenEnglish: string | null,
): Promise<Enrichment> {
  const origin = handwriting
    ? `You help an English speaker who learns Thai. They wrote a Thai word or phrase by hand
during a lesson, and a model read the handwriting. The text can contain a wrong letter where
two Thai letters look alike in handwriting (for example ด and ค, ถ and ภ, บ and ป).`
    : `You help an English speaker who learns Thai. They heard a Thai word or phrase
and recorded it, often in their own (learner) pronunciation. Speech recognition produced this
text, which can be misspelled: it often writes the sound instead of the dictionary spelling,
for example it drops silent letters (ุ in เหตุ, ์ karan) or a quiet final consonant, or it
writes a different word when a tone or vowel length was pronounced wrongly.`;

  const prompt = `${origin}

Text: ${thai}
${notes ? `The learner's note: ${notes}\n` : ""}${writtenEnglish ? `The learner's own translation: ${writtenEnglish}\n` : ""}
Return:
- thai: the standard Thai spelling of the most likely intended word or phrase. Keep the text
  unchanged when it is already correct or when you are not sure.
- romanization: strictly the Paiboon+ system described below.
- english: a short English meaning (at most 8 words). For a phrase, a natural translation.
- entry_type: "word" for a single word (including compound words), else "phrase".
- part_of_speech: for a word, one of ${PARTS_OF_SPEECH.join(", ")}; for a phrase, null.

${ROMANIZATION_RULES}`;

  const result = await askClaude(prompt, ENRICHMENT_SCHEMA) as Enrichment;
  if (result.entry_type === "phrase") result.part_of_speech = null;
  return result;
}

interface Reading {
  written_text: string;
  thai: string;
  thai_alternatives: string[];
  written_english: string | null;
}

const READING_SCHEMA = {
  type: "object",
  properties: {
    written_text: { type: "string" },
    thai: { type: "string" },
    thai_alternatives: { type: "array", items: { type: "string" } },
    written_english: { anyOf: [{ type: "string" }, { type: "null" }] },
  },
  required: ["written_text", "thai", "thai_alternatives", "written_english"],
  additionalProperties: false,
};

async function imageBlock(supabase: SupabaseClient, path: string) {
  const { data, error } = await supabase.storage.from("vocab-handwriting").download(path);
  if (error || !data) throw new Error(`Cannot read image: ${error?.message ?? "not found"}`);
  return {
    type: "image",
    source: { type: "base64", media_type: "image/png", data: encodeBase64(await data.arrayBuffer()) },
  };
}

/** Reads the handwriting images of one row. `written_script` is the Next button the user pressed. */
async function read(supabase: SupabaseClient, row: Row): Promise<Reading> {
  const phonetic = row.written_script === "phonetic";
  // deno-lint-ignore no-explicit-any
  const content: any[] = [
    { type: "text", text: "Image 1: the word or phrase that the learner wrote." },
    await imageBlock(supabase, row.image_path),
  ];
  if (row.english_image_path) {
    content.push({ type: "text", text: "Image 2: the learner's own English translation of it." });
    content.push(await imageBlock(supabase, row.english_image_path));
  }
  const task = phonetic
    ? `Image 1 contains Latin letters: the learner wrote a Thai word or phrase by its sound, in
their own informal spelling, because they did not see the Thai script. Tones and vowel length
are usually not marked.
Return:
- written_text: the Latin letters exactly as written.
- thai: the Thai script of the most likely intended word or phrase.
- thai_alternatives: up to four other Thai words or phrases that this spelling can mean,
  most likely first. Use an empty list when there is only one plausible answer.`
    : `Image 1 contains handwritten Thai script by a learner. Letter shapes can be imperfect.
Return:
- written_text: the Thai text exactly as you read it.
- thai: the same text as written_text.
- thai_alternatives: up to four other readings where a letter or a tone mark is unclear,
  most likely first. Use an empty list when the handwriting is clear.`;
  content.push({
    type: "text",
    text: `You help an English speaker who learns Thai. They wrote the text in the image by hand
with a pen on a tablet during a Thai lesson.

${task}
- written_english: ${row.english_image_path
      ? "the English text in image 2, exactly as written. Use it as a hint for image 1."
      : "null."}
${row.notes ? `\nThe learner's note: ${row.notes}` : ""}`,
  });
  const result = await askClaude(content, READING_SCHEMA) as Reading;
  if (!row.english_image_path) result.written_english = null;
  return result;
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "Use POST" }, 405);
  const auth = req.headers.get("Authorization");
  if (!auth) return json({ error: "Not signed in" }, 401);

  let itemId: string;
  let step: string;
  try {
    const body = await req.json();
    itemId = body.item_id;
    step = body.step ?? "all";
  } catch {
    return json({ error: "Body must be JSON" }, 400);
  }
  if (!itemId) return json({ error: "item_id is missing" }, 400);
  if (!["transcribe", "read", "enrich", "all"].includes(step)) return json({ error: "Unknown step" }, 400);

  // Acts as the signed-in user, so the security rules apply.
  const supabase = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_ANON_KEY")!,
    { global: { headers: { Authorization: auth } } },
  );

  const { data: row, error: readError } = await supabase
    .from("vocab_items").select("*").eq("id", itemId).maybeSingle();
  if (readError) return json({ error: readError.message }, 500);
  if (!row) return json({ error: "Item not found" }, 404);

  const update: Row = {};

  // Step 1: transcription
  const clip: string | null = row.audio_path ?? row.native_audio_path;
  if ((step === "transcribe" || step === "all") && !row.thai && clip && row.transcription_status !== "manual") {
    try {
      const text = await transcribe(supabase, clip);
      if (text) {
        Object.assign(update, { thai: text, transcription_status: "done", transcription_error: null });
      } else {
        Object.assign(update, { transcription_status: "failed", transcription_error: "no speech found" });
      }
    } catch (e) {
      Object.assign(update, { transcription_status: "failed", transcription_error: String(e).slice(0, 300) });
    }
  }

  // Step 1b: handwriting from the tablet. Runs for a new row, and again when the app sets
  // the status back to "pending" ("Read again as the other type").
  if (
    (step === "read" || step === "all") && row.image_path &&
    row.enrichment_status !== "reviewed" && row.transcription_status !== "manual" &&
    (!row.thai || row.transcription_status === "pending")
  ) {
    try {
      const r = await read(supabase, row);
      const thai = r.thai.trim();
      if (thai) {
        Object.assign(update, {
          thai,
          thai_alternatives: r.thai_alternatives.filter((a) => a && a !== thai).slice(0, 4),
          written_text: r.written_text,
          written_english: r.written_english,
          transcription_status: row.written_script === "phonetic" ? "guessed" : "done",
          transcription_error: null,
          // The meaning of an earlier reading no longer fits: let "enrich" run again.
          romanization: null,
          english: null,
          enrichment_status: "new",
          enrichment_error: null,
        });
      } else {
        Object.assign(update, { transcription_status: "failed", transcription_error: "no text found" });
      }
    } catch (e) {
      if (e instanceof TemporaryError) return json({ error: String(e) }, 503);
      Object.assign(update, { transcription_status: "failed", transcription_error: String(e).slice(0, 300) });
    }
  }

  // Step 2: enrichment
  const thai: string | null = update.thai ?? row.thai;
  if ((step === "enrich" || step === "all") && thai && row.enrichment_status !== "reviewed" && Deno.env.get("ANTHROPIC_API_KEY")) {
    try {
      const r = await enrich(thai, row.notes, row.source === "boox_handwriting", row.written_english ?? null);
      const corrected = r.thai.trim() || thai;
      const alternatives: string[] = [...(update.thai_alternatives ?? row.thai_alternatives ?? [])];
      if (corrected !== thai && !alternatives.includes(thai)) alternatives.unshift(thai);
      Object.assign(update, {
        thai: corrected,
        thai_alternatives: alternatives,
        romanization: r.romanization,
        english: r.english,
        entry_type: r.entry_type,
        part_of_speech: r.part_of_speech,
        enrichment_status: "enriched",
        enrichment_error: null,
      });
    } catch (e) {
      // Temporary problems are retried at the next sync; others are saved and shown.
      if (!(e instanceof TemporaryError)) update.enrichment_error = String(e).slice(0, 300);
      else console.warn(String(e));
    }
  }

  if (Object.keys(update).length === 0) return json(row);
  const { data: saved, error: saveError } = await supabase
    .from("vocab_items").update(update).eq("id", itemId).select("*").single();
  if (saveError) return json({ error: saveError.message }, 500);
  return json(saved);
});
