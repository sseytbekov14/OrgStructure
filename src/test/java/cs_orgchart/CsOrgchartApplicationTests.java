package cs_orgchart;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Application context smoke test.
 *
 * Uses the "local" profile so that SecurityConfig activates its local branch
 * (anyRequest().permitAll(), CSRF disabled). ClientRegistrationRepository is
 * mocked to satisfy the OAuth2 auto-configuration on the classpath while
 * ensuring no real IdP/SSO is required during CI builds.
 */
@SpringBootTest
@ActiveProfiles("local")
class CsOrgchartApplicationTests {

	@MockitoBean
	ClientRegistrationRepository clientRegistrationRepository;

	@Test
	void contextLoads() {
		// Verifies the full Spring application context starts without errors.
	}

}
