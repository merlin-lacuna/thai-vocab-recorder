// Quiz emails ("sentence quiz").
//
//   GET ?q=<quiz id>&c=<choice index>   -> JSON for the answer page; writes one quiz_events row
//   GET ?q=<quiz id>&format=email       -> the email HTML (as text; Supabase sends HTML as text/plain)
//
// The answer page itself is a static page on the user's web server (web/quiz/), because Supabase
// changes text/html to text/plain on its own domains. The quiz id is a random UUID; the
// function returns only the data of that one quiz.

import { createClient } from "npm:@supabase/supabase-js@2";

// Address of web/quiz/index.html on the user's web server (Supabase secret QUIZ_PAGE_URL).
const PAGE_URL = Deno.env.get("QUIZ_PAGE_URL") ?? "";

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, OPTIONS",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type",
};

type Option = { thai: string; romanization: string; english: string; correct: boolean };
type Quiz = {
  id: string; user_id: string; item_id: string; sentence_en: string; highlight: string;
  options: Option[]; created_at: string;
};

const db = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
  auth: { persistSession: false },
});

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status, headers: { ...CORS, "Content-Type": "application/json; charset=utf-8" },
  });
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { headers: CORS });
  if (req.method !== "GET") return json({ error: "method not allowed" }, 405);

  const url = new URL(req.url);
  const q = url.searchParams.get("q") ?? "";
  if (!UUID.test(q)) return json({ error: "quiz not found" }, 404);

  const { data: quiz } = await db.from("email_quizzes").select("*").eq("id", q).maybeSingle<Quiz>();
  if (!quiz) return json({ error: "quiz not found" }, 404);

  const { data: item } = await db.from("vocab_items")
    .select("id, thai, romanization, english, part_of_speech, native_audio_path")
    .eq("id", quiz.item_id).maybeSingle();
  if (!item) return json({ error: "word not found" }, 404);

  if (url.searchParams.get("format") === "email") {
    if (!PAGE_URL) return json({ error: "secret QUIZ_PAGE_URL is not set" }, 500);
    return new Response(emailHtml(quiz), { headers: { ...CORS, "Content-Type": "text/plain; charset=utf-8" } });
  }

  const c = Number(url.searchParams.get("c"));
  if (!Number.isInteger(c) || c < 0 || c >= quiz.options.length) return json({ error: "bad choice" }, 400);
  const chosen = quiz.options[c];

  // One row per click, so wrong tries count too.
  await db.from("quiz_events").insert({
    user_id: quiz.user_id, item_id: quiz.item_id, asked_at: quiz.created_at,
    answered_at: new Date().toISOString(), correct: chosen.correct,
    quiz_type: "email_sentence", answer: chosen.thai,
  });

  let nativeAudioUrl: string | null = null;
  if (item.native_audio_path) {
    const { data } = await db.storage.from("vocab-audio").createSignedUrl(item.native_audio_path, 60 * 60 * 24);
    nativeAudioUrl = data?.signedUrl ?? null;
  }

  return json({
    correct: chosen.correct,
    choice: c,
    sentence_en: quiz.sentence_en,
    highlight: quiz.highlight,
    word: {
      thai: item.thai, romanization: item.romanization, english: item.english,
      part_of_speech: item.part_of_speech,
    },
    native_audio_url: nativeAudioUrl,
    // The page shows the meaning of every choice after the right answer, and the
    // meaning of the clicked choice after a wrong one.
    options: quiz.options,
  });
});

// ---------------------------------------------------------------------------------------
// Email HTML: tables and inline styles only, because email programs ignore most CSS.

function esc(s: string) {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

function highlighted(sentence: string, word: string) {
  const i = sentence.toLowerCase().indexOf(word.toLowerCase());
  if (i < 0) return esc(sentence);
  return esc(sentence.slice(0, i)) +
    `<span style="background:#FFE27A;color:#0B2545;padding:0 6px;border-radius:6px;font-weight:bold;">` +
    esc(sentence.slice(i, i + word.length)) + `</span>` + esc(sentence.slice(i + word.length));
}

function emailHtml(quiz: Quiz) {
  const buttons = quiz.options.map((o, i) => {
    const href = `${PAGE_URL}?q=${quiz.id}&c=${i}`;
    return `<tr><td style="padding:6px 0;">
      <a href="${href}" target="_blank" style="display:block;border:1px solid #2B6CB0;border-radius:12px;background:#FFFFFF;color:#1A4F8B;text-decoration:none;font-family:Tahoma,'Noto Sans Thai',Arial,sans-serif;font-size:22px;line-height:32px;padding:12px 16px;">${esc(o.thai)}</a>
    </td></tr>`;
  }).join("");

  return `<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1.0">
<meta name="color-scheme" content="light"><title>Thai sentence quiz</title></head>
<body style="margin:0;padding:0;background:#EEF2F7;">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#EEF2F7;">
<tr><td align="center" style="padding:16px 8px;">
  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width:560px;background:#FFFFFF;border-radius:18px;overflow:hidden;">
    <tr><td style="background:#0B3D91;padding:28px 24px 24px;font-family:Helvetica,Arial,sans-serif;color:#FFFFFF;text-align:center;">
      <div style="font-size:13px;letter-spacing:2px;text-transform:uppercase;opacity:.8;">Thai sentence quiz</div>
      <div style="font-size:26px;font-weight:bold;line-height:34px;margin-top:6px;">Which sentence says this?</div>
    </td></tr>
    <tr><td style="padding:28px 24px 8px;font-family:Helvetica,Arial,sans-serif;text-align:center;">
      <div style="font-size:24px;line-height:38px;color:#1B1F24;">${highlighted(quiz.sentence_en, quiz.highlight)}</div>
    </td></tr>
    <tr><td style="padding:12px 24px 24px;">
      <table role="presentation" width="100%" cellpadding="0" cellspacing="0">${buttons}</table>
    </td></tr>
    <tr><td style="padding:0 24px 24px;font-family:Helvetica,Arial,sans-serif;font-size:13px;color:#6B7280;text-align:center;">
      Tap a sentence to see the answer. From your Thai vocabulary database.
    </td></tr>
  </table>
</td></tr></table>
</body></html>`;
}
