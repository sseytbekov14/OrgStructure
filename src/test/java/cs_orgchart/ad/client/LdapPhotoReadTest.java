package cs_orgchart.ad.client;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldap.listener.interceptor.InMemoryInterceptedSearchRequest;
import com.unboundid.ldap.listener.interceptor.InMemoryOperationInterceptor;
import com.unboundid.ldap.sdk.Entry;
import cs_orgchart.ad.config.AdLdapProperties;
import cs_orgchart.ad.config.LdapConfig;
import cs_orgchart.ad.mapper.AdEmployeeMapper;
import cs_orgchart.ad.model.AdRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ldap.core.support.LdapContextSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static cs_orgchart.ad.client.LdapAdDirectoryClientTest.BASE;
import static cs_orgchart.ad.client.LdapAdDirectoryClientTest.BIND_DN;
import static cs_orgchart.ad.client.LdapAdDirectoryClientTest.PASSWORD;
import static cs_orgchart.ad.client.LdapAdDirectoryClientTest.ROOT;
import static cs_orgchart.ad.client.LdapAdDirectoryClientTest.guid;
import static cs_orgchart.ad.client.LdapAdDirectoryClientTest.user;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LdapAdDirectoryClient#readPhoto} against an in-memory LDAP server: the binary thumbnailPhoto comes back byte
 * for byte (and is corrupted without the java.naming.ldap.attributes.binary setting), a user without a photo, a DN that
 * no longer exists (fallback by objectGUID, escaped in the filter), and the paged search never asks for the photo.
 */
class LdapPhotoReadTest {

    private static InMemoryDirectoryServer server;
    private static final List<String[]> requestedAttributes = new CopyOnWriteArrayList<>();
    private static final List<String> filters = new CopyOnWriteArrayList<>();

    /** Bytes no text decoding survives: every value 0..255, also invalid UTF-8 sequences */
    private static final byte[] PHOTO = photoBytes();

    /** A GUID with bytes that must be escaped in a filter: ( ) * \ and NUL */
    private static final UUID TRICKY_GUID = UUID.fromString("28292a5c-0000-4000-8000-0000000000ff");

    private static byte[] photoBytes() {
        byte[] bytes = new byte[4096];
        for (int i = 0; i < 256; i++) {
            bytes[i] = (byte) i;
        }
        Random random = new Random(42);
        byte[] rest = new byte[bytes.length - 256];
        random.nextBytes(rest);
        System.arraycopy(rest, 0, bytes, 256, rest.length);
        return bytes;
    }

    @BeforeAll
    static void startServer() throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(ROOT);
        config.setSchema(null);
        config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("ldap", 0));
        config.addAdditionalBindCredentials(BIND_DN, PASSWORD);
        config.addInMemoryOperationInterceptor(new InMemoryOperationInterceptor() {
            @Override
            public void processSearchRequest(InMemoryInterceptedSearchRequest request) {
                // The root DSE query of the connection itself (empty base) is not one of the client's searches
                if (!request.getRequest().getBaseDN().isEmpty()) {
                    requestedAttributes.add(request.getRequest().getAttributeList().toArray(new String[0]));
                }
                filters.add(request.getRequest().getFilter().toString());
            }
        });
        server = new InMemoryDirectoryServer(config);
        server.startListening();
        LdapAdDirectoryClientTest.addDirectory(server);

        Entry withPhoto = user("photo", "KZ", "CS/ITS/ITS/ITS General", 512);
        withPhoto.addAttribute("thumbnailPhoto", PHOTO);
        server.add(withPhoto);
        // Renamed in AD after the last sync: the stored DN is gone, the objectGUID is the same
        Entry moved = user("moved", "KZ", "CS/ITS/ITS/ITS General", 512);
        moved.setDN("CN=moved-new-name,OU=UZ," + BASE);
        moved.setAttribute("distinguishedName", moved.getDN());
        moved.setAttribute("objectGUID", AdEmployeeMapper.guidBytes(TRICKY_GUID));
        moved.addAttribute("thumbnailPhoto", PHOTO);
        server.add(moved);
    }

    @AfterAll
    static void stopServer() {
        server.shutDown(true);
    }

    @BeforeEach
    void clear() {
        requestedAttributes.clear();
        filters.clear();
    }

    private static AdLdapProperties.Ldap settings() {
        AdLdapProperties.Ldap settings = LdapAdDirectoryClientTest.settings("ldap://localhost:" + server.getListenPort(),
                PASSWORD, 500);
        // The in-memory server has no extensible match (the real filter is tested in LdapAdDirectoryClientTest)
        settings.setFilter("(&(objectCategory=person)(objectClass=user))");
        return settings;
    }

    private static LdapAdDirectoryClient client() {
        return new LdapAdDirectoryClient(LdapConfig.contextSource(settings()), settings());
    }

    @Test
    void theBinaryPhotoComesBackByteForByte() {
        Optional<byte[]> photo = client().readPhoto("CN=photo,OU=KZ," + BASE, null);
        assertThat(photo).hasValueSatisfying(bytes -> assertThat(bytes).isEqualTo(PHOTO));
        // One read of that DN asking for the photo, none of the employee attributes
        // (JNDI adds objectClass and its java* attributes for the returned objects)
        assertThat(requestedAttributes).singleElement().satisfies(a -> assertThat(a).contains("thumbnailPhoto")
                .doesNotContain(AdEmployeeMapper.AD_ATTRIBUTES));
        assertThat(LdapConfig.PHOTO_BINARY_ATTRIBUTES.split(" ")).contains("objectGUID", "thumbnailPhoto");
    }

    @Test
    void thePhotoIsReadAsBinaryByTheConfiguredConnection() {
        // The connection names thumbnailPhoto as binary explicitly. The JDK LDAP provider also has it in its own default
        // list of binary attributes, so a connection without the setting reads it intact too: this guards against a
        // provider without that default, it cannot be shown failing with the JDK.
        AdLdapProperties.Ldap settings = settings();
        LdapContextSource source = new LdapContextSource();
        source.setUrl(settings.getUrl());
        source.setUserDn(settings.getBindDn());
        source.setPassword(settings.getBindPassword());
        source.setBaseEnvironmentProperties(Map.of("java.naming.ldap.attributes.binary", "objectGUID"));
        source.afterPropertiesSet();
        Optional<byte[]> photo = new LdapAdDirectoryClient(source, settings).readPhoto("CN=photo,OU=KZ," + BASE, null);
        assertThat(photo).hasValueSatisfying(bytes -> assertThat(bytes).isEqualTo(PHOTO));
        assertThat(LdapConfig.PHOTO_BINARY_ATTRIBUTES.split(" ")).containsExactly("objectGUID", "thumbnailPhoto");
    }

    @Test
    void aUserWithoutPhotoHasNone() {
        assertThat(client().readPhoto("CN=user00,OU=KZ," + BASE, guid("user00".hashCode() & 0xFFFF))).isEmpty();
    }

    @Test
    void aDnThatIsGoneFallsBackToTheObjectGuid() {
        Optional<byte[]> photo = client().readPhoto("CN=moved,OU=KZ," + BASE, TRICKY_GUID);
        assertThat(photo).hasValueSatisfying(bytes -> assertThat(bytes).isEqualTo(PHOTO));
        // The binary GUID went into the filter escaped byte by byte
        assertThat(filters).anySatisfy(f -> assertThat(f).contains("objectGUID=\\5c\\2a\\29\\28"));
    }

    @Test
    void neitherDnNorGuidFoundIsNoPhoto() {
        assertThat(client().readPhoto("CN=nobody,OU=KZ," + BASE, UUID.randomUUID())).isEmpty();
        assertThat(client().readPhoto(null, null)).isEmpty();
    }

    @Test
    void thePagedSearchNeverAsksForThePhoto() {
        List<AdRecord> records = client().fetchAll();
        assertThat(records).isNotEmpty().allSatisfy(r -> assertThat(r.get("thumbnailPhoto")).isNull());
        assertThat(requestedAttributes).isNotEmpty()
                .allSatisfy(a -> assertThat(a).doesNotContain("thumbnailPhoto"));
        assertThat(AdEmployeeMapper.AD_ATTRIBUTES).doesNotContain("thumbnailPhoto");
    }

    @Test
    void escapingABinaryValue() {
        assertThat(LdapAdDirectoryClient.escapeBinary(new byte[]{0x28, 0x29, 0x2a, 0x5c, 0x00, (byte) 0xff}))
                .isEqualTo("\\28\\29\\2a\\5c\\00\\ff");
    }
}
