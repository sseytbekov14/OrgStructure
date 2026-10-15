package cs_orgchart.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SsoAdmissionTest {

    private final RoleLists roles = new RoleLists("admin@corp.example", "hr@corp.example");

    @Test
    void withoutDomains_everyoneEntraLetsThrough_isUser_evenWithoutAttributes() {
        SsoAdmission admission = new SsoAdmission("", roles);
        SsoAdmission.Decision decision = admission.decide(new SsoIdentity("opaque-id", null, null));
        assertThat(decision.admitted()).isTrue();
        assertThat(decision.roles()).containsExactly("USER");
        assertThat(decision.name()).isEqualTo("opaque-id");
    }

    @Test
    void theDomain_comesFromTheEmail_elseFromTheUpn() {
        SsoAdmission admission = new SsoAdmission(" CORP.example ,other.example", roles);
        assertThat(admission.decide(new SsoIdentity(null, "a@Corp.Example", "a@partner.example")).admitted()).isTrue();
        assertThat(admission.decide(new SsoIdentity(null, "a@partner.example", "a@corp.example")).refusal())
                .isEqualTo(SsoAdmission.Reason.DOMAIN_NOT_ALLOWED);
        assertThat(admission.decide(new SsoIdentity(null, null, "b@other.example")).admitted()).isTrue();
    }

    @Test
    void withDomains_noAddress_isRefused() {
        SsoAdmission admission = new SsoAdmission("corp.example", roles);
        assertThat(admission.decide(new SsoIdentity("someone@corp.example", null, null)).refusal())
                .as("the NameID does not count").isEqualTo(SsoAdmission.Reason.NO_ADDRESS);
        assertThat(admission.decide(new SsoIdentity(null, "not-an-address", null)).refusal())
                .isEqualTo(SsoAdmission.Reason.NO_ADDRESS);
    }

    @Test
    void aSubdomain_isNotTheDomain() {
        SsoAdmission admission = new SsoAdmission("corp.example", roles);
        assertThat(admission.decide(new SsoIdentity(null, "a@evil.corp.example", null)).refusal())
                .isEqualTo(SsoAdmission.Reason.DOMAIN_NOT_ALLOWED);
    }

    @Test
    void theLists_matchTheEmailOrTheUpn() {
        SsoAdmission admission = new SsoAdmission("", roles);
        assertThat(admission.decide(new SsoIdentity(null, "x@corp.example", "ADMIN@corp.example")).roles())
                .containsExactly("USER", "SYSTEM_ADMIN");
        assertThat(admission.decide(new SsoIdentity("hr@corp.example", null, null)).roles())
                .as("not by the NameID").containsExactly("USER");
    }

    @Test
    void theName_isTheEmail_elseAnAddressNameId_elseTheUpn() {
        SsoAdmission admission = new SsoAdmission("", roles);
        assertThat(admission.decide(new SsoIdentity("n@corp.example", "e@corp.example", "u@corp.example")).name())
                .isEqualTo("e@corp.example");
        assertThat(admission.decide(new SsoIdentity("n@corp.example", null, "u@corp.example")).name())
                .isEqualTo("n@corp.example");
        assertThat(admission.decide(new SsoIdentity("opaque", " ", "u@corp.example")).name())
                .isEqualTo("u@corp.example");
    }
}
