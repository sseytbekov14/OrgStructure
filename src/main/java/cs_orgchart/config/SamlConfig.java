package cs_orgchart.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.saml2.provider.service.registration.InMemoryRelyingPartyRegistrationRepository;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrations;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * The one SAML 2.0 relying party, registration id {@value #REGISTRATION_ID}: OrgChart as the Enterprise
 * application of Microsoft Entra ID. Everything comes from the environment, nothing has a default:
 * SAML_ENTITY_ID (the application's Identifier in Entra), SAML_ACS_URL (the Reply URL, {@value #ACS_PATH} on the
 * site address) and SAML_METADATA_URI (the App Federation Metadata Url of the Entra application, or a file: copy
 * of it). The metadata is read once at start: the start fails when it cannot be read. Made only with SSO on.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "auth.sso.enabled", havingValue = "true")
public class SamlConfig {

    public static final String REGISTRATION_ID = "entra";
    public static final String ACS_PATH = "/login/saml2/sso/" + REGISTRATION_ID;
    public static final String SIGN_ON_PATH = "/saml2/authenticate/" + REGISTRATION_ID;

    @Bean
    public RelyingPartyRegistrationRepository relyingPartyRegistrationRepository(
            @Value("${auth.sso.entity-id:}") String entityId,
            @Value("${auth.sso.acs-url:}") String acsUrl,
            @Value("${auth.sso.metadata-uri:}") String metadataUri) {
        List<String> problems = problems(entityId, acsUrl, metadataUri);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("SSO is on (AUTH_SSO_ENABLED=true) but " + String.join("; ", problems));
        }
        RelyingPartyRegistration registration;
        try {
            registration = RelyingPartyRegistrations.fromMetadataLocation(metadataUri.trim())
                    .registrationId(REGISTRATION_ID)
                    .entityId(entityId.trim())
                    .assertionConsumerServiceLocation(acsUrl.trim())
                    .build();
        } catch (RuntimeException | LinkageError ex) {
            throw new IllegalStateException("SSO is on but the Entra metadata of SAML_METADATA_URI cannot be read: "
                    + ex, ex);
        }
        URI acs = URI.create(acsUrl.trim());
        log.info("SSO with Microsoft Entra ID: Identifier (Entity ID) {}, Reply URL (ACS) {}, Sign on URL {}://{}{},"
                        + " identity provider {}", registration.getEntityId(),
                registration.getAssertionConsumerServiceLocation(), acs.getScheme(), acs.getRawAuthority(),
                SIGN_ON_PATH, registration.getAssertingPartyMetadata().getEntityId());
        return new InMemoryRelyingPartyRegistrationRepository(registration);
    }

    /** What is missing or wrong in the three SAML settings (empty: they can be used). */
    static List<String> problems(String entityId, String acsUrl, String metadataUri) {
        List<String> problems = new ArrayList<>();
        if (blank(entityId)) {
            problems.add("SAML_ENTITY_ID is not set (the Identifier of the Entra application)");
        } else if (entityId.contains("{")) {
            problems.add("SAML_ENTITY_ID must be the Identifier itself, not a template");
        }
        if (blank(acsUrl)) {
            problems.add("SAML_ACS_URL is not set (the Reply URL, https://<site>" + ACS_PATH + ")");
        } else {
            URI acs = uri(acsUrl.trim());
            if (acs == null || !acs.isAbsolute() || acs.getHost() == null) {
                problems.add("SAML_ACS_URL must be an absolute address, https://<site>" + ACS_PATH);
            } else if (!ACS_PATH.equals(acs.getPath())) {
                problems.add("SAML_ACS_URL must end with " + ACS_PATH + " (the address OrgChart reads the answer at)");
            }
        }
        if (blank(metadataUri)) {
            problems.add("SAML_METADATA_URI is not set (the App Federation Metadata Url of the Entra application)");
        }
        return problems;
    }

    private static URI uri(String value) {
        try {
            return URI.create(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
