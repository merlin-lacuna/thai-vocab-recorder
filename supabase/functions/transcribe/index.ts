// Supabase Edge Function "transcribe"
//
// Input:  POST {"audio_path": "<user id>/<item id>.wav"}   (with the user's login token)
// Output: {"text": "<Thai text>", "language_code": "tha", "language_probability": 0.98}
//
// The function downloads the clip from the "vocab-audio" bucket with the caller's own
// permissions (so a user can only transcribe their own clips), sends it to the
// ElevenLabs speech-to-text API, and returns the text. It does not change the database;
// the app saves the text together with the rest of the entry.
//
// Needed secret (Supabase dashboard -> Edge Functions -> Secrets):
//   ELEVENLABS_API_KEY        your ElevenLabs API key
// Optional:
//   ELEVENLABS_STT_MODEL      default "scribe_v1"

import { createClient } from "npm:@supabase/supabase-js@2";

const ELEVENLABS_URL = "https://api.elevenlabs.io/v1/speech-to-text";

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "Use POST" }, 405);

  const apiKey = Deno.env.get("ELEVENLABS_API_KEY");
  if (!apiKey) return json({ error: "ELEVENLABS_API_KEY is not set in Supabase secrets" }, 500);

  const auth = req.headers.get("Authorization");
  if (!auth) return json({ error: "Not signed in" }, 401);

  let audioPath: string;
  try {
    audioPath = (await req.json()).audio_path;
  } catch {
    return json({ error: "Body must be JSON" }, 400);
  }
  if (!audioPath) return json({ error: "audio_path is missing" }, 400);

  // Client that acts as the signed-in user, so storage security rules apply.
  const supabase = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_ANON_KEY")!,
    { global: { headers: { Authorization: auth } } },
  );

  const { data: audio, error: downloadError } = await supabase.storage
    .from("vocab-audio")
    .download(audioPath);
  if (downloadError || !audio) {
    return json({ error: `Cannot read audio: ${downloadError?.message ?? "not found"}` }, 404);
  }

  const form = new FormData();
  form.append("model_id", Deno.env.get("ELEVENLABS_STT_MODEL") ?? "scribe_v1");
  form.append("language_code", "tha");
  form.append("tag_audio_events", "false");
  form.append("file", audio, "clip.wav");

  const res = await fetch(ELEVENLABS_URL, {
    method: "POST",
    headers: { "xi-api-key": apiKey },
    body: form,
  });
  const body = await res.text();
  if (!res.ok) {
    return json({ error: `ElevenLabs ${res.status}: ${body.slice(0, 300)}` }, 502);
  }

  const result = JSON.parse(body);
  return json({
    text: (result.text ?? "").trim(),
    language_code: result.language_code ?? null,
    language_probability: result.language_probability ?? null,
  });
});
