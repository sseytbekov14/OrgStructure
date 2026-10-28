package cs_orgchart.faq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cs_orgchart.faq.FaqDocument.Item;
import cs_orgchart.faq.FaqDocument.Section;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * U5 parity: the FAQ documents seeded by V10 hold exactly the content of the former FUNCTION_FAQ constant of
 * function.js (kept as src/test/resources/faq-legacy.json; checked equal to the constant before it was removed):
 * same functions, guidelines in order, mailboxes in order with label and address (an address the page showed as
 * "Email to be added" is null). The seeded documents pass the FAQ rules unchanged.
 */
@SpringBootTest
@ActiveProfiles("local")
class FaqSeedParityTest {

    private static final Pattern VALID_EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    @Autowired
    private JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();

    private static JsonNode legacy() throws IOException {
        try (InputStream in = FaqSeedParityTest.class.getResourceAsStream("/faq-legacy.json")) {
            return new ObjectMapper().readTree(in);
        }
    }

    /** The document the seed must hold for an entry of the old constant */
    private static FaqDocument documentOf(JsonNode entry) {
        List<Item> rules = new ArrayList<>();
        entry.get("rules").forEach(r -> rules.add(new Item(r.asText(), null, null, null, null)));
        List<Item> mailboxes = new ArrayList<>();
        entry.get("emails").forEach(m -> {
            String email = m.get("email").asText();
            mailboxes.add(new Item(null, m.get("label").asText(), VALID_EMAIL.matcher(email).matches() ? email : null,
                    null, null));
        });
        return new FaqDocument(List.of(new Section(FaqDocument.GUIDELINES, "Request rules", rules),
                new Section(FaqDocument.MAILBOXES, "Team mailboxes", mailboxes)));
    }

    private Map<String, FaqDocument> seeded() throws IOException {
        Map<String, FaqDocument> seeded = new TreeMap<>();
        for (Map<String, Object> row : jdbc.queryForList("SELECT cs, content::text AS content FROM function_faq"
                + " WHERE updated_by = 'migration'")) {
            seeded.put((String) row.get("cs"), json.readValue((String) row.get("content"), FaqDocument.class));
        }
        return seeded;
    }

    @Test
    void theSeedHoldsTheContentOfTheFormerConstant() throws IOException {
        JsonNode legacy = legacy();
        Map<String, FaqDocument> expected = new TreeMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = legacy.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            expected.put(entry.getKey(), documentOf(entry.getValue()));
        }
        Map<String, FaqDocument> seeded = seeded();
        assertThat(seeded.keySet()).containsExactlyElementsOf(expected.keySet());
        expected.forEach((cs, document) -> assertThat(seeded.get(cs)).as(cs).isEqualTo(document));
    }

    @Test
    void theSeedPassesTheFaqRulesUnchanged() throws IOException {
        seeded().forEach((cs, document) -> assertThat(FaqRules.validate(document)).as(cs).isEqualTo(document));
    }

    @Test
    void theOnlyChangedValueIsTheInvalidAddress() throws IOException {
        int invalid = 0;
        for (JsonNode entry : legacy()) {
            for (JsonNode m : entry.get("emails")) {
                if (!VALID_EMAIL.matcher(m.get("email").asText()).matches()) {
                    invalid++;
                }
            }
        }
        assertThat(invalid).as("the ITS 1C team address '@kpmg.kz'").isEqualTo(1);
    }
}
