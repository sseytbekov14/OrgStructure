package cs_orgchart.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Whether a person Microsoft signed in gets into OrgChart. Who may sign in at all is decided in Entra (the users
 * and groups assigned to the application); OrgChart lets in everyone Entra lets through, as USER (plus the roles
 * of {@link RoleLists}). Nobody is stored and nobody is created.
 *
 * <p>Optional: SAML_ALLOWED_EMAIL_DOMAINS (domains separated by commas, any case; empty: off) keeps out addresses
 * of other domains. The domain is the one of the e-mail claim, else of the UPN; no address at all is refused too.
 */
@Component
public class SsoAdmission {

    public enum Reason {
        NO_ADDRESS("the answer has neither an e-mail address nor a UPN"),
        DOMAIN_NOT_ALLOWED("the address is not on SAML_ALLOWED_EMAIL_DOMAINS");

        private final String words;

        Reason(String words) {
            this.words = words;
        }

        public String words() {
            return words;
        }
    }

    /** In (the name and roles) or out (why). */
    public record Decision(String name, List<String> roles, Reason refusal) {

        public boolean admitted() {
            return refusal == null;
        }
    }

    private final Set<String> allowedDomains;
    private final RoleLists roleLists;

    public SsoAdmission(@Value("${auth.sso.allowed-email-domains:}") String allowedDomains, RoleLists roleLists) {
        this.allowedDomains = RoleLists.list(allowedDomains);
        this.roleLists = roleLists;
    }

    public Decision decide(SsoIdentity identity) {
        if (!allowedDomains.isEmpty()) {
            String address = identity.email() != null ? identity.email() : identity.upn();
            int at = address == null ? -1 : address.lastIndexOf('@');
            if (at < 0) {
                return new Decision(null, List.of(), Reason.NO_ADDRESS);
            }
            if (!allowedDomains.contains(address.substring(at + 1).toLowerCase(Locale.ROOT))) {
                return new Decision(null, List.of(), Reason.DOMAIN_NOT_ALLOWED);
            }
        }
        return new Decision(identity.displayName(), roleLists.rolesOf(identity.email(), identity.upn()), null);
    }
}
