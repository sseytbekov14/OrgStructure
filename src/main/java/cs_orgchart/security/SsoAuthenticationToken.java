package cs_orgchart.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

/**
 * A sign-in with SSO, as it is kept in the session: a name to show and the roles, plus what Microsoft sent only
 * while auth.sso.debug-endpoint is on (else null). Not the SAML answer itself (Spring's own token keeps the whole
 * answer, with every claim, for the life of the session).
 */
public class SsoAuthenticationToken extends AbstractAuthenticationToken {

    private final String name;
    private final SsoClaims claims;

    public SsoAuthenticationToken(String name, Collection<? extends GrantedAuthority> authorities, SsoClaims claims) {
        super(authorities);
        this.name = name;
        this.claims = claims;
        setAuthenticated(true);
    }

    @Override
    public Object getPrincipal() {
        return name;
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public String getName() {
        return name;
    }

    /** The NameID and claims of this sign-in, or null when the debug endpoint was off. */
    public SsoClaims claims() {
        return claims;
    }
}
