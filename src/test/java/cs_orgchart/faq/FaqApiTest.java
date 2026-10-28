package cs_orgchart.faq;

import com.fasterxml.jackson.databind.JsonNode;
import cs_orgchart.access.DbModeMvcSupport;
import cs_orgchart.service.OrgStreamService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * U5: GET /api/functions/{cs}/faq for every signed-in person (ETag, 304), PUT /api/manage/functions/{cs}/faq for
 * admins with optimistic locking, validation with field paths, XSS payloads refused, SSE after the change.
 */
class FaqApiTest extends DbModeMvcSupport {

    @MockitoSpyBean
    private OrgStreamService orgStream;

    private Map<String, Object> original;

    @BeforeEach
    void rememberTheSeed() {
        original = jdbc.queryForMap("SELECT content::text AS content, version, updated_by FROM function_faq WHERE cs = 'QRM'");
        clearInvocations(orgStream);
    }

    @AfterEach
    void restoreTheSeed() {
        jdbc.update("UPDATE function_faq SET content = ?::jsonb, version = ?, updated_by = ? WHERE cs = 'QRM'",
                original.get("content"), original.get("version"), original.get("updated_by"));
    }

    private static Map<String, Object> document(String guideline) {
        return Map.of("sections", List.of(
                Map.of("kind", "GUIDELINES", "title", "Request rules", "items", List.of(Map.of("text", guideline))),
                Map.of("kind", "QUESTIONS", "title", "Questions", "items", List.of(
                        Map.of("question", "Where?", "answer", "See https://portal.example\nor mailto:qrm@kpmg.kz")))));
    }

    private long version() {
        return jdbc.queryForObject("SELECT version FROM function_faq WHERE cs = 'QRM'", Long.class);
    }

    @Test
    void everySignedInPersonReadsTheFaqWithAnEtag() throws Exception {
        MvcResult first = mvc.perform(get("/api/functions/QRM/faq").session(reader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cs").value("QRM"))
                .andExpect(jsonPath("$.content.sections[0].kind").value("GUIDELINES"))
                .andExpect(jsonPath("$.content.sections[1].items[0].email").value("qrm@kpmg.kz"))
                .andExpect(header().exists("ETag"))
                .andReturn();
        String etag = first.getResponse().getHeader("ETag");
        mvc.perform(get("/api/functions/QRM/faq").session(reader).header("If-None-Match", etag))
                .andExpect(status().isNotModified());
        mvc.perform(get("/api/functions/QRM/faq")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/functions/Nowhere/faq").session(reader)).andExpect(status().isNotFound());
    }

    @Test
    void anAdminSavesWithTheVersionAndTheOtherPagesAreTold() throws Exception {
        String before = mvc.perform(get("/api/functions/QRM/faq").session(reader)).andReturn().getResponse()
                .getHeader("ETag");
        mvc.perform(write(put("/api/manage/functions/QRM/faq"), editor,
                        Map.of("content", document("  Check   Sentinel first. "), "version", version())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.sections[0].items[0].text").value("Check Sentinel first."))
                .andExpect(jsonPath("$.content.sections[1].items[0].answer")
                        .value("See https://portal.example\nor mailto:qrm@kpmg.kz"));
        verify(orgStream).publishOrgUpdated("faq");
        assertThat(mvc.perform(get("/api/functions/QRM/faq").session(reader).header("If-None-Match", before))
                .andReturn().getResponse().getStatus()).as("the ETag changed").isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT updated_by FROM function_faq WHERE cs = 'QRM'", String.class))
                .isEqualTo(cs_orgchart.TestAccounts.EDITOR);
    }

    @Test
    void aStaleVersionIs409AndChangesNothing() throws Exception {
        long version = version();
        mvc.perform(write(put("/api/manage/functions/QRM/faq"), editor,
                Map.of("content", document("First"), "version", version))).andExpect(status().isOk());
        mvc.perform(write(put("/api/manage/functions/QRM/faq"), editor,
                        Map.of("content", document("Second"), "version", version)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHANGED"));
        JsonNode now = body(mvc.perform(get("/api/functions/QRM/faq").session(reader)).andReturn());
        assertThat(now.get("content").get("sections").get(0).get("items").get(0).get("text").asText()).isEqualTo("First");
    }

    @Test
    void htmlScriptsAndOtherLinksAreRefusedWithFieldPaths() throws Exception {
        long version = version();
        for (String bad : List.of("<img src=x onerror=alert(1)>", "javascript:alert(1)", "see http://x.example",
                "data:text/html,<b>")) {
            mvc.perform(write(put("/api/manage/functions/QRM/faq"), editor,
                            Map.of("content", document(bad), "version", version)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors['sections[0].items[0].text'].code").exists());
        }
        assertThat(version()).as("nothing stored").isEqualTo(version);
    }

    @Test
    void onlyAdminsWrite() throws Exception {
        long version = version();
        Map<String, Object> body = Map.of("content", document("x y"), "version", version);
        mvc.perform(write(put("/api/manage/functions/QRM/faq"), null, body)).andExpect(status().isUnauthorized());
        mvc.perform(write(put("/api/manage/functions/QRM/faq"), reader, body)).andExpect(status().isForbidden());
        mvc.perform(put("/api/manage/functions/QRM/faq").session(editor).contentType("application/json")
                .content(json.writeValueAsString(body))).andExpect(status().isForbidden());
        mvc.perform(write(put("/api/manage/functions/QRM/faq"), superAdmin, body)).andExpect(status().isOk());
        jdbc.update("UPDATE app_admins SET enabled = FALSE");
        adminAccess.evict();
        mvc.perform(write(put("/api/manage/functions/QRM/faq"), editor, Map.of("content", document("z"),
                "version", version()))).andExpect(status().isForbidden());
    }

    @Test
    void longTextAndManyItemsAreKept() throws Exception {
        List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (int i = 0; i < FaqRules.MAX_ITEMS; i++) {
            items.add(Map.of("question", "Question " + i, "answer", "x".repeat(FaqRules.ANSWER_MAX)));
        }
        mvc.perform(write(put("/api/manage/functions/QRM/faq"), editor, Map.of("content", Map.of("sections",
                        List.of(Map.of("kind", "QUESTIONS", "title", "t".repeat(FaqRules.TITLE_MAX), "items", items))),
                        "version", version())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.sections[0].items.length()").value(FaqRules.MAX_ITEMS));
    }
}
