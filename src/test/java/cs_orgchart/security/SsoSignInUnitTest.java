package cs_orgchart.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SsoSignInUnitTest {

    @Test
    void theFailureReason_isOneLine_withoutAddresses() {
        assertThat(SsoSignIn.withoutAddresses("Invalid assertion\n for [Someone@Corp.Example]:\r\n audience"))
                .isEqualTo("Invalid assertion for [<address>]: audience");
        assertThat(SsoSignIn.withoutAddresses(null)).isEmpty();
    }
}
