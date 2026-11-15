// Quiz emails ("sentence quiz").
//
//   GET ?q=<quiz id>&c=<choice index>   -> JSON for the answer page; writes one quiz_events row
//   GET ?q=<quiz id>&format=email       -> the email HTML (as text; Supabase sends HTML as text/plain)
//
// For the sending job on the user's server (header x-quiz-send-token = secret QUIZ_SEND_TOKEN):
//   GET  ?action=next                   -> the oldest unsent quiz: {"quiz": {id, subject, html, text}}.
//                                          When no quiz is waiting, the function first creates one
//                                          (word choice below, sentences written by Claude).
//                                          {"quiz": null} only when no word can be quizzed.
//   POST ?action=sent&q=<quiz id>       -> marks the quiz as sent (sent_at = now)
//
// The answer page itself is a static page on the user's web server (web/quiz/), because Supabase
// changes text/html to text/plain on its own domains. The quiz id is a random UUID; the
// function returns only the data of that one quiz.

import { createClient } from "npm:@supabase/supabase-js@2";
import Anthropic from "npm:@anthropic-ai/sdk";
import { ROMANIZATION_RULES } from "../_shared/romanization.ts";

// Address of web/quiz/index.html on the user's web server (Supabase secret QUIZ_PAGE_URL).
const PAGE_URL = Deno.env.get("QUIZ_PAGE_URL") ?? "";

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
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

const SEND_TOKEN = Deno.env.get("QUIZ_SEND_TOKEN") ?? "";

function sameText(a: string, b: string) {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

/** Requests from the sending job: get the next unsent quiz, or mark one as sent. */
async function sender(req: Request, url: URL, action: string) {
  if (!SEND_TOKEN || !sameText(req.headers.get("x-quiz-send-token") ?? "", SEND_TOKEN)) {
    return json({ error: "not allowed" }, 403);
  }
  if (!PAGE_URL) return json({ error: "secret QUIZ_PAGE_URL is not set" }, 500);

  if (action === "next" && req.method === "GET") {
    let { data: quiz } = await db.from("email_quizzes").select("*")
      .is("sent_at", null).order("created_at").limit(1).maybeSingle<Quiz>();
    if (!quiz) {
      try {
        quiz = await createQuiz();
      } catch (e) {
        return json({ error: `could not create a quiz: ${(e as Error).message}` }, 500);
      }
    }
    if (!quiz) return json({ quiz: null });
    const d = new Date();
    const date = `${String(d.getUTCMonth() + 1).padStart(2, "0")}/${String(d.getUTCDate()).padStart(2, "0")}/${d.getUTCFullYear()}`;
    return json({
      quiz: { id: quiz.id, subject: `Thai Sentence Quiz - ${date}`, html: emailHtml(quiz), text: emailText(quiz) },
    });
  }

  if (action === "sent" && req.method === "POST") {
    const q = url.searchParams.get("q") ?? "";
    if (!UUID.test(q)) return json({ error: "quiz not found" }, 404);
    const { data } = await db.from("email_quizzes").update({ sent_at: new Date().toISOString() })
      .eq("id", q).select("id, sent_at").maybeSingle();
    return data ? json(data) : json({ error: "quiz not found" }, 404);
  }

  return json({ error: "unknown action" }, 400);
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { headers: CORS });
  const url = new URL(req.url);
  const action = url.searchParams.get("action");
  if (action) return sender(req, url, action);
  if (req.method !== "GET") return json({ error: "method not allowed" }, 405);

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
// Quiz creation

const USER_ID = Deno.env.get("VOCAB_USER_ID") ?? "";
const RECENT_DAYS = 7; // a word gets at most one email quiz in this many days

type Word = {
  id: string; thai: string; romanization: string; english: string;
  part_of_speech: string | null; entry_type: string | null; created_at: string;
};

/**
 * Weakest word first:
 *   1. words whose most recent quiz answer was wrong (most recent wrong answer first),
 *   2. words that were never quizzed (oldest saved word first),
 *   3. all other words (word with the oldest last quiz first).
 * Words that had an email quiz in the last RECENT_DAYS days are skipped.
 */
async function chooseWord(): Promise<Word | null> {
  const { data: words, error } = await db.from("vocab_items")
    .select("id, thai, romanization, english, part_of_speech, entry_type, created_at")
    .eq("user_id", USER_ID).is("deleted_at", null).is("duplicate_of", null)
    .not("thai", "is", null).not("english", "is", null).not("romanization", "is", null);
  if (error) throw new Error(error.message);

  const { data: events } = await db.from("quiz_events")
    .select("item_id, asked_at, answered_at, correct").eq("user_id", USER_ID);
  const since = new Date(Date.now() - RECENT_DAYS * 86_400_000).toISOString();
  const { data: recent } = await db.from("email_quizzes")
    .select("item_id").eq("user_id", USER_ID).gte("created_at", since);
  const skip = new Set((recent ?? []).map((r) => r.item_id));

  // Most recent answer per word.
  const last = new Map<string, { at: string; correct: boolean | null }>();
  for (const e of events ?? []) {
    const at = e.answered_at ?? e.asked_at;
    const prev = last.get(e.item_id);
    if (!prev || at > prev.at) last.set(e.item_id, { at, correct: e.correct });
  }

  const rank = (w: Word): [number, number] => {
    const l = last.get(w.id);
    if (!l) return [1, Date.parse(w.created_at)];                // never quizzed: oldest first
    if (l.correct === false) return [0, -Date.parse(l.at)];      // wrong: most recent first
    return [2, Date.parse(l.at)];                                // others: oldest quiz first
  };
  const candidates = ((words ?? []) as Word[]).filter((w) => !skip.has(w.id));
  candidates.sort((a, b) => {
    const [ga, ta] = rank(a), [gb, tb] = rank(b);
    return ga - gb || ta - tb;
  });
  return candidates[0] ?? null;
}

// Claude romanizes each sentence word by word; the function joins the words. The joined
// Thai words must give the full sentence exactly, which catches skipped or extra words.
const SENTENCE = {
  type: "object",
  properties: {
    thai: { type: "string" },
    words: {
      type: "array",
      items: {
        type: "object",
        properties: { thai: { type: "string" }, romanization: { type: "string" } },
        required: ["thai", "romanization"],
        additionalProperties: false,
      },
    },
    english: { type: "string" },
  },
  required: ["thai", "words", "english"],
  additionalProperties: false,
};

const QUIZ_SCHEMA = {
  type: "object",
  properties: {
    sentence_en: { type: "string" },
    highlight: { type: "string" },
    correct: SENTENCE,
    distractors: { type: "array", items: SENTENCE },
  },
  required: ["sentence_en", "highlight", "correct", "distractors"],
  additionalProperties: false,
};

type Sentence = { thai: string; words: { thai: string; romanization: string }[]; english: string };
type Written = { sentence_en: string; highlight: string; correct: Sentence; distractors: Sentence[] };

const romanized = (s: Sentence) => ({
  thai: s.thai, english: s.english, romanization: s.words.map((w) => w.romanization.trim()).join(" "),
});

function prompt(w: Word) {
  return `You write a multiple-choice quiz for an English-speaking learner of Thai (beginner to
lower intermediate). The quiz tests one Thai word:

  Thai: ${w.thai}
  Romanization: ${w.romanization}
  Meaning: ${w.english}
  Part of speech: ${w.part_of_speech ?? "unknown"}

Write:
1. sentence_en: one short, simple, everyday English sentence (6-12 words) that uses the
   meaning of the word, for example "Yesterday, I went to see my godson." Choose the most
   common meaning if there are several.
2. highlight: the English word or words in sentence_en that translate the Thai word. Copy
   them exactly as they appear in sentence_en.
3. correct: the natural Thai translation of sentence_en. It must contain ${w.thai} exactly as
   written. Use everyday spoken Thai with ฉัน for "I", and leave out ของฉัน where Thai
   speakers would leave it out.
4. distractors: exactly 5 other Thai sentences. Each distractor uses a different word that
   has a similar role or belongs to the same group as ${w.thai} (for "godson": nephew,
   uncle, older brother, cousin, godfather). Use a similar sentence pattern, but vary the
   time word and the verb a little in some sentences (today / on Sunday / tomorrow, forgot to
   visit / will go and see). One or two distractors may keep the exact pattern of the correct
   sentence and change only the tested word. No distractor may contain ${w.thai}, and no
   distractor may also be a correct translation of sentence_en.

For every Thai sentence, give an English translation, and split the sentence into words
(for example เมื่อวาน | ฉัน | ไป | หา | ลูกทูนหัว). For each word, give its romanization.
The Thai words, joined without spaces, must give the full sentence exactly. Romanize each
word syllable by syllable, with the real spoken tone and vowel length.

${ROMANIZATION_RULES}`;
}

let anthropic: Anthropic | null = null;

// deno-lint-ignore no-explicit-any
async function askClaude(content: string, schema: unknown): Promise<any> {
  anthropic ??= new Anthropic({ timeout: 120_000, maxRetries: 0 });
  const response = await anthropic.beta.messages.create({
    model: Deno.env.get("QUIZ_MODEL") ?? "claude-sonnet-5-5",
    max_tokens: 16000,
    // If a safety classifier declines, the API re-runs the request on a fallback model.
    betas: ["server-side-fallback-2026-07-01"],
    fallbacks: "default",
    output_config: {
      effort: "high",
      // deno-lint-ignore no-explicit-any
      format: { type: "json_schema", schema: schema as any },
    },
    messages: [{ role: "user", content }],
  });
  if (response.stop_reason === "refusal") throw new Error("Claude declined the request");
  if (response.stop_reason === "max_tokens") throw new Error("Claude answer was cut off");
  let text = "";
  for (const block of response.content) if (block.type === "text") text += block.text;
  return JSON.parse(text);
}

/** Checks the rules that the page and the email depend on. */
function check(w: Word, q: Written) {
  if (!q.sentence_en.toLowerCase().includes(q.highlight.toLowerCase())) throw new Error("highlight is not in the sentence");
  if (!q.correct.thai.includes(w.thai)) throw new Error("correct sentence lacks the word");
  if (q.distractors.length !== 5) throw new Error(`expected 5 distractors, got ${q.distractors.length}`);
  const all = [q.correct.thai, ...q.distractors.map((d) => d.thai)];
  if (new Set(all).size !== all.length) throw new Error("two sentences are the same");
  if (q.distractors.some((d) => d.thai.includes(w.thai))) throw new Error("a distractor contains the word");
  for (const s of [q.correct, ...q.distractors]) {
    if (s.words.map((x) => x.thai).join("") !== s.thai) throw new Error(`word split does not match: ${s.thai}`);
  }
}

async function createQuiz(): Promise<Quiz | null> {
  if (!USER_ID) throw new Error("secret VOCAB_USER_ID is not set");
  const word = await chooseWord();
  if (!word) return null;

  let written: Written | null = null;
  let lastError: Error | null = null;
  for (let attempt = 0; attempt < 2 && !written; attempt++) {
    try {
      const q = await askClaude(prompt(word), QUIZ_SCHEMA) as Written;
      check(word, q);
      written = q;
    } catch (e) {
      lastError = e as Error;
    }
  }
  if (!written) throw lastError ?? new Error("quiz could not be written");

  const options: Option[] = [
    { ...romanized(written.correct), correct: true },
    ...written.distractors.map((d) => ({ ...romanized(d), correct: false })),
  ];
  for (let i = options.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [options[i], options[j]] = [options[j], options[i]];
  }

  const { data, error } = await db.from("email_quizzes").insert({
    user_id: USER_ID, item_id: word.id,
    sentence_en: written.sentence_en, highlight: written.highlight, options,
  }).select("*").single<Quiz>();
  if (error) throw new Error(error.message);
  return data;
}

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

/** Plain-text version, for email programs that show no HTML. */
function emailText(quiz: Quiz) {
  const i = quiz.sentence_en.toLowerCase().indexOf(quiz.highlight.toLowerCase());
  const sentence = i < 0 ? quiz.sentence_en
    : quiz.sentence_en.slice(0, i) + quiz.highlight.toUpperCase() + quiz.sentence_en.slice(i + quiz.highlight.length);
  return `Thai sentence quiz. Which sentence says: "${sentence}"\n\n` +
    quiz.options.map((o, n) => `${n + 1}. ${o.thai}\n${PAGE_URL}?q=${quiz.id}&c=${n}`).join("\n") + "\n";
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
