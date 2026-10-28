/* Field rules of the texts admins type (U3): the JavaScript mirror of FieldRules.java. Both are tested against the
   same vectors (src/test/resources/validation-vectors.json): same normalization, same codes, same messages. */

const FIELD_RULES = {
  groupName: { field: "groupName", label: "Group", min: 2, max: 60, required: false },
  areaOfDuties: { field: "areaOfDuties", label: "Area of duties", min: 0, max: 120, required: false },
  jobDescription: { field: "jobDescription", label: "Job description", min: 0, max: 200, required: false },
  name: { field: "name", label: "Group name", min: 2, max: 60, required: true },
  reason: { field: "reason", label: "Reason", min: 0, max: 200, required: false }
};

const RULE_SPACES = /[\t\r\n\u{00A0}\u{2007}\u{202F}]/gu;
const RULE_INVISIBLE = /[\u{200B}-\u{200F}\u{202A}-\u{202E}\u{2066}-\u{2069}\u{FEFF}]/u;
const RULE_MARKUP = /[<>`]/;
const RULE_SCHEME = /(javascript|data|vbscript):/i;
const RULE_ALLOWED_CHAR = /^[\p{L}\p{M}\p{Nd} &/,.:;\-–—()'’"+#%_!?]$/u;
// Control characters as Java's Character.isISOControl: U+0000-U+001F and U+007F-U+009F
const RULE_CONTROL = /[\u0000-\u001F\u007F-\u009F]/;

/** NFC, whitespace to single spaces, trimmed; empty becomes null */
function normalizeField(value) {
  if (value == null) return null;
  // Only spaces are trimmed (String.trim would also drop U+FEFF, which the rules refuse instead)
  const text = String(value).normalize("NFC").replace(RULE_SPACES, " ").replace(/ {2,}/g, " ").replace(/^ +| +$/g, "");
  return text === "" ? null : text;
}

/** {code, message} of a normalized value, or null when it is valid */
function checkField(rule, normalized) {
  const label = rule.label;
  if (normalized == null) return rule.required ? { code: "REQUIRED", message: `${label} is required.` } : null;
  if (RULE_CONTROL.test(normalized)) return { code: "CONTROL", message: `${label} must not contain control characters.` };
  if (RULE_INVISIBLE.test(normalized)) {
    return { code: "INVISIBLE", message: `${label} must not contain invisible or text-direction characters.` };
  }
  if (RULE_MARKUP.test(normalized)) return { code: "MARKUP", message: `${label} must not contain < > or \`.` };
  if (RULE_SCHEME.test(normalized)) {
    return { code: "SCRIPT", message: `${label} must not contain javascript:, data: or vbscript:.` };
  }
  if ("=+@".includes(normalized[0])) return { code: "FORMULA", message: `${label} must not start with = + or @.` };
  const refused = [...new Set([...normalized].filter((c) => !RULE_ALLOWED_CHAR.test(c)))];
  if (refused.length) {
    return { code: "CHARACTERS", message: `${label} contains characters that are not allowed: ${refused.slice(0, 5).join(" ")}.` };
  }
  const length = [...normalized].length;
  if (length < rule.min) return { code: "TOO_SHORT", message: `${label} needs at least ${rule.min} characters.` };
  if (length > rule.max) return { code: "TOO_LONG", message: `${label} can have at most ${rule.max} characters.` };
  return null;
}

/** {value (normalized), problem} of a raw input */
function validateField(rule, raw) {
  const value = normalizeField(raw);
  return { value, problem: checkField(rule, value) };
}

/** Characters as the rules count them (code points), for the live counters */
function fieldLength(raw) {
  const value = normalizeField(raw);
  return value == null ? 0 : [...value].length;
}

/* ---------- FAQ documents (U5): the mirror of FaqRules.java, tested against faq-vectors.json ---------- */

const FAQ_LIMITS = { sections: 12, items: 30, title: 80, text: 500, label: 200, question: 200, answer: 2000, email: 254 };
const FAQ_KINDS = ["GUIDELINES", "MAILBOXES", "QUESTIONS"];
const FAQ_SPACES = /[\t\u{00A0}\u{2007}\u{202F}]/gu;
const FAQ_LINE_BREAKS = /\r\n?|\n/g;
const FAQ_OTHER_LINK = /\b(?!https:\/\/)[a-z][a-z0-9+.-]*:\/\//i;
const FAQ_EMAIL = /^[^@\s<>]+@[^@\s<>]+\.[^@\s<>]+$/;

/** One line: NFC, whitespace and line breaks to single spaces, trimmed; empty is null */
function normalizeFaqLine(value) {
  if (value == null) return null;
  const text = String(value).normalize("NFC").replace(FAQ_SPACES, " ").replace(FAQ_LINE_BREAKS, " ")
    .replace(/ {2,}/g, " ").replace(/^ +| +$/g, "");
  return text === "" ? null : text;
}

/** Several lines: NFC, CRLF to LF, spaces at line ends and outer blank lines removed; empty is null */
function normalizeFaqText(value) {
  if (value == null) return null;
  const text = String(value).normalize("NFC").replace(FAQ_SPACES, " ").replace(FAQ_LINE_BREAKS, "\n")
    .replace(/ +\n/g, "\n").replace(/^[ \n]+|[ \n]+$/g, "");
  return text === "" ? null : text;
}

function checkFaqText(label, normalized, max, required, multiline) {
  if (normalized == null) return required ? { code: "REQUIRED", message: `${label} is required.` } : null;
  const control = multiline ? /[\u{0000}-\u{0009}\u{000B}-\u{001F}\u{007F}-\u{009F}]/u : RULE_CONTROL;
  if (control.test(normalized)) return { code: "CONTROL", message: `${label} must not contain control characters.` };
  if (RULE_INVISIBLE.test(normalized)) {
    return { code: "INVISIBLE", message: `${label} must not contain invisible or text-direction characters.` };
  }
  if (/[<>]/.test(normalized)) return { code: "MARKUP", message: `${label} must not contain < or > (no HTML).` };
  if (RULE_SCHEME.test(normalized) || FAQ_OTHER_LINK.test(normalized)) {
    return { code: "LINK", message: `${label} may only link with https:// or mailto:.` };
  }
  if ([...normalized].length > max) return { code: "TOO_LONG", message: `${label} can have at most ${max} characters.` };
  return null;
}

function checkFaqEmail(normalized) {
  if (normalized == null) return null;
  if (normalized.length > FAQ_LIMITS.email || !FAQ_EMAIL.test(normalized)) {
    return { code: "EMAIL", message: "Enter a valid e-mail address, e.g. team@kpmg.kz." };
  }
  return null;
}

/** {document (normalized), errors: {path: {code, message}}} of a FAQ document */
function validateFaqDocument(document) {
  const errors = {};
  const put = (path, problem) => { if (problem) errors[path] = problem; };
  const input = (document && document.sections) || [];
  if (input.length > FAQ_LIMITS.sections) errors.sections = { code: "TOO_MANY", message: `At most ${FAQ_LIMITS.sections} sections.` };
  const sections = [];
  input.slice(0, FAQ_LIMITS.sections).forEach((section, s) => {
    const path = `sections[${s}]`;
    if (!section) { errors[path] = { code: "REQUIRED", message: "The section is empty." }; return; }
    const kind = section.kind == null ? null : String(section.kind).trim().toUpperCase();
    if (!FAQ_KINDS.includes(kind)) {
      errors[`${path}.kind`] = { code: "KIND", message: "A section holds guidelines, mailboxes or questions." };
      return;
    }
    const title = normalizeFaqLine(section.title);
    put(`${path}.title`, checkFaqText("Section title", title, FAQ_LIMITS.title, true, false));
    const items = section.items || [];
    if (items.length > FAQ_LIMITS.items) errors[`${path}.items`] = { code: "TOO_MANY", message: `At most ${FAQ_LIMITS.items} items per section.` };
    const cleaned = items.slice(0, FAQ_LIMITS.items).map((item, i) => {
      const at = `${path}.items[${i}]`;
      const given = item || {};
      if (kind === "GUIDELINES") {
        const text = normalizeFaqLine(given.text);
        put(`${at}.text`, checkFaqText("Guideline", text, FAQ_LIMITS.text, true, false));
        return { text };
      }
      if (kind === "MAILBOXES") {
        const label = normalizeFaqLine(given.label);
        const email = normalizeFaqLine(given.email);
        put(`${at}.label`, checkFaqText("Mailbox name", label, FAQ_LIMITS.label, true, false));
        put(`${at}.email`, checkFaqEmail(email));
        return { label, email };
      }
      const question = normalizeFaqLine(given.question);
      const answer = normalizeFaqText(given.answer);
      put(`${at}.question`, checkFaqText("Question", question, FAQ_LIMITS.question, true, false));
      put(`${at}.answer`, checkFaqText("Answer", answer, FAQ_LIMITS.answer, true, true));
      return { question, answer };
    });
    sections.push({ kind, title, items: cleaned });
  });
  return { document: { sections }, errors };
}