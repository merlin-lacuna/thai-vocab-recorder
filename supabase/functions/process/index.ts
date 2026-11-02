// Supabase Edge Function "process"
//
// Input:  POST {"item_id": "<uuid>"}   (with the user's login token)
// Output: the updated vocab_items row (JSON object)
//
// Steps, for one vocabulary item that belongs to the caller:
//   1. Transcription: if the item has an audio clip (the learner's own, or else a native
//      speaker's recording made with the app's "Native speaker" switch) and no Thai text yet, send the clip to
//      ElevenLabs speech-to-text (Thai) and save the text.
//   2. Enrichment: if the item has Thai text and is not marked "reviewed", ask Gemini for
//      the standard spelling, romanization, English meaning, word/phrase and part of speech.
//      A corrected spelling replaces `thai`; the original transcript is kept in
//      `thai_alternatives`.
// The function works with the caller's own permissions (Row Level Security).
//
// Secrets (Supabase dashboard -> Edge Functions -> Secrets):
//   ELEVENLABS_API_KEY     required for step 1
//   GEMINI_API_KEY         required for step 2 (step 2 is skipped without it)
// Optional:
//   ELEVENLABS_STT_MODEL   default "scribe_v1"
//   GEMINI_MODELS          comma-separated models to try in order, default
//                          "gemini-3.8-flash,gemini-flash-latest,gemini-flash-lite-latest"

import { createClient, SupabaseClient } from "npm:@supabase/supabase-js@2";

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

/**
 * Problem with one model (busy, rate limit, timeout, model retired): try the next model.
 * If all models fail this way, try again at the next sync and save no error.
 */
class TemporaryError extends Error {}
class BadRequestError extends Error {}

const GEMINI_MODELS = (Deno.env.get("GEMINI_MODELS") ??
  "gemini-3.8-flash,gemini-flash-latest,gemini-flash-lite-latest").split(",").map((m) => m.trim());

/** Tries each model in turn while the error is temporary. */
/** Paiboon+ romanization, written out in full so the model has no room for its own style. */
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

async function enrich(thai: string, notes: string | null): Promise<Enrichment> {
  let last: unknown;
  for (const model of GEMINI_MODELS) {
    try {
      try {
        return await enrichWith(model, thai, notes, true);
      } catch (e) {
        // Some models reject the "thinking" setting: try once more without it.
        if (e instanceof BadRequestError) return await enrichWith(model, thai, notes, false);
        throw e;
      }
    } catch (e) {
      last = e;
      if (!(e instanceof TemporaryError)) throw e;
    }
  }
  throw last;
}

async function enrichWith(
  model: string, thai: string, notes: string | null, noThinking: boolean,
): Promise<Enrichment> {
  const apiKey = Deno.env.get("GEMINI_API_KEY")!;

  const prompt = `You help an English speaker who learns Thai. They heard a Thai word or phrase
in daily life and recorded it. Speech recognition produced this text, which can be
misspelled: it often writes the sound instead of the dictionary spelling, for example it
drops silent letters (ุ in เหตุ, ์ karan) or a quiet final consonant.

Text: ${thai}
${notes ? `The learner's note: ${notes}\n` : ""}
Return:
- thai: the standard Thai spelling of the most likely intended word or phrase. Keep the text
  unchanged when it is already correct or when you are not sure.
- romanization: strictly the Paiboon+ system described below.
- english: a short English meaning (at most 8 words). For a phrase, a natural translation.
- entry_type: "word" for a single word (including compound words), else "phrase".
- part_of_speech: for a word, one of ${PARTS_OF_SPEECH.join(", ")}; for a phrase, null.

${ROMANIZATION_RULES}`;

  let res: Response;
  try {
  res = await fetch(
    `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`,
    {
      method: "POST",
      signal: AbortSignal.timeout(15_000),
      headers: { "Content-Type": "application/json", "x-goog-api-key": apiKey },
      body: JSON.stringify({
        contents: [{ role: "user", parts: [{ text: prompt }] }],
        generationConfig: {
          temperature: 0.2,
          // A short dictionary-style answer needs no "thinking"; this keeps it fast.
          ...(noThinking ? { thinkingConfig: { thinkingBudget: 0 } } : {}),
          responseMimeType: "application/json",
          responseSchema: {
            type: "OBJECT",
            properties: {
              thai: { type: "STRING" },
              romanization: { type: "STRING" },
              english: { type: "STRING" },
              entry_type: { type: "STRING", enum: ["word", "phrase"] },
              part_of_speech: { type: "STRING", enum: PARTS_OF_SPEECH, nullable: true },
            },
            required: ["thai", "romanization", "english", "entry_type"],
          },
        },
      }),
    },
  );
  } catch (e) {
    throw new TemporaryError(`Gemini ${model}: ${e}`); // timeout or network
  }
  const body = await res.text();
  if (res.status === 400 && noThinking) {
    throw new BadRequestError(`Gemini ${model} 400: ${body.slice(0, 200)}`);
  }
  if (res.status === 429 || res.status === 404 || res.status >= 500) {
    throw new TemporaryError(`Gemini ${model} ${res.status}: ${body.slice(0, 200)}`);
  }
  if (!res.ok) throw new Error(`Gemini ${model} ${res.status}: ${body.slice(0, 300)}`);
  const text = JSON.parse(body).candidates?.[0]?.content?.parts?.[0]?.text;
  if (!text) throw new Error(`Gemini returned no result: ${body.slice(0, 300)}`);
  const result = JSON.parse(text) as Enrichment;
  if (result.entry_type === "phrase" || !PARTS_OF_SPEECH.includes(result.part_of_speech ?? "")) {
    result.part_of_speech = null;
  }
  return result;
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "Use POST" }, 405);
  const auth = req.headers.get("Authorization");
  if (!auth) return json({ error: "Not signed in" }, 401);

  let itemId: string;
  try {
    itemId = (await req.json()).item_id;
  } catch {
    return json({ error: "Body must be JSON" }, 400);
  }
  if (!itemId) return json({ error: "item_id is missing" }, 400);

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

  // 1. Transcription
  const clip: string | null = row.audio_path ?? row.native_audio_path;
  if (!row.thai && clip && row.transcription_status !== "manual") {
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

  // 2. Enrichment
  const thai: string | null = update.thai ?? row.thai;
  if (thai && row.enrichment_status !== "reviewed" && Deno.env.get("GEMINI_API_KEY")) {
    try {
      const r = await enrich(thai, row.notes);
      const corrected = r.thai.trim() || thai;
      const alternatives: string[] = [...(row.thai_alternatives ?? [])];
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
