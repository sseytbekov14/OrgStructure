package cs_orgchart.faq;

import cs_orgchart.employee.override.FieldRules.Problem;
import cs_orgchart.employee.override.FieldValidationException;
import cs_orgchart.faq.FaqDocument.Item;
import cs_orgchart.faq.FaqDocument.Section;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The rules of the FAQ documents (U5), mirrored by the FAQ part of static/js/validation.js; both are tested against
 * src/test/resources/faq-vectors.json. Plain text: NFC, tabs and no-break spaces to spaces; one-line fields also have
 * line breaks turned into spaces and repeated spaces collapsed; an answer keeps its line breaks. Refused: {@code < >},
 * control characters (an answer may have line breaks), zero-width and text-direction characters, the schemes
 * {@code javascript: data: vbscript:} and every link other than https:// or mailto:. Limits: section title 80,
 * guideline line 500, mailbox label 200, question 200, answer 2000 characters; at most 12 sections and 30 items per
 * section; mailbox e-mail addresses are checked. Field paths in the errors: {@code sections[0].items[2].answer}.
 */
public final class FaqRules {

    public static final int MAX_SECTIONS = 12;
    public static final int MAX_ITEMS = 30;
    public static final int TITLE_MAX = 80;
    public static final int TEXT_MAX = 500;
    public static final int LABEL_MAX = 200;
    public static final int QUESTION_MAX = 200;
    public static final int ANSWER_MAX = 2000;
    public static final int EMAIL_MAX = 254;
    static final Set<String> KINDS = Set.of(FaqDocument.GUIDELINES, FaqDocument.MAILBOXES, FaqDocument.QUESTIONS);

    private static final Pattern SPACES = Pattern.compile("[\\t\\x{00A0}\\x{2007}\\x{202F}]");
    private static final Pattern LINE_BREAKS = Pattern.compile("\\r\\n?|\\n");
    private static final Pattern INVISIBLE = Pattern.compile("[\\x{200B}-\\x{200F}\\x{202A}-\\x{202E}\\x{2066}-\\x{2069}\\x{FEFF}]");
    private static final Pattern MARKUP = Pattern.compile("[<>]");
    private static final Pattern SCHEME = Pattern.compile("(?i)(javascript|data|vbscript):");
    private static final Pattern OTHER_LINK = Pattern.compile("(?i)\\b(?!https://)[a-z][a-z0-9+.-]*://");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s<>]+@[^@\\s<>]+\\.[^@\\s<>]+");

    private FaqRules() {
    }

    /** One line: NFC, whitespace and line breaks to single spaces, trimmed; empty is null */
    public static String normalizeLine(String value) {
        if (value == null) {
            return null;
        }
        String text = Normalizer.normalize(value, Normalizer.Form.NFC);
        text = LINE_BREAKS.matcher(SPACES.matcher(text).replaceAll(" ")).replaceAll(" ");
        text = text.replaceAll(" {2,}", " ").replaceAll("^ +| +$", "");
        return text.isEmpty() ? null : text;
    }

    /** Several lines: NFC, CRLF to LF, tabs and no-break spaces to spaces, spaces at line ends and outer blank lines
     * removed; empty is null */
    public static String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String text = Normalizer.normalize(value, Normalizer.Form.NFC);
        text = LINE_BREAKS.matcher(SPACES.matcher(text).replaceAll(" ")).replaceAll("\n");
        text = text.replaceAll(" +\n", "\n").replaceAll("^[ \n]+|[ \n]+$", "");
        return text.isEmpty() ? null : text;
    }

    /** The problem of a normalized text, or null */
    public static Problem check(String label, String normalized, int max, boolean required, boolean multiline) {
        if (normalized == null) {
            return required ? new Problem("REQUIRED", label + " is required.") : null;
        }
        if (normalized.codePoints().anyMatch(c -> Character.isISOControl(c) && !(multiline && c == '\n'))) {
            return new Problem("CONTROL", label + " must not contain control characters.");
        }
        if (INVISIBLE.matcher(normalized).find()) {
            return new Problem("INVISIBLE", label + " must not contain invisible or text-direction characters.");
        }
        if (MARKUP.matcher(normalized).find()) {
            return new Problem("MARKUP", label + " must not contain < or > (no HTML).");
        }
        if (SCHEME.matcher(normalized).find() || OTHER_LINK.matcher(normalized).find()) {
            return new Problem("LINK", label + " may only link with https:// or mailto:.");
        }
        if (normalized.codePointCount(0, normalized.length()) > max) {
            return new Problem("TOO_LONG", label + " can have at most " + max + " characters.");
        }
        return null;
    }

    /** The problem of an e-mail address (normalized), or null; empty is allowed ("Email to be added") */
    public static Problem checkEmail(String normalized) {
        if (normalized == null) {
            return null;
        }
        if (normalized.length() > EMAIL_MAX || !EMAIL.matcher(normalized).matches()) {
            return new Problem("EMAIL", "Enter a valid e-mail address, e.g. team@kpmg.kz.");
        }
        return null;
    }

    /** The normalized document; refused values throw one {@link FieldValidationException} with every problem */
    public static FaqDocument validate(FaqDocument document) {
        Map<String, Problem> problems = new LinkedHashMap<>();
        List<Section> input = document == null || document.sections() == null ? List.of() : document.sections();
        if (input.size() > MAX_SECTIONS) {
            problems.put("sections", new Problem("TOO_MANY", "At most " + MAX_SECTIONS + " sections."));
        }
        List<Section> sections = new ArrayList<>();
        for (int s = 0; s < Math.min(input.size(), MAX_SECTIONS); s++) {
            Section section = input.get(s);
            String path = "sections[" + s + "]";
            if (section == null) {
                problems.put(path, new Problem("REQUIRED", "The section is empty."));
                continue;
            }
            String kind = section.kind() == null ? null : section.kind().trim().toUpperCase();
            if (!KINDS.contains(kind)) {
                problems.put(path + ".kind", new Problem("KIND", "A section holds guidelines, mailboxes or questions."));
                continue;
            }
            String title = normalizeLine(section.title());
            put(problems, path + ".title", check("Section title", title, TITLE_MAX, true, false));
            List<Item> items = section.items() == null ? List.of() : section.items();
            if (items.size() > MAX_ITEMS) {
                problems.put(path + ".items", new Problem("TOO_MANY", "At most " + MAX_ITEMS + " items per section."));
            }
            List<Item> cleaned = new ArrayList<>();
            for (int i = 0; i < Math.min(items.size(), MAX_ITEMS); i++) {
                cleaned.add(item(kind, items.get(i), path + ".items[" + i + "]", problems));
            }
            sections.add(new Section(kind, title, cleaned));
        }
        if (!problems.isEmpty()) {
            throw new FieldValidationException(problems);
        }
        return new FaqDocument(sections);
    }

    private static Item item(String kind, Item item, String path, Map<String, Problem> problems) {
        Item given = item == null ? new Item(null, null, null, null, null) : item;
        switch (kind) {
            case FaqDocument.GUIDELINES -> {
                String text = normalizeLine(given.text());
                put(problems, path + ".text", check("Guideline", text, TEXT_MAX, true, false));
                return new Item(text, null, null, null, null);
            }
            case FaqDocument.MAILBOXES -> {
                String label = normalizeLine(given.label());
                String email = normalizeLine(given.email());
                put(problems, path + ".label", check("Mailbox name", label, LABEL_MAX, true, false));
                put(problems, path + ".email", checkEmail(email));
                return new Item(null, label, email, null, null);
            }
            default -> {
                String question = normalizeLine(given.question());
                String answer = normalizeText(given.answer());
                put(problems, path + ".question", check("Question", question, QUESTION_MAX, true, false));
                put(problems, path + ".answer", check("Answer", answer, ANSWER_MAX, true, true));
                return new Item(null, null, null, question, answer);
            }
        }
    }

    private static void put(Map<String, Problem> problems, String path, Problem problem) {
        if (problem != null) {
            problems.put(path, problem);
        }
    }
}
