package cs_orgchart.importer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The Excel import runs at startup only with OVERRIDES_IMPORT_FROM_EXCEL=true (never in the tests unless they set it).
 */
@Configuration
@EnableConfigurationProperties(OverridesImportProperties.class)
public class OverridesImportConfig {

    @Bean
    @ConditionalOnProperty(name = "app.overrides-import.from-excel", havingValue = "true")
    public OverridesExcelImporter overridesExcelImporter(OverlayImportService service,
                                                         OverridesImportProperties properties, ObjectMapper json) {
        return new OverridesExcelImporter(service, properties, json);
    }
}
