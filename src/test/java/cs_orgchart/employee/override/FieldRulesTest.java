package cs_orgchart.employee.override;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cs_orgchart.employee.override.FieldRules.Problem;
import cs_orgchart.employee.override.FieldRules.Rule;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * U3: FieldRules against the shared vectors (validation-vectors.json); src/test/js/validation.test.js runs the same
 * vectors through the JavaScript mirror.
 */
class FieldRulesTest {

    private static final Map<String, Rule> RULES = Map.of(
            "groupName", FieldRules.GROUP_NAME,
            "areaOfDuties", FieldRules.AREA_OF_DUTIES,
            "jobDescription", FieldRules.JOB_DESCRIPTION,
            "name", FieldRules.GROUP,
            "reason", FieldRules.HIDE_REASON);

    private static JsonNode vectors() throws IOException {
        try (InputStream in = FieldRulesTest.class.getResourceAsStream("/validation-vectors.json")) {
            return new ObjectMapper().readTree(in);
        }
    }

    private static String build(JsonNode spec) {
        if (spec == null || spec.isNull()) {
            return null;
        }
        if (spec.isTextual()) {
            return spec.asText();
        }
        JsonNode repeat = spec.get("repeat");
        return repeat.get(0).asText().repeat(repeat.get(1).asInt()) + (spec.has("suffix") ? spec.get("suffix").asText() : "");
    }

    @Test
    void theRulesMatchTheVectorsFile() throws IOException {
        vectors().get("rules").fields().forEachRemaining(entry -> {
            Rule rule = RULES.get(entry.getKey());
            JsonNode expected = entry.getValue();
            assertThat(List.of(rule.label(), rule.min(), rule.max(), rule.required()))
                    .isEqualTo(List.of(expected.get("label").asText(), expected.get("min").asInt(),
                            expected.get("max").asInt(), expected.get("required").asBoolean()));
            assertThat(rule.field()).isEqualTo(entry.getKey());
        });
    }

    @TestFactory
    List<DynamicTest> everyVector() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        int i = 0;
        for (JsonNode v : vectors().get("vectors")) {
            String input = v.has("repeat") ? build(v) : build(v.get("input"));
            Rule rule = RULES.get(v.get("field").asText());
            String name = "#" + i++ + " " + rule.field() + " " + (input == null ? "null"
                    : input.substring(0, Math.min(30, input.length())));
            tests.add(DynamicTest.dynamicTest(name, () -> {
                String normalized = FieldRules.normalize(input);
                Problem problem = FieldRules.check(rule, normalized);
                if (v.has("code")) {
                    assertThat(problem).as("refused").isNotNull();
                    assertThat(problem.code()).isEqualTo(v.get("code").asText());
                } else {
                    assertThat(problem).as("valid").isNull();
                    String expected = v.path("valid").asBoolean() ? input
                            : v.has("normalizedRepeat") ? v.get("normalizedRepeat").get(0).asText()
                                    .repeat(v.get("normalizedRepeat").get(1).asInt())
                                    + v.path("normalizedSuffix").asText("")
                            : v.get("normalized").isNull() ? null : v.get("normalized").asText();
                    assertThat(normalized).isEqualTo(expected);
                }
            }));
        }
        assertThat(tests).hasSizeGreaterThan(60);
        return tests;
    }

    @Test
    void theMessagesAreTheAgreedOnes() throws IOException {
        JsonNode data = vectors();
        data.get("messageInputs").fields().forEachRemaining(entry -> {
            Rule rule = RULES.get(entry.getValue().get(0).asText());
            Problem problem = FieldRules.check(rule, FieldRules.normalize(build(entry.getValue().get(1))));
            assertThat(problem.code()).isEqualTo(entry.getKey());
            assertThat(problem.message()).isEqualTo(data.get("messages").get(entry.getKey()).asText());
        });
    }

    @Test
    void errorsCollectEveryFieldAndThrowOnce() {
        FieldRules.Errors errors = new FieldRules.Errors();
        assertThat(errors.apply(FieldRules.GROUP_NAME, "  IT  Ops ")).isEqualTo("IT Ops");
        errors.apply(FieldRules.AREA_OF_DUTIES, "<b>");
        errors.apply(FieldRules.JOB_DESCRIPTION, "=1");
        assertThatThrownBy(errors::throwIfAny)
                .isInstanceOfSatisfying(FieldValidationException.class, e ->
                        assertThat(e.getProblems()).containsOnlyKeys("areaOfDuties", "jobDescription"));
    }
}
