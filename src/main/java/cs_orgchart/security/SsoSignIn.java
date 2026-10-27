package cs_orgchart.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.saml2.provider.service.authentication.OpenSaml4AuthenticationProvider;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal;
import org.springframework.security.saml2.provider.service.authentication.Saml2Authentication;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The OrgChart side of the SAML sign-in. Spring Security checks the answer of Microsoft (signature, audience,
 * recipient, time, the request it answers); then {@link SsoAdmission} decides whether the person gets in.
 * In: the page that was asked for. Out: the static page "Access is not granted" for a refusal, "Sign-in did not
 * complete" for an answer Spring refused. The log says why with the NAMES of the attributes Microsoft sent, never
 * their values or an address.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "auth.sso.enabled", havingValue = "true")
public class SsoSignIn {

    public static final String ACCESS_NOT_GRANTED_PATH = "/access-not-granted.html";
    public static final String SIGN_IN_FAILED_PATH = "/sign-in-failed.html";
    public static final String SIGNED_OUT_PATH = "/signed-out.html";

    private static final Pattern ADDRESS = Pattern.compile("[^\\s\\[\\]<>\"',;:]+@[^\\s\\[\\]<>\"',;:]+");

    private final SsoAdmission admission;
    private final boolean keepClaims;
    private final Converter<OpenSaml4AuthenticationProvider.ResponseToken, Saml2Authentication> saml =
            OpenSaml4AuthenticationProvider.createDefaultResponseAuthenticationConverter();

    public SsoSignIn(SsoAdmission admission, @Value("${auth.sso.debug-endpoint:false}") boolean keepClaims) {
        this.admission = admission;
        this.keepClaims = keepClaims;
    }

    /** The SAML provider: Spring's checks of the answer, then OrgChart's admission. */
    public OpenSaml4AuthenticationProvider provider() {
        OpenSaml4AuthenticationProvider provider = new OpenSaml4AuthenticationProvider();
        provider.setResponseAuthenticationConverter(this::admit);
        return provider;
    }

    AbstractAuthenticationToken admit(OpenSaml4AuthenticationProvider.ResponseToken token) {
        Saml2Authentication answer = saml.convert(token);
        if (answer == null || !(answer.getPrincipal() instanceof Saml2AuthenticatedPrincipal person)) {
            log.warn("SSO sign-in refused: reason=NO_PRINCIPAL attributes=[]");
            throw new AccessNotGrantedException("NO_PRINCIPAL");
        }
        // Names only: the values (addresses, names, ids) stay out of the log
        TreeSet<String> attributes = new TreeSet<>(person.getAttributes().keySet());
        SsoIdentity identity = SsoIdentity.of(person);
        SsoAdmission.Decision decision = admission.decide(identity);
        if (!decision.admitted()) {
            log.warn("SSO sign-in refused: reason={} ({}) attributes={}", decision.refusal(),
                    decision.refusal().words(), attributes);
            throw new AccessNotGrantedException(decision.refusal().name());
        }
        log.info("SSO sign-in: roles={} attributes={}", decision.roles(), attributes);
        // The claims only for the person's own GET /me (SsoDebugController), kept with their sign-in
        return new SsoAuthenticationToken(decision.name(), decision.roles().stream()
                .<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList(),
                keepClaims ? SsoClaims.of(person) : null);
    }

    /** The page that was asked for (or the start page). */
    public AuthenticationSuccessHandler successHandler(RequestCache requestCache) {
        SavedRequestAwareAuthenticationSuccessHandler toPage = new SavedRequestAwareAuthenticationSuccessHandler();
        toPage.setRequestCache(requestCache);
        return toPage;
    }

    public AuthenticationFailureHandler failureHandler() {
        return this::failed;
    }

    private void failed(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        if (exception instanceof AccessNotGrantedException) {
            // Logged where it was decided, with the reason and the attribute names
            response.sendRedirect(request.getContextPath() + ACCESS_NOT_GRANTED_PATH);
            return;
        }
        String code = exception instanceof Saml2AuthenticationException samlException
                ? samlException.getSaml2Error().getErrorCode() : exception.getClass().getSimpleName();
        log.warn("SSO sign-in failed: {} {}", code, withoutAddresses(exception.getMessage()));
        response.sendRedirect(request.getContextPath() + SIGN_IN_FAILED_PATH);
    }

    /** Spring's reason on one line, with anything that looks like an e-mail address masked. */
    static String withoutAddresses(String text) {
        return text == null ? "" : ADDRESS.matcher(text.replaceAll("\\s+", " ").trim()).replaceAll("<address>");
    }
}
