// Shared by the Edge Functions "process" (enrich step) and "vocab-mcp" (add_vocab tool),
// so that every source of words uses the same romanization rules and word classes.

export const PARTS_OF_SPEECH = [
  "noun", "verb", "adjective", "adverb", "preposition", "article",
  "pronoun", "classifier", "particle", "conjunction", "number", "other",
];

/** Paiboon+ romanization, written out in full so the model has no room for its own style. */
export const ROMANIZATION_RULES = `Paiboon+ romanization rules (follow exactly):
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
