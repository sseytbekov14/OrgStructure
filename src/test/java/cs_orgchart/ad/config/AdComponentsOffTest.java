package cs_orgchart.ad.config;

import cs_orgchart.ad.client.AdDirectoryClient;
import cs_orgchart.ad.mapper.AdEmployeeMapper;
import cs_orgchart.ad.mapper.DepartmentNormalizer;
import cs_orgchart.ad.mapper.FunctionResolver;
import cs_orgchart.ad.sync.AuthCircuitBreaker;
import cs_orgchart.department.DepartmentDictionaries;
import cs_orgchart.service.ExcelService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.ldap.LdapHealthIndicator;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.ldap.core.ContextSource;
import org.springframework.ldap.core.LdapOperations;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The deploy settings before AD is reachable (spec D8): AD_SYNC_ENABLED=false, EMPLOYEE_SOURCE=excel, no
 * AD_SYNC_MODE. The application starts as before: no AD component, no LDAP connection, no LDAP health check,
 * employees from Excel.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {"AD_SYNC_ENABLED=false", "EMPLOYEE_SOURCE=excel"})
class AdComponentsOffTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private MockMvc mvc;

    @Test
    void noAdComponentExists() {
        for (Class<?> type : List.of(AdDirectoryClient.class, AdEmployeeMapper.class, AuthCircuitBreaker.class,
                DepartmentNormalizer.class, FunctionResolver.class, DepartmentDictionaries.class,
                AdLdapProperties.class, AdConfig.class, ContextSource.class, LdapOperations.class,
                LdapHealthIndicator.class)) {
            assertThat(context.getBeanNamesForType(type)).as(type.getSimpleName()).isEmpty();
        }
    }

    @Test
    void employeesStillComeFromExcel() throws Exception {
        assertThat(context.getBeanNamesForType(ExcelService.class)).hasSize(1);
        mvc.perform(get("/api/functions")).andExpect(status().isOk());
    }

    @Test
    void healthIsUpWithoutAnLdapCheck() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.ldap").doesNotExist());
    }
}
