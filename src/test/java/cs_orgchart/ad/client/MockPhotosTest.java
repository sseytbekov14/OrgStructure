package cs_orgchart.ad.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import cs_orgchart.ad.model.AdRecord;
import cs_orgchart.photo.PhotoState;
import cs_orgchart.photo.PhotoValidator;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The synthetic photos of AD_SYNC_MODE=mock: about two thirds of the mock employees have one (made in code), some
 * have none, and the deliberately bad values are there; found by DN or by objectGUID, always the same value.
 */
class MockPhotosTest {

    private final MockAdDirectoryClient mock = new MockAdDirectoryClient(new ObjectMapper());
    private final PhotoValidator validator = new PhotoValidator(204_800, 1024);

    private Optional<byte[]> photo(AdRecord user) {
        return mock.readPhoto(Objects.toString(user.get("distinguishedName")), null);
    }

    @Test
    void aboutTwoThirdsHaveAPhotoAndAllStatesOccur() {
        List<AdRecord> employees = mock.fetchAll();
        Map<PhotoState, Integer> states = new EnumMap<>(PhotoState.class);
        for (AdRecord user : employees) {
            Optional<byte[]> value = photo(user);
            PhotoState state = value.isEmpty() ? PhotoState.NONE : validator.validate(value.get()).state();
            states.merge(state, 1, Integer::sum);
        }
        double withPhoto = states.getOrDefault(PhotoState.PRESENT, 0) / (double) employees.size();
        assertThat(withPhoto).isBetween(0.55, 0.75);
        assertThat(states.get(PhotoState.NONE)).isPositive();
        assertThat(states.get(PhotoState.INVALID)).isPositive();
    }

    @Test
    void everyBadKindIsGivenToSomeone() {
        List<AdRecord> all = mock.fetchAll();
        int bad = 0;
        for (int i = 0; i < 30; i++) {
            Optional<byte[]> value = MockPhotos.photoFor(i, "AB");
            if (i % 3 == 2 && i / 3 < MockPhotos.Bad.values().length) {
                assertThat(value).isPresent();
                bad++;
            }
        }
        assertThat(bad).isEqualTo(MockPhotos.Bad.values().length);
        assertThat(all.size()).isGreaterThan(3 * MockPhotos.Bad.values().length);
    }

    @Test
    void foundByDnOrByGuidAndAlwaysTheSame() {
        AdRecord user = mock.fetchAll().get(0);
        UUID guid = UUID.fromString(Objects.toString(user.get("objectGUID")));
        byte[] byDn = photo(user).orElseThrow();
        assertThat(mock.readPhoto("CN=gone,OU=KZ,DC=example", guid)).hasValueSatisfying(b -> assertThat(b).isEqualTo(byDn));
        assertThat(photo(user)).hasValueSatisfying(b -> assertThat(b).isEqualTo(byDn));
        assertThat(mock.readPhoto("CN=nobody", UUID.randomUUID())).isEmpty();
    }

    @Test
    void theSquaresAreSmallValidImages() {
        for (String format : List.of("jpeg", "png")) {
            byte[] square = MockPhotos.square(4, "KK", format);
            PhotoValidator.Result result = validator.validate(square);
            assertThat(result.valid()).as(format).isTrue();
            assertThat(result.width()).isEqualTo(96);
            assertThat(square.length).isLessThan(20_000);
        }
        assertThat(MockAdDirectoryClient.initials("Kilo, Khaki")).isEqualTo("KK");
        assertThat(MockAdDirectoryClient.initials(null)).isEqualTo("?");
    }
}
