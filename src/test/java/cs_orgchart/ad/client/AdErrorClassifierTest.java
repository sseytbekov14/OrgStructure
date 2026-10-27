package cs_orgchart.ad.client;

import org.junit.jupiter.api.Test;

import javax.naming.AuthenticationException;
import javax.naming.CommunicationException;
import javax.naming.NamingException;
import javax.naming.NoPermissionException;
import javax.naming.SizeLimitExceededException;
import javax.net.ssl.SSLHandshakeException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each kind of LDAP failure gets its own error code and an actionable message (spec v2.1 §7).
 */
class AdErrorClassifierTest {

    private static final String DC = "dc.corp.example:636";

    @Test
    void invalidCredentialsAreAuthWithTheAdReason() {
        AuthenticationException jndi = new AuthenticationException("[LDAP: error code 49 - 80090308: LdapErr:"
                + " DSID-0C090447, comment: AcceptSecurityContext error, data 52e, v4563]");

        AdDirectoryException e = AdErrorClassifier.classify(new org.springframework.ldap.AuthenticationException(jndi), DC);

        assertThat(e.getCode()).isEqualTo(AdErrorCode.AUTH);
        assertThat(e.getMessage()).contains("LDAP error 49", "wrong user name or password", "AD_LDAP_BIND_DN",
                "AD_LDAP_BIND_PASSWORD", "suspended");
    }

    @Test
    void lockedAccountIsNamed() {
        AdDirectoryException e = AdErrorClassifier.classify(
                new AuthenticationException("[LDAP: error code 49 - AcceptSecurityContext error, data 775, v4563]"), DC);

        assertThat(e.getCode()).isEqualTo(AdErrorCode.AUTH);
        assertThat(e.getMessage()).contains("LOCKED OUT");
    }

    @Test
    void untrustedCertificateIsTlsAndPointsToTheTruststore() {
        CommunicationException jndi = new CommunicationException("simple bind failed: " + DC);
        jndi.setRootCause(new SSLHandshakeException("PKIX path building failed: unable to find valid certification"
                + " path to requested target"));

        AdDirectoryException e = AdErrorClassifier.classify(new org.springframework.ldap.CommunicationException(jndi), DC);

        assertThat(e.getCode()).isEqualTo(AdErrorCode.TLS);
        assertThat(e.getMessage()).contains("truststore missing or wrong", "section 8.1", "AD_LDAP_TRUSTSTORE_PATH");
    }

    @Test
    void certificateForAnotherHostIsTlsAndPointsToTheHostName() {
        SSLHandshakeException handshake = new SSLHandshakeException("No subject alternative DNS name matching"
                + " 10.0.0.1 found.");
        handshake.initCause(new CertificateException("No subject alternative DNS name matching 10.0.0.1 found."));

        AdDirectoryException e = AdErrorClassifier.classify(handshake, DC);

        assertThat(e.getCode()).isEqualTo(AdErrorCode.TLS);
        assertThat(e.getMessage()).contains("AD_LDAP_URL", "host name");
    }

    @Test
    void refusedConnectionIsNetworkAndNamesPort636() {
        CommunicationException jndi = new CommunicationException(DC);
        jndi.setRootCause(new ConnectException("Connection refused"));

        AdDirectoryException e = AdErrorClassifier.classify(jndi, DC);

        assertThat(e.getCode()).isEqualTo(AdErrorCode.NETWORK);
        assertThat(e.getMessage()).contains(DC, "port 636", "Test-NetConnection", "Connection refused");
    }

    @Test
    void connectTimeoutIsNetwork() {
        CommunicationException jndi = new CommunicationException(DC);
        jndi.setRootCause(new SocketTimeoutException("connect timed out"));

        assertThat(AdErrorClassifier.classify(jndi, DC).getCode()).isEqualTo(AdErrorCode.NETWORK);
    }

    @Test
    void unknownHostIsNetworkAndNamesDns() {
        CommunicationException jndi = new CommunicationException("dc.corp.example:636");
        jndi.setRootCause(new UnknownHostException("dc.corp.example"));

        AdDirectoryException e = AdErrorClassifier.classify(jndi, DC);

        assertThat(e.getCode()).isEqualTo(AdErrorCode.NETWORK);
        assertThat(e.getMessage()).contains("DNS");
    }

    @Test
    void readTimeoutIsTimeout() {
        NamingException jndi = new NamingException("LDAP response read timed out, timeout used: 60000 ms.");

        assertThat(AdErrorClassifier.classify(jndi, DC).getCode()).isEqualTo(AdErrorCode.TIMEOUT);
    }

    @Test
    void timeLimitIsTimeout() {
        assertThat(AdErrorClassifier.classify(new javax.naming.TimeLimitExceededException("[LDAP: error code 3]"), DC)
                .getCode()).isEqualTo(AdErrorCode.TIMEOUT);
    }

    @Test
    void insufficientRightsArePermission() {
        AdDirectoryException e = AdErrorClassifier.classify(new org.springframework.ldap.NoPermissionException(
                new NoPermissionException("[LDAP: error code 50 - Insufficient Access Rights]")), DC);

        assertThat(e.getCode()).isEqualTo(AdErrorCode.PERMISSION);
        assertThat(e.getMessage()).contains("insufficient rights", "AD_LDAP_BASE_DN");
    }

    @Test
    void sizeLimitIsLimitAndPointsToThePageSize() {
        AdDirectoryException e = AdErrorClassifier.classify(new org.springframework.ldap.SizeLimitExceededException(
                new SizeLimitExceededException("[LDAP: error code 4 - Sizelimit Exceeded]")), DC);

        assertThat(e.getCode()).isEqualTo(AdErrorCode.LIMIT);
        assertThat(e.getMessage()).contains("AD_LDAP_PAGE_SIZE");
    }

    @Test
    void anythingElseIsLdapWithTheOriginalMessage() {
        AdDirectoryException e = AdErrorClassifier.classify(new NamingException("[LDAP: error code 80 - other]"), DC);

        assertThat(e.getCode()).isEqualTo(AdErrorCode.LDAP);
        assertThat(e.getMessage()).contains("error code 80");
    }

    @Test
    void alreadyClassifiedStaysAsItIs() {
        AdDirectoryException config = new AdDirectoryException(AdErrorCode.CONFIG, "AD_LDAP_URL is not set");

        assertThat(AdErrorClassifier.classify(config, DC)).isSameAs(config);
    }
}
