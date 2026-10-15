package cs_orgchart.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

/**
 * A sign-in with SSO, as it is kept in the session: a name to show and the roles. Not the SAML answer itself
 * (Spring's own token keeps the whole answer, with every claim, for the life of the session).
 */
public class SsoAuthenticationToken extends AbstractAuthenticationToken {

    private final String name;

    public SsoAuthenticationToken(String name, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.name = name;
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
}
