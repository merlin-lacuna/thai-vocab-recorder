// Supabase Edge Function "process"
//
// Input:  POST {"item_id": "<uuid>"}   (with the user's login token)
// Output: the updated vocab_items row (JSON object)
//
// Steps, for one vocabulary item that belongs to the caller:
//   1. Transcription: if the item has an audio clip and no Thai text yet, send the clip to
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
//   GEMINI_MODEL           default "gemini-flash-latest"

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

async function enrich(thai: string, notes: string | null): Promise<Enrichment> {
  const apiKey = Deno.env.get("GEMINI_API_KEY")!;
  const model = Deno.env.get("GEMINI_MODEL") ?? "gemini-flash-latest";

  const prompt = `You help an English speaker who learns Thai. They heard a Thai word or phrase
in daily life and recorded it. Speech recognition produced this text, which can be
misspelled: it often writes the sound instead of the dictionary spelling, for example it
drops silent letters (ุ in เหตุ, ์ karan) or a quiet final consonant.

Text: ${thai}
${notes ? `The learner's note: ${notes}\n` : ""}
Return:
- thai: the standard Thai spelling of the most likely intended word or phrase. Keep the text
  unchanged when it is already correct or when you are not sure.
- romanization: Paiboon+ style with tone marks, syllables joined with hyphens, words with spaces.
- english: a short English meaning (at most 8 words). For a phrase, a natural translation.
- entry_type: "word" for a single word (including compound words), else "phrase".
- part_of_speech: for a word, one of ${PARTS_OF_SPEECH.join(", ")}; for a phrase, null.`;

  const res = await fetch(
    `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json", "x-goog-api-key": apiKey },
      body: JSON.stringify({
        contents: [{ role: "user", parts: [{ text: prompt }] }],
        generationConfig: {
          temperature: 0.2,
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
  const body = await res.text();
  if (!res.ok) throw new Error(`Gemini ${res.status}: ${body.slice(0, 300)}`);
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
  if (!row.thai && row.audio_path && row.transcription_status !== "manual") {
    try {
      const text = await transcribe(supabase, row.audio_path);
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
      update.enrichment_error = String(e).slice(0, 300);
    }
  }

  if (Object.keys(update).length === 0) return json(row);
  const { data: saved, error: saveError } = await supabase
    .from("vocab_items").update(update).eq("id", itemId).select("*").single();
  if (saveError) return json({ error: saveError.message }, 500);
  return json(saved);
});
