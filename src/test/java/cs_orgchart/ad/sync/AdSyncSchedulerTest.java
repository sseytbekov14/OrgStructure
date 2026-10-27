package cs_orgchart.ad.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AD_SYNC_CRON: "-" switches the schedule off, an unusable expression is reported and never stops the start.
 */
class AdSyncSchedulerTest {

    private final AdSyncService service = mock(AdSyncService.class);

    private ScheduledTaskRegistrar registered(String cron) {
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
        new AdSyncScheduler(service, cron).configureTasks(registrar);
        return registrar;
    }

    @Test
    void cronExpressionIsScheduled() {
        ScheduledTaskRegistrar registrar = registered("0 */30 * * * *");

        assertThat(registrar.getCronTaskList()).singleElement()
                .satisfies(task -> assertThat(task.getExpression()).isEqualTo("0 */30 * * * *"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-", " - ", "", "every half hour", "*/30 * * * *"})
    void dashEmptyOrUnusableExpressionSchedulesNothing(String cron) {
        assertThat(registered(cron).getCronTaskList()).isEmpty();
    }

    @Test
    void scheduledRunSyncsAndSurvivesAFailure() {
        AdSyncScheduler scheduler = new AdSyncScheduler(service, "0 */30 * * * *");
        when(service.sync(SyncTrigger.SCHEDULED)).thenThrow(new IllegalStateException("boom"));

        assertThatCode(scheduler::runScheduled).doesNotThrowAnyException();
        verify(service).sync(SyncTrigger.SCHEDULED);
    }
}
