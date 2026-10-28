package cs_orgchart.employee.override;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * U3: the values that exist today keep passing the stricter rules: the mock overlay (mock/overrides.json) and the
 * groups of the mock AD (default groups come from department levels). Real database values are checked with the
 * SQL of the progress report before the release.
 */
class ExistingValuesRulesTest {

    private static JsonNode read(String resource) throws IOException {
        try (InputStream in = ExistingValuesRulesTest.class.getResourceAsStream(resource)) {
            return new ObjectMapper().readTree(in);
        }
    }

    @Test
    void theMockOverlayPassesTheRules() throws IOException {
        List<String> refused = new ArrayList<>();
        for (JsonNode row : read("/mock/overrides.json")) {
            check(refused, FieldRules.GROUP_NAME, row.path("groupName").asText(null));
            check(refused, FieldRules.AREA_OF_DUTIES, row.path("areaOfDuties").asText(null));
            check(refused, FieldRules.JOB_DESCRIPTION, row.path("jobDescription").asText(null));
        }
        assertThat(refused).isEmpty();
    }

    private static void check(List<String> refused, FieldRules.Rule rule, String value) {
        FieldRules.Problem problem = FieldRules.check(rule, FieldRules.normalize(value));
        if (problem != null) {
            refused.add(rule.field() + " '" + value + "': " + problem.code());
        }
    }
}
