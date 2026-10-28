package cs_orgchart.photo;

import cs_orgchart.ad.client.AdDirectoryClient;
import cs_orgchart.ad.config.AdSyncModeCondition;
import cs_orgchart.ad.sync.AdSyncLogWriter;
import cs_orgchart.ad.sync.AuthCircuitBreaker;
import cs_orgchart.employee.EmployeeSourceProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.concurrent.Executors;

/**
 * The photo phase exists with the AD components (AD_SYNC_MODE set); it does something only with AD_PHOTOS_ENABLED=true
 * and EMPLOYEE_SOURCE=db. Its single thread is not a bean (an Executor bean would replace Spring's task executor).
 */
@Configuration
@Conditional(AdSyncModeCondition.class)
public class PhotoSyncConfig {

    @Bean
    public PhotoSyncService photoSyncService(AdDirectoryClient directory, AuthCircuitBreaker breaker,
                                             DataSource dataSource, JdbcTemplate jdbc,
                                             PlatformTransactionManager transactions, EmployeePhotoRepository photos,
                                             AdSyncLogWriter syncLog, ApplicationEventPublisher events,
                                             PhotoProperties properties, EmployeeSourceProperties source) {
        return new PhotoSyncService(directory, breaker, dataSource, jdbc, transactions, photos, syncLog, events,
                properties, source.isDb(), Clock.systemUTC(), Executors.newSingleThreadExecutor(task -> {
                    Thread thread = new Thread(task, "ad-photos");
                    thread.setDaemon(true);
                    return thread;
                }));
    }
}
