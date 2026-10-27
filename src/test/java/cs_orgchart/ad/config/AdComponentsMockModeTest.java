package cs_orgchart.ad.config;

import cs_orgchart.ad.client.AdDirectoryClient;
import cs_orgchart.ad.client.MockAdDirectoryClient;
import cs_orgchart.CsOrgchartApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AD_SYNC_MODE=mock gives the synthetic directory; an unknown mode stops the start with a message (a typo must not
 * silently switch the AD sync off).
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "AD_SYNC_MODE=mock")
class AdComponentsMockModeTest {

    @Autowired
    private AdDirectoryClient client;

    @Test
    void mockModeReadsTheSyntheticDirectory() {
        assertThat(client).isInstanceOf(MockAdDirectoryClient.class);
        assertThat(client.fetchAll()).hasSize(41);
    }

    @Test
    void unknownModeDoesNotStart() {
        SpringApplication app = new SpringApplication(CsOrgchartApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);

        assertThatThrownBy(() -> app.run("--spring.profiles.active=test", "--AD_SYNC_MODE=ldpa").close())
                .hasStackTraceContaining("AD_SYNC_MODE=ldpa is not known: use mock or ldap");
    }
}
