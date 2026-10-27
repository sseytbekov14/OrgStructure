package cs_orgchart.ad.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * Matches when AD_SYNC_MODE ({@code ad.sync.mode}) is set. Without it the application has no AD components at all and
 * starts exactly as before the AD integration.
 */
public class AdSyncModeCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return StringUtils.hasText(context.getEnvironment().getProperty("ad.sync.mode"));
    }
}
