package cs_orgchart.ad.client;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.util.ssl.KeyStoreKeyManager;
import com.unboundid.util.ssl.SSLUtil;
import com.unboundid.util.ssl.TrustAllTrustManager;
import cs_orgchart.ad.config.AdLdapProperties;
import cs_orgchart.ad.config.LdapConfig;
import cs_orgchart.ad.model.AdRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static cs_orgchart.ad.client.LdapAdDirectoryClientTest.BIND_DN;
import static cs_orgchart.ad.client.LdapAdDirectoryClientTest.PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LDAPS with the truststore of AD_LDAP_TRUSTSTORE_PATH (spec v3 §8.1): the certificate of the domain controller is
 * trusted for the LDAP connection only. The JVM-wide trust (used by the SAML metadata fetch and every other TLS
 * connection) is not changed.
 */
class LdapsTruststoreTest {

    private static final String STORE_PASSWORD = "test-only-store";

    @TempDir
    static Path dir;

    private static InMemoryDirectoryServer server;
    private static Path truststore;

    @BeforeAll
    static void startLdapsServer() throws Exception {
        Path serverKeys = dir.resolve("dc.p12");
        Path certificate = dir.resolve("dc.cer");
        truststore = dir.resolve("ad-truststore.jks");
        keytool("-genkeypair", "-alias", "dc", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-keystore", serverKeys.toString(), "-storetype", "PKCS12", "-storepass", STORE_PASSWORD,
                "-keypass", STORE_PASSWORD);
        keytool("-exportcert", "-alias", "dc", "-keystore", serverKeys.toString(), "-storepass", STORE_PASSWORD,
                "-file", certificate.toString());
        keytool("-importcert", "-noprompt", "-alias", "corporate-ca", "-file", certificate.toString(),
                "-keystore", truststore.toString(), "-storetype", "JKS", "-storepass", STORE_PASSWORD);

        SSLUtil serverTls = new SSLUtil(new KeyStoreKeyManager(serverKeys.toFile(), STORE_PASSWORD.toCharArray(),
                "PKCS12", "dc"), new TrustAllTrustManager());
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(LdapAdDirectoryClientTest.ROOT);
        config.setSchema(null);
        config.setListenerConfigs(InMemoryListenerConfig.createLDAPSConfig("ldaps", null, 0,
                serverTls.createSSLServerSocketFactory(), null));
        config.addAdditionalBindCredentials(BIND_DN, PASSWORD);
        config.addInMemoryOperationInterceptor(new LdapAdDirectoryClientTest.Counter());
        server = new InMemoryDirectoryServer(config);
        server.startListening();
        LdapAdDirectoryClientTest.addDirectory(server);
    }

    @AfterAll
    static void stopServer() {
        server.shutDown(true);
    }

    private static AdLdapProperties.Ldap ldaps(String truststorePath, String truststorePassword) {
        AdLdapProperties.Ldap settings = LdapAdDirectoryClientTest.settings(
                "ldaps://localhost:" + server.getListenPort(), PASSWORD, 500);
        settings.setTruststorePath(truststorePath);
        settings.setTruststorePassword(truststorePassword);
        return settings;
    }

    @Test
    void withTheTruststoreTheLdapsReadWorksAndTheJvmTrustIsUnchanged() throws Exception {
        String truststoreProperty = System.getProperty("javax.net.ssl.trustStore");
        SSLContext defaultContext = SSLContext.getDefault();
        SSLSocketFactory defaultHttps = HttpsURLConnection.getDefaultSSLSocketFactory();

        List<AdRecord> records = LdapConfig.client(ldaps(truststore.toString(), STORE_PASSWORD)).fetchAll();

        assertThat(records).hasSize(14);
        assertThat(System.getProperty("javax.net.ssl.trustStore")).isEqualTo(truststoreProperty);
        assertThat(System.getProperty("javax.net.ssl.trustStorePassword")).isNull();
        assertThat(SSLContext.getDefault()).isSameAs(defaultContext);
        assertThat(HttpsURLConnection.getDefaultSSLSocketFactory()).isSameAs(defaultHttps);
    }

    @Test
    void otherTlsConnectionsStillDoNotTrustTheLdapCertificate() throws Exception {
        LdapConfig.client(ldaps(truststore.toString(), STORE_PASSWORD)).fetchAll();

        try (SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault()
                .createSocket("localhost", server.getListenPort())) {
            assertThatThrownBy(socket::startHandshake).isInstanceOf(SSLHandshakeException.class);
        }
    }

    @Test
    void withoutTheTruststoreTheCertificateIsNotTrusted() {
        assertThatThrownBy(() -> LdapConfig.client(ldaps("", "")).fetchAll())
                .isInstanceOfSatisfying(AdDirectoryException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(AdErrorCode.TLS);
                    assertThat(e.getMessage()).contains("truststore missing or wrong", "section 8.1");
                });
    }

    @Test
    void unreadableTruststoreIsTlsAndNamesThePath() {
        String missing = dir.resolve("no-such.jks").toString();

        assertThatThrownBy(() -> LdapConfig.client(ldaps(missing, STORE_PASSWORD)).fetchAll())
                .isInstanceOfSatisfying(AdDirectoryException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(AdErrorCode.TLS);
                    assertThat(e.getMessage()).contains("AD_LDAP_TRUSTSTORE_PATH", missing);
                });
    }

    @Test
    void wrongTruststorePasswordIsTls() {
        assertThatThrownBy(() -> LdapConfig.client(ldaps(truststore.toString(), "wrong")).fetchAll())
                .isInstanceOfSatisfying(AdDirectoryException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(AdErrorCode.TLS);
                    assertThat(e.getMessage()).contains("AD_LDAP_TRUSTSTORE_PASSWORD").doesNotContain("wrong");
                });
    }

    private static void keytool(String... args) throws Exception {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", windows ? "keytool.exe" : "keytool").toString());
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as("keytool %s: %s", args[0], output).isZero();
    }
}
