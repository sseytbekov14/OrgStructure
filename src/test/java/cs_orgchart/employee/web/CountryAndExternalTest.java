package cs_orgchart.employee.web;

import com.fasterxml.jackson.databind.JsonNode;
import cs_orgchart.access.DbModeMvcSupport;
import cs_orgchart.ad.sync.SyncOutcome;
import cs_orgchart.ad.sync.SyncStatus;
import cs_orgchart.ad.sync.SyncTrigger;
import cs_orgchart.employee.EmployeeDirectory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * U6: city and country on the employees (country from co, else the English name of c), pmId / pmExternal, the limited
 * card of external managers (GET /api/people/{id}/external) and never phone, mobile or office in an answer.
 */
class CountryAndExternalTest extends DbModeMvcSupport {

    @Autowired
    private EmployeeDirectory directory;

    private cs_orgchart.model.Employee dto(String name) {
        return directory.getAllEmployees().stream().filter(e -> name.equals(e.getName())).findFirst().orElseThrow();
    }

    @Test
    void employeesHaveCityAndCountryFromAd() {
        cs_orgchart.model.Employee navy = dto("November, Navy");
        assertThat(navy.getCity()).isEqualTo("Almaty");
        assertThat(navy.getCountry()).isEqualTo("Kazakhstan");
        assertThat(jdbc.queryForObject("SELECT country_iso FROM employees WHERE login = 'nnavy'", String.class))
                .isEqualTo("KZ");
    }

    @Test
    void theExternalManagerIsNamedByPmIdAndPmExternal() throws Exception {
        List<cs_orgchart.model.Employee> withExternal = directory.getAllEmployees().stream()
                .filter(e -> Boolean.TRUE.equals(e.getPmExternal())).toList();
        assertThat(withExternal).as("the mock has people whose manager is outside Central Services").isNotEmpty();
        for (cs_orgchart.model.Employee e : withExternal) {
            assertThat(e.getPmId()).isNotNull();
            assertThat(e.getParentId()).as("no node outside Central Services in the tree").isNull();
            assertThat(jdbc.queryForObject("SELECT is_external FROM employees WHERE id = ?", Boolean.class, e.getPmId()))
                    .isTrue();
        }
        cs_orgchart.model.Employee internal = directory.getAllEmployees().stream()
                .filter(e -> e.getParentId() != null).findFirst().orElseThrow();
        assertThat(internal.getPmId()).isEqualTo(internal.getParentId());
        assertThat(internal.getPmExternal()).isFalse();
    }

    @Test
    void theLimitedCardIsOnlyForExternalPeopleAndHasNoPhoneMobileOrOffice() throws Exception {
        long external = jdbc.queryForObject("SELECT id FROM employees WHERE is_external ORDER BY id LIMIT 1", Long.class);
        JsonNode card = body(mvc.perform(get("/api/people/" + external + "/external").session(reader))
                .andExpect(status().isOk()).andReturn());
        List<String> fields = new ArrayList<>();
        card.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactly("name", "email", "jobTitle", "city", "country");
        assertThat(card.get("name").asText()).isNotBlank();
        assertThat(card.toString()).doesNotContain("+000", "Office");

        mvc.perform(get("/api/people/" + id("nnavy") + "/external").session(reader)).andExpect(status().isNotFound());
        mvc.perform(get("/api/people/987654321/external").session(reader)).andExpect(status().isNotFound());
        mvc.perform(get("/api/people/" + external + "/external")).andExpect(status().isUnauthorized());
    }

    @Test
    void withoutCoTheCountryIsTheEnglishNameOfC() throws Exception {
        long external = jdbc.queryForObject("SELECT id FROM employees WHERE is_external ORDER BY id LIMIT 1", Long.class);
        jdbc.update("UPDATE employees SET country_name = NULL, country_iso = 'GB' WHERE id = ?", external);
        mvc.perform(get("/api/people/" + external + "/external").session(reader))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.country")
                        .value("United Kingdom"));
    }

    @Test
    void noAnswerHasPhoneMobileOrOffice() throws Exception {
        String all = mvc.perform(get("/api/employees").session(reader)).andReturn().getResponse().getContentAsString();
        assertThat(all).contains("\"city\"", "\"country\"").doesNotContain("\"phone\"", "\"mobile\"", "\"office\"",
                "+000 ", "Almaty Office");
    }

    @Test
    void theNextSyncFillsTheNewColumnsOfExistingRows() {
        jdbc.update("UPDATE employees SET country_name = NULL, country_iso = NULL");
        SyncOutcome outcome = sync.sync(SyncTrigger.MANUAL);
        assertThat(outcome.status()).isEqualTo(SyncStatus.SUCCESS);
        assertThat(outcome.counts().updated()).isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employees WHERE country_name = 'Kazakhstan'", Long.class))
                .isPositive();
        SyncOutcome again = sync.sync(SyncTrigger.MANUAL);
        assertThat(again.counts().updated()).as("only once").isZero();
    }
}
