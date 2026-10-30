package cs_orgchart.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RoleListsTest {

    private final RoleLists lists = new RoleLists(" Admin@Corp.example , ops@corp.example");

    @Test
    void everyone_isUser() {
        assertThat(lists.rolesOf("someone@corp.example")).containsExactly("USER");
        assertThat(lists.rolesOf((String) null)).containsExactly("USER");
    }

    @Test
    void theLists_matchAnyCase_andAnyOfTheNames() {
        assertThat(lists.rolesOf(null, "ADMIN@corp.example")).containsExactly("USER", "SYSTEM_ADMIN");
        assertThat(lists.rolesOf("x@corp.example", " OPS@corp.example ")).containsExactly("USER", "SYSTEM_ADMIN");
    }

    @Test
    void emptyLists_giveNobodyMore() {
        RoleLists empty = new RoleLists(" ");
        assertThat(empty.rolesOf("admin@corp.example")).containsExactly("USER");
    }

    @Test
    void authorities_haveTheRolePrefix() {
        assertThat(lists.authoritiesOf("ops@corp.example")).extracting(Object::toString)
                .containsExactly("ROLE_USER", "ROLE_SYSTEM_ADMIN");
    }
}
