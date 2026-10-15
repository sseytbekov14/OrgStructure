package cs_orgchart.security;

import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal;

/**
 * Who Microsoft says signed in, from the SAML answer: the NameID and the claims for the e-mail address and the
 * user principal name (UPN). Values as sent, trimmed; null when absent.
 */
public record SsoIdentity(String nameId, String email, String upn) {

    public static final String EMAIL_CLAIM = "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/emailaddress";
    /** Entra's default claim "name" carries the UPN (user.userprincipalname). */
    public static final String NAME_CLAIM = "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/name";
    public static final String UPN_CLAIM = "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/upn";

    public SsoIdentity {
        nameId = clean(nameId);
        email = clean(email);
        upn = clean(upn);
    }

    public static SsoIdentity of(Saml2AuthenticatedPrincipal principal) {
        String upn = first(principal, UPN_CLAIM);
        return new SsoIdentity(principal.getName(), first(principal, EMAIL_CLAIM),
                upn != null ? upn : first(principal, NAME_CLAIM));
    }

    /** The e-mail claim, else the NameID when it is an address, else the UPN, else the NameID. */
    public String displayName() {
        if (email != null) {
            return email;
        }
        if (nameId != null && nameId.contains("@")) {
            return nameId;
        }
        return upn != null ? upn : nameId;
    }

    private static String first(Saml2AuthenticatedPrincipal principal, String claim) {
        Object value = principal.getFirstAttribute(claim);
        return value == null ? null : value.toString();
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
