package cs_orgchart.photo;

import com.fasterxml.jackson.databind.JsonNode;
import cs_orgchart.TestAccounts;
import cs_orgchart.access.DbModeMvcSupport;
import cs_orgchart.ad.client.MockPhotos;
import cs_orgchart.employee.EmployeeDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Photos uploaded on the site: PUT / DELETE /api/me/photo (only the signed-in person's own photo, found by their
 * sign-in name) and PUT / DELETE /api/manage/employees/{id}/photo (admins). Stored as MANUAL, served and shown first
 * (here with AD photos off: uploads do not depend on them), refused when not a usable image, too large, of another type
 * or for somebody outside Central Services; 401 / 403 / CSRF as every write.
 */
class PhotoUploadTest extends DbModeMvcSupport {

    private static final byte[] JPEG = MockPhotos.square(1, "NN", "jpeg");
    private static final byte[] PNG = MockPhotos.square(2, "MM", "png");

    @Autowired
    private EmployeePhotoRepository photos;

    @Autowired
    private EmployeeDirectory directory;

    @Override
    protected String[] tablesToClean() {
        String[] base = super.tablesToClean();
        String[] all = new String[base.length + 1];
        all[0] = "employee_photos";
        System.arraycopy(base, 0, all, 1, base.length);
        return all;
    }

    /** The reader account signs in with the e-mail of November, Navy: their own card */
    @BeforeEach
    void readerIsNavy() {
        jdbc.update("UPDATE employees SET email = ? WHERE login = 'nnavy'", TestAccounts.READER);
    }

    private MockHttpServletResponse send(MockHttpServletRequestBuilder request, MockHttpSession session, byte[] body,
                                         String type) throws Exception {
        MockHttpServletRequestBuilder call = write(request, session, null);
        if (body != null) {
            call.contentType(type).content(body);
        }
        return mvc.perform(call).andReturn().getResponse();
    }

    private JsonNode json(MockHttpServletResponse response) throws Exception {
        return json.readTree(response.getContentAsString());
    }

    private EmployeePhoto stored(String login) {
        return photos.findById(id(login)).orElse(null);
    }

    private cs_orgchart.model.Employee dto(String login) {
        long id = id(login);
        return directory.getAllEmployees().stream().filter(e -> Long.valueOf(id).equals(e.getId())).findFirst()
                .orElseThrow();
    }

    @Test
    void everybodyUploadsTheirOwnPhotoAndItIsShownFirst() throws Exception {
        MockHttpServletResponse response = send(put("/api/me/photo"), reader, JPEG, "image/jpeg");

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        long navy = id("nnavy");
        JsonNode body = json(response);
        assertThat(body.get("employeeId").asLong()).isEqualTo(navy);
        String url = body.get("photoUrl").asText();
        assertThat(url).startsWith("/api/photos/" + navy + "?v=");
        EmployeePhoto photo = stored("nnavy");
        assertThat(photo.getSource()).isEqualTo(PhotoSource.MANUAL);
        assertThat(photo.getUploadedBy()).isEqualTo(TestAccounts.READER);
        assertThat(photo.getState()).isEqualTo(PhotoState.PRESENT);
        assertThat(photo.getContent()).startsWith((byte) 0xFF, (byte) 0xD8, (byte) 0xFF);

        // Served although AD photos are off, and the card shows it with "Remove photo"
        MockHttpServletResponse image = mvc.perform(get("/api/photos/" + navy).session(reader)).andReturn()
                .getResponse();
        assertThat(image.getStatus()).isEqualTo(200);
        assertThat(image.getContentType()).isEqualTo("image/jpeg");
        assertThat(dto("nnavy").getPhotoUrl()).isEqualTo(url);
        assertThat(dto("nnavy").getPhotoUploaded()).isTrue();
        assertThat(dto("mmint").getPhotoUploaded()).isNull();
    }

    @Test
    void aPngIsStoredAsAJpegAndReplacesAnAdPhoto() throws Exception {
        long navy = id("nnavy");
        photos.save(EmployeePhoto.builder().employeeId(navy).state(PhotoState.PRESENT).content(new byte[]{1})
                .contentType("image/jpeg").sha256("a".repeat(64)).checkedAt(Instant.now()).build());

        assertThat(send(put("/api/me/photo"), reader, PNG, "image/png").getStatus()).isEqualTo(200);

        EmployeePhoto photo = stored("nnavy");
        assertThat(photo.getSource()).isEqualTo(PhotoSource.MANUAL);
        assertThat(photo.getContentType()).isEqualTo("image/jpeg");
        assertThat(photo.getSha256()).isNotEqualTo("a".repeat(64));
    }

    @Test
    void theOwnPhotoCanBeRemovedAgain() throws Exception {
        send(put("/api/me/photo"), reader, JPEG, "image/jpeg");

        assertThat(send(delete("/api/me/photo"), reader, null, null).getStatus()).isEqualTo(204);
        assertThat(stored("nnavy")).isNull();
        assertThat(dto("nnavy").getPhotoUploaded()).isNull();

        MockHttpServletResponse again = send(delete("/api/me/photo"), reader, null, null);
        assertThat(again.getStatus()).isEqualTo(404);
        assertThat(json(again).get("code").asText()).isEqualTo("NO_UPLOADED_PHOTO");
    }

    @Test
    void anAdPhotoIsNotAnUploadToRemove() throws Exception {
        long navy = id("nnavy");
        photos.save(EmployeePhoto.builder().employeeId(navy).state(PhotoState.PRESENT).content(new byte[]{1})
                .contentType("image/jpeg").sha256("a".repeat(64)).checkedAt(Instant.now()).build());

        assertThat(send(delete("/api/me/photo"), reader, null, null).getStatus()).isEqualTo(404);
        assertThat(stored("nnavy").getSource()).isEqualTo(PhotoSource.AD);
    }

    @Test
    void nobodyElsesPhotoWithoutBeingAnAdmin() throws Exception {
        long mint = id("mmint");
        assertThat(send(put("/api/manage/employees/" + mint + "/photo"), reader, JPEG, "image/jpeg").getStatus())
                .isEqualTo(403);
        assertThat(send(delete("/api/manage/employees/" + mint + "/photo"), reader, null, null).getStatus())
                .isEqualTo(403);
        assertThat(stored("mmint")).isNull();
    }

    @Test
    void adminsUploadAndRemoveAnybodysPhoto() throws Exception {
        long mint = id("mmint");
        MockHttpServletResponse response = send(put("/api/manage/employees/" + mint + "/photo"), editor, JPEG,
                "image/jpeg");

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        assertThat(stored("mmint").getSource()).isEqualTo(PhotoSource.MANUAL);
        assertThat(stored("mmint").getUploadedBy()).isEqualTo(TestAccounts.EDITOR);

        assertThat(send(delete("/api/manage/employees/" + mint + "/photo"), superAdmin, null, null).getStatus())
                .isEqualTo(204);
        assertThat(stored("mmint")).isNull();
    }

    @Test
    void aSignInThatIsNoEmployeeHasNoOwnPhoto() throws Exception {
        MockHttpServletResponse response = send(put("/api/me/photo"), signIn(TestAccounts.OTHER), JPEG, "image/jpeg");

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(json(response).get("code").asText()).isEqualTo("NOT_AN_EMPLOYEE");
    }

    @Test
    void meRolesNamesTheOwnEmployee() throws Exception {
        JsonNode me = body(mvc.perform(get("/api/me/roles").session(reader)).andReturn());
        assertThat(me.get("employeeId").asLong()).isEqualTo(id("nnavy"));
        assertThat(me.get("photoUpload").asBoolean()).isTrue();

        JsonNode editorMe = body(mvc.perform(get("/api/me/roles").session(editor)).andReturn());
        assertThat(editorMe.get("employeeId").isNull()).isTrue();
    }

    @Test
    void refusedUploadsChangeNothing() throws Exception {
        MockHttpServletResponse text = send(put("/api/me/photo"), reader, "not a photo".getBytes(), "image/jpeg");
        assertThat(text.getStatus()).isEqualTo(400);
        assertThat(json(text).get("code").asText()).isEqualTo("INVALID_PHOTO");

        MockHttpServletResponse type = send(put("/api/me/photo"), reader, JPEG, "text/plain");
        assertThat(type.getStatus()).isEqualTo(415);
        assertThat(json(type).get("code").asText()).isEqualTo("UNSUPPORTED_TYPE");

        byte[] huge = new byte[PhotoUploadService.MAX_UPLOAD_BYTES + 1];
        System.arraycopy(JPEG, 0, huge, 0, JPEG.length);
        MockHttpServletResponse large = send(put("/api/me/photo"), reader, huge, "image/jpeg");
        assertThat(large.getStatus()).isEqualTo(413);
        assertThat(json(large).get("code").asText()).isEqualTo("TOO_LARGE");

        long external = jdbc.queryForObject("SELECT id FROM employees WHERE is_external ORDER BY id LIMIT 1", Long.class);
        MockHttpServletResponse outside = send(put("/api/manage/employees/" + external + "/photo"), editor, JPEG,
                "image/jpeg");
        assertThat(outside.getStatus()).isEqualTo(404);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM employee_photos", Long.class)).isZero();
    }

    @Test
    void writesNeedASessionAndTheCsrfToken() throws Exception {
        assertThat(mvc.perform(put("/api/me/photo").contentType("image/jpeg").content(JPEG)
                .cookie(token).header("X-XSRF-TOKEN", token.getValue())).andReturn().getResponse().getStatus())
                .isEqualTo(401);
        assertThat(mvc.perform(put("/api/me/photo").session(reader).contentType("image/jpeg").content(JPEG))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(delete("/api/me/photo").session(reader)).andReturn().getResponse().getStatus())
                .isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employee_photos", Long.class)).isZero();
    }
}
