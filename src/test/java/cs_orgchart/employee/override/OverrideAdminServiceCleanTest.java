package cs_orgchart.employee.override;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The text rules of admin edits (spec v3 §10.2): trimmed, empty → null, length limit, no HTML.
 */
class OverrideAdminServiceCleanTest {

    @Test
    void trimmedAndEmptyIsNull() {
        assertThat(OverrideAdminService.clean("f", "  text  ", 10)).isEqualTo("text");
        assertThat(OverrideAdminService.clean("f", "   ", 10)).isNull();
        assertThat(OverrideAdminService.clean("f", null, 10)).isNull();
    }

    @Test
    void limitIsInclusive() {
        assertThat(OverrideAdminService.clean("f", "x".repeat(500), 500)).hasSize(500);
        assertThatThrownBy(() -> OverrideAdminService.clean("areaOfDuties", "x".repeat(501), 500))
                .isInstanceOfSatisfying(AdminEditException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("TOO_LONG");
                    assertThat(e.getMessage()).contains("areaOfDuties", "500");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"<img src=x onerror=alert(1)>", "<script>alert(1)</script>", "a > b", "x<y",
            "<b>bold</b>"})
    void htmlIsRefused(String text) {
        assertThatThrownBy(() -> OverrideAdminService.clean("jobDescription", text, 500))
                .isInstanceOfSatisfying(AdminEditException.class, e -> assertThat(e.getCode()).isEqualTo("HTML"));
    }

    @Test
    void quotesAndAmpersandsAreFine() {
        assertThat(OverrideAdminService.clean("f", "R&D \"core\" team's", 50)).isEqualTo("R&D \"core\" team's");
    }
}
