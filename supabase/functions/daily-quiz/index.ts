// Quiz emails ("sentence quiz").
//
//   GET ?q=<quiz id>                    -> the question for the quiz page: English sentence and the
//                                          six Thai sentences, without the answer
//   GET ?q=<quiz id>&c=<choice index>[&ms=<answer time in ms>]
//                                       -> JSON for the answer page; writes one quiz_events row.
//                                          With ms: asked_at = answered_at - ms (time from the
//                                          moment the page showed the choices to the tap)
//   GET ?q=<quiz id>&format=email       -> the email HTML (as text; Supabase sends HTML as text/plain)
//
// For the sending job on the user's server (header x-quiz-send-token = secret QUIZ_SEND_TOKEN):
//   GET  ?action=next                   -> today's quiz: {"quiz": {id, subject, html, text}}, following
//                                          the rule for today's weekday in the table quiz_schedule.
//                                          When no quiz is waiting, the function first creates one
//                                          (word choice below, sentences written by Claude).
//                                          {"quiz": null, "reason": ...} when today's rule says send =
//                                          false, or when no word fits the rule.
//   GET  ?action=plan                   -> today's send time, for the planner run after midnight:
//                                          {date: "YYYY-MM-DD", day_name, time_zone, send: bool,
//                                           send_time: "HH:MM", already_sent: bool}
//   POST ?action=sent&q=<quiz id>       -> marks the quiz as sent (sent_at = now)
//   POST ?action=create&item=<word id>  -> creates a quiz for this word: {"id": ...}; the quiz waits
//                                          until it is sent
//   POST ?action=notify&q=<quiz id>    -> sends only the push message for this quiz (for tests)
//   After ?action=sent, the function sends a push message {"type":"quiz","id":...} to every
//   UnifiedPush address in the table push_endpoints (the quiz app shows a notification).
//
// For the quiz app (header Authorization: Bearer <user's login token>):
//   GET    ?action=practice[&labels=a,b][&exclude=id,id]
//                                       -> a practice quiz: {id, sentence_en, highlight, options: [{thai}]}.
//                                          labels: label keys or teacher names; empty = any word.
//                                          exclude: ids of the quizzes already asked in this round.
//                                          The function keeps one prepared quiz per label filter, so the
//                                          answer is usually immediate; it prepares the next one after
//                                          each request.
//   POST   ?action=push  body {"endpoint": url}  -> saves the app's UnifiedPush address
//   DELETE ?action=push  body {"endpoint": url}  -> removes it
//   The answer request (?q=&c=&ms=) is the same as for the web page, plus &src=app
//   (quiz_events.quiz_type = 'app_sentence_timed').
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

  if (action === "create" && req.method === "POST") {
    // An extra quiz for one given word (for example right after a lesson). The quiz waits
    // in email_quizzes until it is sent.
    const item = url.searchParams.get("item") ?? "";
    if (!UUID.test(item)) return json({ error: "word not found" }, 404);
    try {
      const quiz = await createQuiz(null, { itemId: item });
      return quiz ? json({ id: quiz.id }) : json({ error: "word not found" }, 404);
    } catch (e) {
      return json({ error: `could not create a quiz: ${(e as Error).message}` }, 500);
    }
  }

  if (action === "plan" && req.method === "GET") {
    // For the planner run shortly after midnight: today's send time.
    const today = localDay();
    const { data: rule } = await db.from("quiz_schedule").select("send, send_time")
      .eq("user_id", USER_ID).eq("weekday", today.weekday).maybeSingle();
    return json({
      date: today.iso, day_name: today.name, time_zone: TIME_ZONE,
      send: rule ? rule.send : true,
      send_time: String(rule?.send_time ?? "07:00:00").slice(0, 5),
      already_sent: await sentToday(today.iso),
    });
  }

  if (action === "next" && req.method === "GET") {
    // Today's rule from the table quiz_schedule (weekday in the user's time zone).
    const today = localDay();
    const { data: rule } = await db.from("quiz_schedule").select("send, label")
      .eq("user_id", USER_ID).eq("weekday", today.weekday).maybeSingle();
    if (rule && !rule.send) return json({ quiz: null, reason: `no quiz on ${today.name}` });
    // At most one quiz email per day, also when the job runs twice (for example after a restart).
    if (await sentToday(today.iso)) return json({ quiz: null, reason: "today's quiz was already sent" });
    // label: a label key (vocab_items.context) or a teacher name (vocab_items.teacher); null = any word.
    const label: string | null = rule?.label ?? null;

    // A waiting quiz (for example after a failed send) is used only if it fits today's rule.
    const { data: waiting } = await db.from("email_quizzes").select("*, vocab_items(context, teacher)")
      .eq("source", "email").is("sent_at", null).order("created_at");
    type Waiting = Quiz & { vocab_items: { context: string | null; teacher: string | null } | null };
    let quiz: Quiz | null = ((waiting ?? []) as Waiting[])
      .find((w) => !label || w.vocab_items?.context === label || w.vocab_items?.teacher === label) ?? null;
    if (!quiz) {
      try {
        quiz = await createQuiz(label ? [label] : null);
      } catch (e) {
        return json({ error: `could not create a quiz: ${(e as Error).message}` }, 500);
      }
    }
    if (!quiz) return json({ quiz: null, reason: label ? `no words for label ${label}` : "no words" });
    const date = today.date;
    return json({
      quiz: { id: quiz.id, subject: `Thai Sentence Quiz - ${date}`, html: emailHtml(quiz), text: emailText(quiz) },
    });
  }

  if (action === "notify" && req.method === "POST") {
    // Sends only the push message for a quiz (for tests); the quiz stays unsent.
    const q = url.searchParams.get("q") ?? "";
    if (!UUID.test(q)) return json({ error: "quiz not found" }, 404);
    return json({ pushed: await pushAll({ type: "quiz", id: q }) });
  }

  if (action === "sent" && req.method === "POST") {
    const q = url.searchParams.get("q") ?? "";
    if (!UUID.test(q)) return json({ error: "quiz not found" }, 404);
    const { data } = await db.from("email_quizzes").update({ sent_at: new Date().toISOString() })
      .eq("id", q).select("id, sent_at").maybeSingle();
    if (!data) return json({ error: "quiz not found" }, 404);
    const pushed = await pushAll({ type: "quiz", id: data.id });
    return json({ ...data, pushed });
  }

  return json({ error: "unknown action" }, 400);
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { headers: CORS });
  const url = new URL(req.url);
  const action = url.searchParams.get("action");
  if (action === "practice" || action === "push") return app(req, url, action);
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

  if (!url.searchParams.has("c")) {
    return json({
      sentence_en: quiz.sentence_en, highlight: quiz.highlight,
      options: quiz.options.map((o) => ({ thai: o.thai })),
    });
  }

  const c = Number(url.searchParams.get("c"));
  if (!Number.isInteger(c) || c < 0 || c >= quiz.options.length) return json({ error: "bad choice" }, 400);
  const chosen = quiz.options[c];

  // Answer time measured by the page; accepted up to one hour.
  const ms = Number(url.searchParams.get("ms"));
  const timed = Number.isInteger(ms) && ms > 0 && ms <= 3_600_000;
  const now = Date.now();

  // One row per click, so wrong tries count too.
  const fromApp = url.searchParams.get("src") === "app";
  await db.from("quiz_events").insert({
    user_id: quiz.user_id, item_id: quiz.item_id,
    asked_at: timed ? new Date(now - ms).toISOString() : quiz.created_at,
    answered_at: new Date(now).toISOString(), correct: chosen.correct,
    quiz_type: fromApp ? "app_sentence_timed" : timed ? "email_sentence_timed" : "email_sentence",
    answer: chosen.thai,
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

const TIME_ZONE = Deno.env.get("QUIZ_TIME_ZONE") ?? "Asia/Bangkok";
const WEEKDAYS = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"];

/** Today's weekday (1 = Monday) and date (MM/DD/YYYY) in the user's time zone. */
function localDay(at = new Date()) {
  const parts = Object.fromEntries(new Intl.DateTimeFormat("en-US", {
    timeZone: TIME_ZONE, weekday: "long", year: "numeric", month: "2-digit", day: "2-digit",
  }).formatToParts(at).map((p) => [p.type, p.value]));
  const weekday = WEEKDAYS.indexOf(parts.weekday) + 1;
  return {
    weekday, name: parts.weekday,
    date: `${parts.month}/${parts.day}/${parts.year}`,   // for the email subject
    iso: `${parts.year}-${parts.month}-${parts.day}`,    // for the server timer
  };
}

/** True when a quiz email was already sent on this local date (YYYY-MM-DD). */
async function sentToday(iso: string) {
  const since = new Date(Date.now() - 36 * 3_600_000).toISOString();
  const { data } = await db.from("email_quizzes").select("sent_at")
    .eq("user_id", USER_ID).gte("sent_at", since);
  return (data ?? []).some((r) => localDay(new Date(r.sent_at)).iso === iso);
}

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
async function chooseWord(labels: string[] | null, itemId?: string, exclude: string[] = []): Promise<Word | null> {
  let query = db.from("vocab_items")
    .select("id, thai, romanization, english, part_of_speech, entry_type, created_at")
    .eq("user_id", USER_ID).is("deleted_at", null).is("duplicate_of", null)
    .not("thai", "is", null).not("english", "is", null).not("romanization", "is", null);
  if (itemId) {
    const { data } = await query.eq("id", itemId).maybeSingle();
    return (data as Word | null) ?? null;
  }
  if (labels && labels.length > 0) {
    // A label is a label key (vocab_items.context) or a teacher name (vocab_items.teacher).
    query = query.or(labels.map((l) => l.replace(/[",()]/g, ""))
      .flatMap((v) => [`context.eq."${v}"`, `teacher.eq."${v}"`]).join(","));
  }
  const { data: words, error } = await query;
  if (error) throw new Error(error.message);

  const { data: events } = await db.from("quiz_events")
    .select("item_id, asked_at, answered_at, correct").eq("user_id", USER_ID);
  const since = new Date(Date.now() - RECENT_DAYS * 86_400_000).toISOString();
  const { data: recent } = await db.from("email_quizzes")
    .select("item_id").eq("user_id", USER_ID).gte("created_at", since);
  const skip = new Set((recent ?? []).map((r) => r.item_id));
  const excluded = new Set(exclude);

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
  // If every word was quizzed recently (small label lists), use all words again.
  const allowed = ((words ?? []) as Word[]).filter((w) => !excluded.has(w.id));
  let candidates = allowed.filter((w) => !skip.has(w.id));
  if (candidates.length === 0) candidates = allowed;
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
   written. Use everyday spoken Thai. The learner is a man: use ผม for "I" and "me"
   (never ฉัน), and ครับ (never ค่ะ or คะ) when a sentence has a polite particle. Leave out
   ของผม where Thai speakers would leave it out.
4. distractors: exactly 5 other Thai sentences. Each distractor uses a different word that
   has a similar role or belongs to the same group as ${w.thai} (for "godson": nephew,
   uncle, older brother, cousin, godfather). Use a similar sentence pattern, but vary the
   time word and the verb a little in some sentences (today / on Sunday / tomorrow, forgot to
   visit / will go and see). One or two distractors may keep the exact pattern of the correct
   sentence and change only the tested word. The distractors follow the same rules for ผม
   and ครับ. No distractor may contain ${w.thai}, and no
   distractor may also be a correct translation of sentence_en.

For every Thai sentence, give an English translation, and split the sentence into words
(for example เมื่อวาน | ผม | ไป | หา | ลูกทูนหัว). For each word, give its romanization.
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
    // Spaces are ignored: Thai uses them between phrases (for example after ๆ), and the
    // word list leaves them out.
    const joined = s.words.map((x) => x.thai).join("").replace(/\s+/g, "");
    if (joined !== s.thai.replace(/\s+/g, "")) throw new Error(`word split does not match: ${s.thai}`);
  }
}

type CreateOptions = {
  itemId?: string; exclude?: string[];
  source?: "email" | "app"; labelsKey?: string | null; shown?: boolean;
};

async function createQuiz(labels: string[] | null, opts: CreateOptions = {}): Promise<Quiz | null> {
  if (!USER_ID) throw new Error("secret VOCAB_USER_ID is not set");
  const word = await chooseWord(labels, opts.itemId, opts.exclude);
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
    source: opts.source ?? "email", labels: opts.labelsKey ?? null,
    shown_at: opts.shown ? new Date().toISOString() : null,
  }).select("*").single<Quiz>();
  if (error) throw new Error(error.message);
  return data;
}

// ---------------------------------------------------------------------------------------
// Quiz app

/** The logged-in user of the quiz app, from the Authorization header; null if invalid. */
async function appUser(req: Request): Promise<string | null> {
  const token = (req.headers.get("authorization") ?? "").replace(/^Bearer\s+/i, "");
  if (!token) return null;
  const { data } = await db.auth.getUser(token);
  return data.user?.id ?? null;
}

function labelList(url: URL): string[] {
  return [...new Set((url.searchParams.get("labels") ?? "").split(",").map((s) => s.trim()).filter(Boolean))].sort();
}

/** Creates the prepared quiz for this label filter, unless one is already waiting. */
async function prepare(labels: string[], exclude: string[]) {
  const key = labels.length ? labels.join(",") : null;
  let q = db.from("email_quizzes").select("id").eq("source", "app").is("shown_at", null);
  q = key ? q.eq("labels", key) : q.is("labels", null);
  const { data } = await q.limit(1);
  if (data && data.length > 0) return;
  await createQuiz(labels.length ? labels : null, { exclude, source: "app", labelsKey: key });
}

async function app(req: Request, url: URL, action: string) {
  const user = await appUser(req);
  if (!user || user !== USER_ID) return json({ error: "not logged in" }, 401);

  if (action === "push" && (req.method === "POST" || req.method === "DELETE")) {
    const body = await req.json().catch(() => ({}));
    const endpoint = String(body.endpoint ?? "");
    if (!/^https:\/\/\S+$/.test(endpoint)) return json({ error: "bad endpoint" }, 400);
    if (req.method === "POST") {
      await db.from("push_endpoints").upsert({ user_id: user, endpoint, updated_at: new Date().toISOString() });
    } else {
      await db.from("push_endpoints").delete().eq("user_id", user).eq("endpoint", endpoint);
    }
    return json({ ok: true });
  }

  if (action === "practice" && req.method === "GET") {
    const labels = labelList(url);
    const key = labels.length ? labels.join(",") : null;
    // exclude: ids of the quizzes already asked in this round; the function skips their words.
    const shownIds = (url.searchParams.get("exclude") ?? "").split(",").filter((s) => UUID.test(s)).slice(0, 50);
    const { data: shown } = shownIds.length
      ? await db.from("email_quizzes").select("item_id").in("id", shownIds)
      : { data: [] };
    const exclude = (shown ?? []).map((r) => r.item_id as string);

    // A prepared quiz for the same filter, if its word is not already in this round.
    let q = db.from("email_quizzes").select("*").eq("source", "app").is("shown_at", null);
    q = key ? q.eq("labels", key) : q.is("labels", null);
    const { data: prepared } = await q.order("created_at");
    let quiz: Quiz | null = ((prepared ?? []) as Quiz[]).find((p) => !exclude.includes(p.item_id)) ?? null;
    if (quiz) {
      await db.from("email_quizzes").update({ shown_at: new Date().toISOString() }).eq("id", quiz.id);
    } else {
      try {
        quiz = await createQuiz(labels.length ? labels : null, { exclude, source: "app", labelsKey: key, shown: true });
      } catch (e) {
        return json({ error: `could not create a quiz: ${(e as Error).message}` }, 500);
      }
    }
    if (!quiz) return json({ error: labels.length ? "no words with these labels" : "no words" }, 404);

    // Prepare the next quiz after the response is sent.
    const next = prepare(labels, [...exclude, quiz.item_id]).catch((e) => console.error("prepare:", e.message));
    // deno-lint-ignore no-explicit-any
    (globalThis as any).EdgeRuntime?.waitUntil(next);

    return json({
      id: quiz.id, sentence_en: quiz.sentence_en, highlight: quiz.highlight,
      options: quiz.options.map((o) => ({ thai: o.thai })),
    });
  }

  return json({ error: "unknown action" }, 400);
}

/** Sends a UnifiedPush message to every saved address; removes addresses that are gone. */
async function pushAll(message: unknown): Promise<number> {
  const { data } = await db.from("push_endpoints").select("endpoint").eq("user_id", USER_ID);
  let sent = 0;
  for (const { endpoint } of data ?? []) {
    try {
      const r = await fetch(endpoint, {
        method: "POST", body: JSON.stringify(message),
        headers: { "Content-Type": "application/json", "TTL": "86400" },
      });
      if (r.status === 404 || r.status === 410) {
        await db.from("push_endpoints").delete().eq("endpoint", endpoint);
      } else if (r.ok) sent++;
    } catch (e) {
      console.error("push:", (e as Error).message);
    }
  }
  return sent;
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
    quiz.options.map((o, n) => `${n + 1}. ${o.thai}`).join("\n") +
    `\n\nStart the quiz (the timer starts when the page shows the choices):\n${PAGE_URL}?q=${quiz.id}\n`;
}

function emailHtml(quiz: Quiz) {
  // Preview only: the sentences are plain text. The quiz itself runs on the page, which
  // measures the answer time.
  const preview = quiz.options.map((o) => `<tr><td style="padding:4px 0;">
      <div style="border:1px solid #D7DEE8;border-radius:12px;background:#F6F8FB;color:#1B1F24;font-family:Tahoma,'Noto Sans Thai',Arial,sans-serif;font-size:21px;line-height:30px;padding:10px 16px;">${esc(o.thai)}</div>
    </td></tr>`).join("");
  const start = `${PAGE_URL}?q=${quiz.id}`;

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
    <tr><td style="padding:12px 24px 8px;">
      <table role="presentation" width="100%" cellpadding="0" cellspacing="0">${preview}</table>
    </td></tr>
    <tr><td align="center" style="padding:16px 24px 8px;">
      <a href="${start}" target="_blank" style="display:inline-block;background:#0B3D91;color:#FFFFFF;text-decoration:none;font-family:Helvetica,Arial,sans-serif;font-size:20px;font-weight:bold;line-height:24px;padding:14px 40px;border-radius:999px;">Start the quiz &#9201;</a>
    </td></tr>
    <tr><td style="padding:8px 24px 24px;font-family:Helvetica,Arial,sans-serif;font-size:13px;color:#6B7280;text-align:center;">
      The timer starts when the quiz page shows the choices.
    </td></tr>
  </table>
</td></tr></table>
</body></html>`;
}
