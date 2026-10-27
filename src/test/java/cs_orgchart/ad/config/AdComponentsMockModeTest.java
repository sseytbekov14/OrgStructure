package cs_orgchart.ad.config;

import cs_orgchart.ad.client.AdDirectoryClient;
import cs_orgchart.ad.client.MockAdDirectoryClient;
import cs_orgchart.ad.mapper.AdEmployeeMapper;
import cs_orgchart.ad.sync.AdSyncScheduler;
import cs_orgchart.ad.sync.AdSyncService;
import cs_orgchart.ad.sync.AdSyncStartup;
import cs_orgchart.ad.sync.MockOverlaySeeder;
import cs_orgchart.CsOrgchartApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;

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

    @Autowired
    private AdEmployeeMapper mapper;

    @Autowired
    private ApplicationContext context;

    @Test
    void timeoutsDefaultTo10sConnectAnd180sRead() {
        AdLdapProperties.Ldap ldap = context.getBean(AdLdapProperties.class).getLdap();

        assertThat(ldap.getConnectTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(ldap.getReadTimeout()).isEqualTo(Duration.ofSeconds(180));
    }

    @Test
    void mockModeReadsTheSyntheticDirectory() {
        assertThat(client).isInstanceOf(MockAdDirectoryClient.class);
        assertThat(client.fetchAll()).hasSize(43);
        assertThat(mapper.mapAll(client.fetchAll()).employees()).hasSize(41);
    }

    @Test
    void withAdSyncDisabledNothingRunsByItself() {
        assertThat(context.getBeanNamesForType(AdSyncService.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(MockOverlaySeeder.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(AdSyncScheduler.class)).isEmpty();
        assertThat(context.getBeanNamesForType(AdSyncStartup.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
    }

    @Test
    void unknownModeDoesNotStart() {
        SpringApplication app = new SpringApplication(CsOrgchartApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);

        assertThatThrownBy(() -> app.run("--spring.profiles.active=test", "--AD_SYNC_MODE=ldpa").close())
                .hasStackTraceContaining("AD_SYNC_MODE=ldpa is not known: use mock or ldap");
    }
}
