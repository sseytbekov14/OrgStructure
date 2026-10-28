package cs_orgchart.employee.override;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The rules of the texts admins type that are shown as labels (U3): group name, area of duties, job description.
 * Single source of truth together with its JavaScript mirror static/js/validation.js; both are tested against the
 * same vectors (src/test/resources/validation-vectors.json), so codes and messages are identical on both sides.
 * <ol>
 *   <li>normalize: Unicode NFC, tabs / line breaks / no-break spaces to a space, repeated spaces collapsed, trimmed,
 *       empty to null;</li>
 *   <li>refuse: control characters, zero-width and text-direction characters, {@code < > `}, the schemes
 *       {@code javascript: data: vbscript:}, a leading {@code = + @} (spreadsheet formulas), and anything but letters
 *       (any script), combining marks, digits, space and {@code & / , . : ; - – — ( ) ' ’ " + # % _ ! ?};</li>
 *   <li>length limits in characters (code points).</li>
 * </ol>
 */
public final class FieldRules {

    /** A label field: its name in requests, its label in messages, length limits, whether it may be empty */
    public record Rule(String field, String label, int min, int max, boolean required) {
    }

    /** A refused value: a stable code and the message the page shows */
    public record Problem(String code, String message) {
    }

    public static final Rule GROUP_NAME = new Rule("groupName", "Group", 2, 60, false);
    public static final Rule AREA_OF_DUTIES = new Rule("areaOfDuties", "Area of duties", 0, 120, false);
    public static final Rule JOB_DESCRIPTION = new Rule("jobDescription", "Job description", 0, 200, false);
    /** U4: the name of a group object (required) */
    public static final Rule GROUP = new Rule("name", "Group name", 2, 60, true);
    /** U4: why a person is hidden from the site (optional) */
    public static final Rule HIDE_REASON = new Rule("reason", "Reason", 0, 200, false);

    private static final Pattern SPACES = Pattern.compile("[\\t\\r\\n\\u00A0\\u2007\\u202F]");
    private static final Pattern REPEATED_SPACES = Pattern.compile(" {2,}");
    private static final Pattern EDGE_SPACES = Pattern.compile("^ +| +$");
    private static final Pattern INVISIBLE = Pattern.compile("[\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]");
    private static final Pattern MARKUP = Pattern.compile("[<>`]");
    private static final Pattern SCHEME = Pattern.compile("(?i)(javascript|data|vbscript):");
    private static final Pattern ALLOWED_CHAR =
            Pattern.compile("[\\p{L}\\p{M}\\p{Nd} &/,.:;\\-\\u2013\\u2014()'\\u2019\"+#%_!?]");

    private FieldRules() {
    }

    /** NFC, whitespace to single spaces, trimmed; empty becomes null */
    public static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String text = Normalizer.normalize(value, Normalizer.Form.NFC);
        text = SPACES.matcher(text).replaceAll(" ");
        // Only spaces are trimmed (String.trim would also drop control characters, which the rules refuse instead)
        text = EDGE_SPACES.matcher(REPEATED_SPACES.matcher(text).replaceAll(" ")).replaceAll("");
        return text.isEmpty() ? null : text;
    }

    /** The problem of a normalized value, or null when it is valid */
    public static Problem check(Rule rule, String normalized) {
        String label = rule.label();
        if (normalized == null) {
            return rule.required() ? new Problem("REQUIRED", label + " is required.") : null;
        }
        if (normalized.codePoints().anyMatch(Character::isISOControl)) {
            return new Problem("CONTROL", label + " must not contain control characters.");
        }
        if (INVISIBLE.matcher(normalized).find()) {
            return new Problem("INVISIBLE", label + " must not contain invisible or text-direction characters.");
        }
        if (MARKUP.matcher(normalized).find()) {
            return new Problem("MARKUP", label + " must not contain < > or `.");
        }
        if (SCHEME.matcher(normalized).find()) {
            return new Problem("SCRIPT", label + " must not contain javascript:, data: or vbscript:.");
        }
        char first = normalized.charAt(0);
        if (first == '=' || first == '+' || first == '@') {
            return new Problem("FORMULA", label + " must not start with = + or @.");
        }
        Set<String> refused = new LinkedHashSet<>();
        normalized.codePoints().mapToObj(Character::toString)
                .filter(c -> !ALLOWED_CHAR.matcher(c).matches())
                .forEach(refused::add);
        if (!refused.isEmpty()) {
            return new Problem("CHARACTERS", label + " contains characters that are not allowed: "
                    + refused.stream().limit(5).collect(Collectors.joining(" ")) + ".");
        }
        int length = normalized.codePointCount(0, normalized.length());
        if (length < rule.min()) {
            return new Problem("TOO_SHORT", label + " needs at least " + rule.min() + " characters.");
        }
        if (length > rule.max()) {
            return new Problem("TOO_LONG", label + " can have at most " + rule.max() + " characters.");
        }
        return null;
    }

    /** Collects the problems of several fields of one request; {@link #throwIfAny()} answers 400 with all of them */
    public static final class Errors {

        private final Map<String, Problem> problems = new LinkedHashMap<>();

        /** The normalized value (null when empty); a problem is recorded under the rule's field */
        public String apply(Rule rule, String raw) {
            String normalized = normalize(raw);
            Problem problem = check(rule, normalized);
            if (problem != null) {
                problems.put(rule.field(), problem);
            }
            return normalized;
        }

        public void add(String field, String code, String message) {
            problems.put(field, new Problem(code, message));
        }

        public boolean isEmpty() {
            return problems.isEmpty();
        }

        public void throwIfAny() {
            if (!problems.isEmpty()) {
                throw new FieldValidationException(problems);
            }
        }
    }

    /** Lower-case key of a label, e.g. to compare group names ignoring case */
    public static String key(String normalized) {
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }
}
