package cs_orgchart;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Application context smoke test with the "local" profile: no identity provider is needed.
 */
@SpringBootTest
@ActiveProfiles("local")
class CsOrgchartApplicationTests {

	@Test
	void contextLoads() {
		// Verifies the full Spring application context starts without errors.
	}

}
