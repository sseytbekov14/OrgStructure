package cs_orgchart.employee;

import cs_orgchart.ad.mapper.FunctionResolver;
import cs_orgchart.department.SpecDepartmentDictionaries;
import cs_orgchart.employee.EmployeeSourceProperties.ParentSource;
import cs_orgchart.photo.PhotoProperties;
import cs_orgchart.photo.PhotoProperties.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The photoUrl chain of the directory: the stored AD photo, the folder photo, the default (initials), in the order of
 * PHOTO_SOURCE_PRIORITY, for every combination; the folder-only links (Excel mode, AD photos off) are exactly
 * {@link EmployeePhotos#urlFor}.
 */
class PhotoLinksTest {

    private static final String HASH = "0123456789abcdef".repeat(4);
    private static final Map<String, String> FOLDER = Map.of("ff@kz.example.jpg", "ff@kz.example.jpg");
    private static final Map<Long, String> AD = Map.of(7L, HASH);

    private static List<Source> priority(String value) {
        PhotoProperties properties = new PhotoProperties();
        properties.setSourcePriority(value);
        return properties.priority();
    }

    @ParameterizedTest(name = "{0}: AD photo {1}, folder photo {2} -> {3}")
    @CsvSource({
            "'ad,folder', true,  true,  AD",
            "'ad,folder', true,  false, AD",
            "'ad,folder', false, true,  FOLDER",
            "'ad,folder', false, false, DEFAULT",
            "'folder,ad', true,  true,  FOLDER",
            "'folder,ad', true,  false, AD",
            "'folder,ad', false, true,  FOLDER",
            "'folder,ad', false, false, DEFAULT",
            "'ad',        true,  true,  AD",
            "'ad',        false, true,  DEFAULT",
            "'folder',    true,  true,  FOLDER",
            "'folder',    true,  false, DEFAULT"})
    void theChain(String order, boolean adPhoto, boolean folderPhoto, String expected) {
        PhotoLinks links = PhotoLinks.of(folderPhoto ? FOLDER : Map.of(), adPhoto ? AD : Map.of(), priority(order));
        String url = links.urlFor(7L, "ff@kz.example");
        switch (expected) {
            case "AD" -> assertThat(url).isEqualTo("/api/photos/7?v=01234567");
            case "FOLDER" -> assertThat(url).isEqualTo("/photos/ff@kz.example.jpg");
            default -> assertThat(url).isEqualTo(EmployeePhotos.DEFAULT);
        }
        assertThat(links.isUploaded(7L)).isFalse();
    }

    @ParameterizedTest(name = "{0}: an uploaded photo wins")
    @CsvSource({"'ad,folder'", "'folder,ad'", "'ad'", "'folder'"})
    void anUploadedPhotoWinsWhateverThePriority(String order) {
        String uploaded = "fedcba9876543210".repeat(4);
        PhotoLinks links = PhotoLinks.of(FOLDER, AD, Map.of(7L, uploaded), priority(order));

        assertThat(links.urlFor(7L, "ff@kz.example")).isEqualTo("/api/photos/7?v=fedcba98");
        assertThat(links.isUploaded(7L)).isTrue();
        assertThat(links.isUploaded(8L)).isFalse();
        assertThat(links.isUploaded(null)).isFalse();
    }

    @Test
    void folderOnlyIsExactlyTheFolderLookupOfBefore() {
        Map<String, String> files = Map.of("ff@kz.example.jpg", "ff@kz.example.jpg", "gg.png", "GG.png");
        for (String email : new String[]{"ff@kz.example", "FF@KZ.example", "gg@kz.example", "none@kz.example", "", null}) {
            assertThat(PhotoLinks.folderOnly(files).urlFor(1L, email)).as(email)
                    .isEqualTo(EmployeePhotos.urlFor(email, files));
        }
        assertThat(PhotoLinks.folderOnly(files).urlFor(7L, "x@kz.example")).isEqualTo(EmployeePhotos.DEFAULT);
    }

    @Test
    void anotherEmployeesHashIsNeverUsed() {
        PhotoLinks links = PhotoLinks.of(Map.of(), AD, priority("ad,folder"));
        assertThat(links.urlFor(8L, "x@kz.example")).isEqualTo(EmployeePhotos.DEFAULT);
        assertThat(links.urlFor(null, "x@kz.example")).isEqualTo(EmployeePhotos.DEFAULT);
    }

    @Test
    void wrongPrioritiesStopTheStart() {
        assertThat(priority(" Folder , AD ")).containsExactly(Source.FOLDER, Source.AD);
        assertThatThrownBy(() -> priority("ad,camera")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PHOTO_SOURCE_PRIORITY");
        assertThatThrownBy(() -> priority("ad,ad")).hasMessageContaining("twice");
        assertThatThrownBy(() -> priority(" , ")).hasMessageContaining("empty");
    }

    @Test
    void theAssemblerUsesTheLinksForEveryRow() {
        EmployeeDtoAssembler assembler = new EmployeeDtoAssembler(new FunctionResolver(SpecDepartmentDictionaries.INSTANCE),
                ParentSource.PM);
        Employee withAd = person(7L, "ad");
        Employee withFolder = person(8L, "ff");
        Employee without = person(9L, "none");
        List<cs_orgchart.model.Employee> rows = assembler.assemble(List.of(withAd, withFolder, without), List.of(),
                List.of(), Set.of(), PhotoLinks.of(Map.of("ff@kz.example.jpg", "ff@kz.example.jpg"), AD,
                        priority("ad,folder")));
        Map<Long, String> urls = new java.util.HashMap<>();
        rows.forEach(r -> urls.put(r.getId(), r.getPhotoUrl()));
        assertThat(urls).containsEntry(7L, "/api/photos/7?v=01234567")
                .containsEntry(8L, "/photos/ff@kz.example.jpg")
                .containsEntry(9L, EmployeePhotos.DEFAULT);
    }

    private static Employee person(long id, String login) {
        return Employee.builder().id(id).adGuid(UUID.randomUUID()).dn("CN=" + login).login(login)
                .upn(login + "@kz.example").email(login + "@kz.example").displayName("Person, " + login)
                .jobTitle("Specialist").deptLevel1("Central Services").deptLevel2("QRM").deptLevel3("QRM General")
                .countryCode("KZ")
                .enabled(true).lastSyncedAt(Instant.now()).createdAt(Instant.now()).updatedAt(Instant.now()).build();
    }
}
