package cs_orgchart.ad.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import cs_orgchart.ad.client.AdDirectoryClient;
import cs_orgchart.ad.client.MockAdDirectoryClient;
import cs_orgchart.ad.mapper.AdEmployeeMapper;
import cs_orgchart.ad.mapper.DepartmentNormalizer;
import cs_orgchart.ad.mapper.FunctionResolver;
import cs_orgchart.ad.health.AdSyncHealthIndicator;
import cs_orgchart.ad.sync.AdSyncLogRepository;
import cs_orgchart.ad.sync.AdSyncLogWriter;
import cs_orgchart.ad.sync.AdSyncPlanner;
import cs_orgchart.ad.sync.AdSyncService;
import cs_orgchart.ad.sync.AdSyncWriter;
import cs_orgchart.ad.sync.AuthCircuitBreaker;
import cs_orgchart.ad.sync.MockOverlaySeeder;
import cs_orgchart.department.DepartmentAliasRepository;
import cs_orgchart.department.DepartmentDictionaries;
import cs_orgchart.department.DepartmentFunctionMapRepository;
import cs_orgchart.department.DepartmentGeneralNodeRepository;
import cs_orgchart.department.JpaDepartmentDictionaries;
import cs_orgchart.employee.EmployeeRepository;
import cs_orgchart.employee.override.EmployeeOverrideRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
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
    public AdEmployeeMapper adEmployeeMapper(AdLdapProperties properties) {
        return new AdEmployeeMapper(properties.getLdap().allowedCountries());
    }

    @Bean
    public DepartmentNormalizer departmentNormalizer(DepartmentDictionaries dictionaries) {
        return new DepartmentNormalizer(dictionaries);
    }

    @Bean
    public FunctionResolver functionResolver(DepartmentDictionaries dictionaries) {
        return new FunctionResolver(dictionaries);
    }

    // --- the sync (spec v3 §7) ---------------------------------------------------------------------------------

    @Bean
    public AdSyncPlanner adSyncPlanner(AdDirectoryClient directory, AdEmployeeMapper mapper,
                                       DepartmentNormalizer normalizer, FunctionResolver functions,
                                       JpaDepartmentDictionaries dictionaries) {
        return new AdSyncPlanner(directory, mapper, normalizer, functions, dictionaries);
    }

    @Bean
    public AdSyncWriter adSyncWriter(EmployeeRepository employees) {
        return new AdSyncWriter(employees);
    }

    @Bean
    public AdSyncLogWriter adSyncLogWriter(AdSyncLogRepository syncLog) {
        return new AdSyncLogWriter(syncLog, Clock.systemUTC());
    }

    @Bean
    public AdSyncService adSyncService(AdSyncPlanner planner, AdSyncWriter writer, AdSyncLogWriter syncLog,
                                       EmployeeRepository employees, AuthCircuitBreaker breaker,
                                       PlatformTransactionManager transactions, JdbcTemplate jdbc,
                                       ApplicationEventPublisher events, AdLdapProperties properties) {
        return new AdSyncService(planner, writer, syncLog, employees, breaker, new TransactionTemplate(transactions),
                jdbc, events, properties.getSync().getMinRatio(), Clock.systemUTC());
    }

    /** /actuator/health component "adSync" */
    @Bean
    public AdSyncHealthIndicator adSyncHealthIndicator(AdSyncLogRepository syncLog, AuthCircuitBreaker breaker,
                                                       AdLdapProperties properties) {
        return new AdSyncHealthIndicator(syncLog, breaker, properties.getSync().isEnabled());
    }

    /** AD_SYNC_MODE=mock: the overlay of mock/overrides.json after the first sync */
    @Bean
    @ConditionalOnProperty(name = "ad.sync.mode", havingValue = "mock")
    public MockOverlaySeeder mockOverlaySeeder(EmployeeRepository employees, EmployeeOverrideRepository overrides,
                                               ObjectMapper json) {
        return new MockOverlaySeeder(employees, overrides, json);
    }
}
