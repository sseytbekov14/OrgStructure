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
