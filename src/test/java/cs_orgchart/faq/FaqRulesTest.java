package cs_orgchart.faq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cs_orgchart.employee.override.FieldRules.Problem;
import cs_orgchart.employee.override.FieldValidationException;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * U5: FaqRules against the shared vectors (faq-vectors.json); src/test/js/faq-rules.test.js runs the same vectors
 * through the JavaScript mirror.
 */
class FaqRulesTest {

    private static final Pattern TOKEN = Pattern.compile("\\{U\\+([0-9A-F]{4})}|\\{NL}");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode vectors() throws IOException {
        try (InputStream in = FaqRulesTest.class.getResourceAsStream("/faq-vectors.json")) {
            return JSON.readTree(in);
        }
    }

    static String expand(String value) {
        if (value == null) {
            return null;
        }
        Matcher m = TOKEN.matcher(value);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String replacement = m.group(1) == null ? "\n" : String.valueOf((char) Integer.parseInt(m.group(1), 16));
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** The tokens inside JSON text as JSON escapes (a raw line break would not be valid JSON) */
    static String expandJson(String json) {
        String backslash = String.valueOf((char) 92);
        Matcher m = TOKEN.matcher(json);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(m.group(1) == null ? backslash + "n" : backslash + "u" + m.group(1)));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String input(JsonNode v) {
        if (v.has("repeat")) {
            return v.get("repeat").get(0).asText().repeat(v.get("repeat").get(1).asInt());
        }
        return v.get("input").isNull() ? null : expand(v.get("input").asText());
    }

    @Test
    void theLimitsMatchTheVectorsFile() throws IOException {
        JsonNode limits = vectors().get("limits");
        assertThat(List.of(FaqRules.TITLE_MAX, FaqRules.TEXT_MAX, FaqRules.LABEL_MAX, FaqRules.QUESTION_MAX,
                FaqRules.ANSWER_MAX)).containsExactly(limits.get("title").asInt(), limits.get("text").asInt(),
                limits.get("label").asInt(), limits.get("question").asInt(), limits.get("answer").asInt());
    }

    @TestFactory
    List<DynamicTest> everyVector() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        int i = 0;
        for (JsonNode v : vectors().get("vectors")) {
            String raw = input(v);
            String kind = v.get("kind").asText();
            tests.add(DynamicTest.dynamicTest("#" + i++ + " " + kind, () -> {
                String normalized = kind.equals("text") ? FaqRules.normalizeText(raw) : FaqRules.normalizeLine(raw);
                Problem problem = kind.equals("email") ? FaqRules.checkEmail(normalized)
                        : FaqRules.check("Field", normalized, v.get("max").asInt(), v.path("required").asBoolean(),
                        kind.equals("text"));
                if (v.has("code")) {
                    assertThat(problem).isNotNull();
                    assertThat(problem.code()).isEqualTo(v.get("code").asText());
                } else {
                    assertThat(problem).isNull();
                    String expected = v.path("valid").asBoolean() ? raw
                            : v.get("normalized").isNull() ? null : expand(v.get("normalized").asText());
                    assertThat(normalized).isEqualTo(expected);
                }
            }));
        }
        return tests;
    }

    @TestFactory
    List<DynamicTest> everyDocument() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode d : vectors().get("documents")) {
            tests.add(DynamicTest.dynamicTest(d.get("name").asText(), () -> {
                FaqDocument document = d.has("sectionsRepeat") ? big(d.get("sectionsRepeat").asInt(),
                        d.get("itemsRepeat").asInt()) : JSON.readValue(expandJson(d.get("input").toString()), FaqDocument.class);
                Map<String, String> errors = new HashMap<>();
                try {
                    FaqRules.validate(document);
                } catch (FieldValidationException e) {
                    e.getProblems().forEach((path, problem) -> errors.put(path, problem.code()));
                }
                Map<String, String> expected = JSON.convertValue(d.get("errors"), Map.class);
                if (d.has("sectionsRepeat")) {
                    assertThat(errors).containsAllEntriesOf(expected);
                } else {
                    assertThat(errors).isEqualTo(expected);
                }
            }));
        }
        return tests;
    }

    @Test
    void aValidDocumentIsNormalized() throws IOException {
        JsonNode first = vectors().get("documents").get(0).get("input");
        FaqDocument document = FaqRules.validate(JSON.readValue(expandJson(first.toString()), FaqDocument.class));
        assertThat(document.sections().get(0).kind()).isEqualTo("GUIDELINES");
        assertThat(document.sections().get(0).title()).isEqualTo("Request rules");
        assertThat(document.sections().get(1).items().get(1).email()).isNull();
        assertThat(document.sections().get(2).items().get(0).answer()).isEqualTo("Like this.\nAnd that.");
    }

    private static FaqDocument big(int sections, int items) {
        List<FaqDocument.Section> list = new ArrayList<>();
        for (int s = 0; s < sections; s++) {
            List<FaqDocument.Item> its = new ArrayList<>();
            for (int i = 0; i < items; i++) {
                its.add(new FaqDocument.Item("Line " + i, null, null, null, null));
            }
            list.add(new FaqDocument.Section("GUIDELINES", "Section " + s, its));
        }
        return new FaqDocument(list);
    }
}
