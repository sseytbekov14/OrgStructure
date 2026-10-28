package cs_orgchart.photo;

import cs_orgchart.sso.SsoTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * SSO mode: the photo endpoint follows the rule of every /api/** endpoint (no rule of its own), so without a session
 * it is 401 JSON, never a photo and never the sign-in page.
 */
class PhotoSsoTest extends SsoTestSupport {

    @Test
    void withoutASessionAPhotoIs401Json() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/api/photos/1")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(Objects.toString(response.getContentType(), "")).contains("json").doesNotContain("html")
                .doesNotContain("image");
    }
}
