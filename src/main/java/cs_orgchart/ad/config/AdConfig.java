package cs_orgchart.ad.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import cs_orgchart.ad.client.AdDirectoryClient;
import cs_orgchart.ad.client.MockAdDirectoryClient;
import cs_orgchart.ad.mapper.DepartmentNormalizer;
import cs_orgchart.ad.mapper.FunctionResolver;
import cs_orgchart.ad.sync.AuthCircuitBreaker;
import cs_orgchart.department.DepartmentAliasRepository;
import cs_orgchart.department.DepartmentDictionaries;
import cs_orgchart.department.DepartmentFunctionMapRepository;
import cs_orgchart.department.DepartmentGeneralNodeRepository;
import cs_orgchart.department.JpaDepartmentDictionaries;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;

/**
 * The AD components, only when AD_SYNC_MODE is set (mock or ldap). Without it none of them exists and the application
 * starts as before. Creating them never contacts AD: the first LDAP connection is made by the first sync.
 */
@Slf4j
@Configuration
@Conditional(AdSyncModeCondition.class)
@EnableConfigurationProperties(AdLdapProperties.class)
public class AdConfig {

    @Bean
    public AdDirectoryClient adDirectoryClient(AdLdapProperties properties, ObjectMapper json) {
        String mode = properties.getSync().getMode().trim().toLowerCase(Locale.ROOT);
        log.info("AD sync mode {} (AD_SYNC_ENABLED={})", mode, properties.getSync().isEnabled());
        return switch (mode) {
            case "mock" -> new MockAdDirectoryClient(json);
            case "ldap" -> LdapConfig.client(properties.getLdap());
            default -> throw new IllegalStateException("AD_SYNC_MODE=" + properties.getSync().getMode()
                    + " is not known: use mock or ldap, or leave it empty (no AD sync)");
        };
    }

    @Bean
    public AuthCircuitBreaker authCircuitBreaker() {
        return new AuthCircuitBreaker();
    }

    @Bean
    public JpaDepartmentDictionaries departmentDictionaries(DepartmentAliasRepository aliases,
                                                            DepartmentGeneralNodeRepository generalNodes,
                                                            DepartmentFunctionMapRepository functionMap) {
        return new JpaDepartmentDictionaries(aliases, generalNodes, functionMap);
    }

    @Bean
    public DepartmentNormalizer departmentNormalizer(DepartmentDictionaries dictionaries) {
        return new DepartmentNormalizer(dictionaries);
    }

    @Bean
    public FunctionResolver functionResolver(DepartmentDictionaries dictionaries) {
        return new FunctionResolver(dictionaries);
    }
}
