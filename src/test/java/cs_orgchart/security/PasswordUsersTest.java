package cs_orgchart.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordUsersTest {

    private static final String HASH_A = "$2a$10$" + "A".repeat(53);
    private static final String HASH_B = "$2y$12$" + "./".repeat(26) + "x";

    @Test
    void parsesNamesAndHashes_skippingBlanks() {
        assertThat(PasswordUsers.parse(" admin : " + HASH_A + " ,, ops@corp.example:" + HASH_B + ","))
                .extracting(PasswordUsers.Account::username, PasswordUsers.Account::hash)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("admin", HASH_A),
                        org.assertj.core.groups.Tuple.tuple("ops@corp.example", HASH_B));
    }

    @Test
    void emptyIsNoAccounts() {
        assertThat(PasswordUsers.parse("")).isEmpty();
        assertThat(PasswordUsers.parse(null)).isEmpty();
    }

    @Test
    void anEntryWithoutAName_isRefused() {
        assertThatThrownBy(() -> PasswordUsers.parse(":" + HASH_A))
                .hasMessageContaining("entry 1 must be name:bcrypt-hash");
        assertThatThrownBy(() -> PasswordUsers.parse("admin:" + HASH_A + ",nocolon"))
                .hasMessageContaining("entry 2 must be name:bcrypt-hash");
    }

    @Test
    void aNameTwice_isRefused() {
        assertThatThrownBy(() -> PasswordUsers.parse("Admin:" + HASH_A + ",admin:" + HASH_B))
                .hasMessageContaining("\"admin\" is listed twice");
    }

    @Test
    void theHash_isNeverInTheMessages_norInToString() {
        assertThatThrownBy(() -> PasswordUsers.parse("admin:{noop}secret"))
                .hasMessageNotContaining("secret");
        assertThat(PasswordUsers.parse("admin:" + HASH_A).get(0).toString()).doesNotContain(HASH_A);
    }
}
