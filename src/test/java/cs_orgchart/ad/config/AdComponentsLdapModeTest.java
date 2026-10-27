package cs_orgchart.ad.config;

import cs_orgchart.ad.client.AdDirectoryClient;
import cs_orgchart.ad.client.LdapAdDirectoryClient;
import cs_orgchart.ad.mapper.DepartmentNormalizer;
import cs_orgchart.ad.mapper.FunctionResolver;
import cs_orgchart.ad.sync.AuthCircuitBreaker;
import cs_orgchart.department.JpaDepartmentDictionaries;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.ldap.core.LdapOperations;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AD_SYNC_MODE=ldap: the AD components exist, but starting the application (and /actuator/health) never connects to
 * the domain controller. A listening socket stands in for it and must see no connection.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdComponentsLdapModeTest {

    private static final ServerSocket DOMAIN_CONTROLLER = listen();

    @DynamicPropertySource
    static void adSettings(DynamicPropertyRegistry registry) {
        registry.add("AD_SYNC_MODE", () -> "ldap");
        registry.add("AD_SYNC_ENABLED", () -> "true");
        registry.add("AD_LDAP_URL", () -> "ldaps://127.0.0.1:" + DOMAIN_CONTROLLER.getLocalPort());
        registry.add("AD_LDAP_BASE_DN", () -> "OU=Countries,OU=CCG,DC=corp,DC=example");
        registry.add("AD_LDAP_BIND_DN", () -> "svc-orgchart@corp.example");
        registry.add("AD_LDAP_BIND_PASSWORD", () -> "test-only-secret");
    }

    @AfterAll
    static void close() throws IOException {
        DOMAIN_CONTROLLER.close();
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private MockMvc mvc;

    @Test
    void adComponentsExist() {
        assertThat(context.getBean(AdDirectoryClient.class)).isInstanceOf(LdapAdDirectoryClient.class);
        assertThat(context.getBean(AdLdapProperties.class).getLdap().getUrl()).startsWith("ldaps://127.0.0.1:");
        assertThat(context.getBeanNamesForType(AuthCircuitBreaker.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(JpaDepartmentDictionaries.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(DepartmentNormalizer.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(FunctionResolver.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(LdapOperations.class)).isEmpty();
    }

    @Test
    void neitherTheStartNorTheHealthCheckConnectsToAd() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        DOMAIN_CONTROLLER.setSoTimeout(500);
        assertThatThrownBy(DOMAIN_CONTROLLER::accept).isInstanceOf(SocketTimeoutException.class);
    }

    @Test
    void passwordIsNotPrinted() {
        assertThat(context.getBean(AdLdapProperties.class).toString()).doesNotContain("test-only-secret");
    }

    private static ServerSocket listen() {
        try {
            return new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
