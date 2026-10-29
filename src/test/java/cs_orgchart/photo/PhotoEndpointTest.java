package cs_orgchart.photo;

import cs_orgchart.access.DbModeMvcSupport;
import cs_orgchart.ad.client.MockPhotos;
import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.Attribute;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/photos/{employeeId} in db mode with AD photos on: the clean JPEG with its headers, 304, 404 for every case
 * that must not be served, 401 without a session, no listing, never HTML; the admin run of the photo phase
 * (POST /api/admin/ad-sync/photos) for env super admins only; the directory's list entity has no image column.
 */
class PhotoEndpointTest extends DbModeMvcSupport {

    @Autowired
    private PhotoProperties properties;

    @Autowired
    private EmployeePhotoRepository photos;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    private final PhotoValidator validator = new PhotoValidator(204_800, 1024);
    private PhotoValidator.Result stored;

    @BeforeEach
    void storeAPhoto() {
        properties.setAdEnabled(true);
        stored = validator.validate(MockPhotos.square(5, "MM", "png"));
        save(id("mmint"), stored);
    }

    @AfterEach
    void photosOff() {
        properties.setAdEnabled(false);
    }

    private void save(long employeeId, PhotoValidator.Result result) {
        photos.save(EmployeePhoto.builder().employeeId(employeeId).state(result.state()).content(result.content())
                .contentType(result.contentType()).width(result.width()).height(result.height())
                .sizeBytes(result.content() == null ? null : result.content().length).sha256(result.sha256())
                .checkedAt(Instant.now()).build());
    }

    private MockHttpServletResponse photo(long employeeId) throws Exception {
        return mvc.perform(get("/api/photos/" + employeeId).session(reader)).andReturn().getResponse();
    }

    private static void notHtml(MockHttpServletResponse response) {
        assertThat(Objects.toString(response.getContentType(), "")).doesNotContain("html");
    }

    @Test
    void theStoredJpegWithItsHeaders() throws Exception {
        MockHttpServletResponse response = photo(id("mmint"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).isEqualTo("image/jpeg");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("Content-Disposition")).isEqualTo("inline");
        assertThat(response.getHeader("ETag")).isEqualTo("\"" + stored.sha256() + "\"");
        assertThat(response.getHeader("Cache-Control")).contains("private").contains("max-age=86400");
        assertThat(response.getContentAsByteArray()).isEqualTo(stored.content());
        assertThat(response.getContentAsByteArray()).startsWith((byte) 0xFF, (byte) 0xD8, (byte) 0xFF);
    }

    @Test
    void aMatchingIfNoneMatchIs304() throws Exception {
        String etag = "\"" + stored.sha256() + "\"";
        MockHttpServletResponse response = mvc.perform(get("/api/photos/" + id("mmint")).session(reader)
                .header("If-None-Match", etag)).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(304);
        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(response.getHeader("ETag")).isEqualTo(etag);

        MockHttpServletResponse other = mvc.perform(get("/api/photos/" + id("mmint")).session(reader)
                .header("If-None-Match", "\"0000\"")).andReturn().getResponse();
        assertThat(other.getStatus()).isEqualTo(200);
    }

    @Test
    void noPresentPhotoIs404() throws Exception {
        save(id("kkhaki"), new PhotoValidator.Result(PhotoState.NONE, null, null, 0, 0, null, null));
        save(id("oolive"), validator.validate("text".getBytes()));
        assertThat(photo(id("kkhaki")).getStatus()).isEqualTo(404);
        assertThat(photo(id("oolive")).getStatus()).isEqualTo(404);
        assertThat(photo(id("nnavy")).getStatus()).as("no row").isEqualTo(404);
        assertThat(photo(987654321L).getStatus()).as("no employee").isEqualTo(404);
    }

    @Test
    void hiddenAndDisabledPeopleAre404ManagersOutsideCentralServicesAreNot() throws Exception {
        long external = jdbc.queryForObject("SELECT id FROM employees WHERE is_external ORDER BY id LIMIT 1", Long.class);
        save(external, stored);
        assertThat(photo(external).getStatus()).as("external").isEqualTo(200);
        jdbc.update("UPDATE employees SET enabled = FALSE WHERE id = ?", external);
        assertThat(photo(external).getStatus()).as("external, disabled").isEqualTo(404);

        jdbc.update("INSERT INTO employee_visibility (employee_id, hidden, hidden_by) VALUES (?, TRUE, 'test')",
                id("mmint"));
        assertThat(photo(id("mmint")).getStatus()).as("hidden").isEqualTo(404);
        jdbc.update("UPDATE employee_visibility SET hidden = FALSE WHERE employee_id = ?", id("mmint"));
        assertThat(photo(id("mmint")).getStatus()).as("restored").isEqualTo(200);

        jdbc.update("UPDATE employees SET enabled = FALSE WHERE id = ?", id("mmint"));
        assertThat(photo(id("mmint")).getStatus()).as("disabled").isEqualTo(404);
    }

    @Test
    void switchedOffEverythingIs404() throws Exception {
        properties.setAdEnabled(false);
        MockHttpServletResponse response = photo(id("mmint"));
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    @Test
    void withoutASessionIs401() throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/api/photos/" + id("mmint"))).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(401);
        notHtml(response);
    }

    @Test
    void thereIsNoWayToListPhotos() throws Exception {
        List<String> patterns = mappings.getHandlerMethods().keySet().stream()
                .flatMap(info -> info.getPatternValues().stream()).filter(p -> p.contains("photo")).toList();
        // The uploads (PUT and DELETE each) write one photo; the admin run of the photo phase; one read
        assertThat(patterns).containsExactlyInAnyOrder("/api/photos/{employeeId}", "/api/admin/ad-sync/photos",
                "/api/me/photo", "/api/me/photo", "/api/manage/employees/{id}/photo",
                "/api/manage/employees/{id}/photo");
        List<String> reads = mappings.getHandlerMethods().keySet().stream()
                .filter(info -> info.getMethodsCondition().getMethods().isEmpty()
                        || info.getMethodsCondition().getMethods().contains(RequestMethod.GET))
                .flatMap(info -> info.getPatternValues().stream()).filter(p -> p.contains("photo")).toList();
        assertThat(reads).as("the only way to read photos: one by id").containsExactly("/api/photos/{employeeId}");
        for (String path : List.of("/api/photos", "/api/photos/", "/api/photos?ids=1,2", "/api/photos/all")) {
            MockHttpServletResponse response = mvc.perform(get(path).session(reader)).andReturn().getResponse();
            assertThat(response.getStatus()).as(path).isIn(400, 404, 405);
            assertThat(response.getContentAsByteArray()).as(path).isNotEqualTo(stored.content());
            notHtml(response);
        }
    }

    @Test
    void theResponseIsNeverHtml() throws Exception {
        for (long employee : List.of(id("mmint"), id("kkhaki"), 987654321L)) {
            notHtml(photo(employee));
        }
        MockHttpServletResponse response = mvc.perform(get("/api/photos/" + id("mmint")).session(reader)
                .accept("text/html")).andReturn().getResponse();
        assertThat(response.getStatus()).isIn(200, 406);
        notHtml(response);
    }

    @Test
    void theListEntityHasNoImageColumn() {
        assertThat(entityManager.getMetamodel().entity(EmployeePhotoInfo.class).getAttributes())
                .extracting(Attribute::getName).doesNotContain("content")
                .contains("employeeId", "state", "sha256", "sourceWhenChanged");
        assertThat(EmployeePhotoInfo.class.getDeclaredFields()).noneMatch(f -> f.getType() == byte[].class);
    }

    @Test
    void theAdminRunIsForEnvSuperAdminsOnly() throws Exception {
        assertThat(mvc.perform(write(post("/api/admin/ad-sync/photos"), reader, null)).andReturn().getResponse()
                .getStatus()).isEqualTo(403);
        assertThat(mvc.perform(write(post("/api/admin/ad-sync/photos"), editor, null)).andReturn().getResponse()
                .getStatus()).isEqualTo(403);
        MockHttpServletResponse run = mvc.perform(write(post("/api/admin/ad-sync/photos").param("force", "true"),
                superAdmin, null)).andReturn().getResponse();
        assertThat(run.getStatus()).isEqualTo(200);
        assertThat(json.readTree(run.getContentAsString()).get("status").asText()).isEqualTo("DONE");
        assertThat(json.readTree(run.getContentAsString()).get("fetched").asInt()).isPositive();
        assertThat(run.getContentAsString()).doesNotContain("@kz.example");

        properties.setAdEnabled(false);
        MockHttpServletResponse off = mvc.perform(write(post("/api/admin/ad-sync/photos"), superAdmin, null))
                .andReturn().getResponse();
        assertThat(off.getStatus()).isEqualTo(409);
        assertThat(off.getContentAsString()).contains("DISABLED");
    }

    @Test
    void theDryRunReportCountsPhotoCandidates() throws Exception {
        MockHttpServletResponse report = mvc.perform(write(post("/api/admin/ad-sync").param("dryRun", "true"),
                superAdmin, null)).andReturn().getResponse();
        assertThat(report.getStatus()).isEqualTo(200);
        int servable = jdbc.queryForObject("SELECT count(*) FROM employees WHERE enabled", Integer.class);
        // mmint has a row (with no whenChanged of its own: it differs, so it counts too)
        assertThat(json.readTree(report.getContentAsString()).get("photoCandidates").asInt()).isEqualTo(servable);
        assertThat(count()).isEqualTo(1);
    }

    private int count() {
        return jdbc.queryForObject("SELECT count(*) FROM employee_photos", Integer.class);
    }

    @Override
    protected String[] tablesToClean() {
        String[] base = super.tablesToClean();
        String[] all = new String[base.length + 1];
        all[0] = "employee_photos";
        System.arraycopy(base, 0, all, 1, base.length);
        return all;
    }
}
