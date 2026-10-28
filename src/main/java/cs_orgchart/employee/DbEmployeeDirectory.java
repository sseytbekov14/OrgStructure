package cs_orgchart.employee;

import cs_orgchart.ad.mapper.FunctionResolver;
import cs_orgchart.ad.sync.EmployeesSynced;
import cs_orgchart.department.DepartmentAliasRepository;
import cs_orgchart.department.DepartmentFunctionMapRepository;
import cs_orgchart.department.DepartmentGeneralNodeRepository;
import cs_orgchart.department.JpaDepartmentDictionaries;
import cs_orgchart.employee.group.FunctionGroupRepository;
import cs_orgchart.employee.override.EmployeeOverrideRepository;
import cs_orgchart.employee.visibility.EmployeeVisibilityRepository;
import cs_orgchart.employee.override.OverlayChanged;
import cs_orgchart.model.Employee;
import cs_orgchart.photo.EmployeePhotoInfoRepository;
import cs_orgchart.photo.PhotoProperties;
import cs_orgchart.photo.PhotoState;
import cs_orgchart.photo.PhotosChanged;
import cs_orgchart.service.OrgStreamService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * EMPLOYEE_SOURCE=db: the {@link EmployeeDirectory} from the employees and the overlay in the database (spec v3
 * §10.1). Built at startup (it needs no AD), again after every AD sync that changed the employees and, through
 * {@link #rebuild()}, after admin edits. An empty database gives an empty site: the way back is
 * EMPLOYEE_SOURCE=excel, never a silent fallback.
 */
@Slf4j
public class DbEmployeeDirectory {

    private final EmployeeRepository employees;
    private final EmployeeOverrideRepository overlay;
    private final EmployeePhotos photos;
    private final EmployeeDirectory directory;
    private final OrgStreamService orgStream;
    private final TransactionTemplate readOnly;
    private final JpaDepartmentDictionaries dictionaries;
    private final EmployeeDtoAssembler assembler;
    private final FunctionGroupRepository groups;
    private final EmployeeVisibilityRepository visibility;
    private final PhotoProperties photoSettings;
    private final EmployeePhotoInfoRepository adPhotos;

    public DbEmployeeDirectory(EmployeeRepository employees, EmployeeOverrideRepository overlay,
                               DepartmentAliasRepository aliases, DepartmentGeneralNodeRepository generalNodes,
                               DepartmentFunctionMapRepository functionMap, EmployeePhotos photos,
                               EmployeeDirectory directory, OrgStreamService orgStream,
                               PlatformTransactionManager transactions, EmployeeSourceProperties.ParentSource parentSource,
                               FunctionGroupRepository groups, EmployeeVisibilityRepository visibility,
                               PhotoProperties photoSettings, EmployeePhotoInfoRepository adPhotos) {
        this.photoSettings = photoSettings;
        this.adPhotos = adPhotos;
        // A wrong PHOTO_SOURCE_PRIORITY stops the start here
        photoSettings.priority();
        this.groups = groups;
        this.visibility = visibility;
        this.employees = employees;
        this.overlay = overlay;
        this.photos = photos;
        this.directory = directory;
        this.orgStream = orgStream;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
        // Own transaction: after a commit (admin edit) the finished one is still bound to the thread
        this.readOnly.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // Own dictionaries (not the AD sync's beans): db mode also works with AD_SYNC_MODE empty
        this.dictionaries = new JpaDepartmentDictionaries(aliases, generalNodes, functionMap);
        this.assembler = new EmployeeDtoAssembler(new FunctionResolver(dictionaries), parentSource);
    }

    @PostConstruct
    void buildAtStartup() {
        try {
            load();
        } catch (RuntimeException e) {
            log.error("EMPLOYEE_SOURCE=db: the employee directory could not be built from the database", e);
        }
    }

    /** The directory again from the database, then org-updated to the open pages (for admin edits) */
    public void rebuild() {
        load();
        orgStream.publishOrgUpdated("employee-directory");
    }

    /** After the mock overlay seeder (highest precedence), so that a first mock sync shows the seeded overlay */
    @EventListener
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void onEmployeesSynced(EmployeesSynced synced) {
        load();
        orgStream.publishOrgUpdated("ad-sync");
    }

    /** An admin edit: rebuilt once its transaction has committed, so org-updated never announces uncommitted data */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOverlayChanged(OverlayChanged change) {
        rebuild();
    }

    /** The photo phase stored, replaced or removed photos: new URLs (their hash) for the pages */
    @EventListener
    public void onPhotosChanged(PhotosChanged change) {
        rebuild();
    }

    /**
     * The photo URLs of this build: with AD_PHOTOS_ENABLED the hashes of the stored photos (one query, never the
     * bytes) in the PHOTO_SOURCE_PRIORITY order; otherwise the folder only, as before
     */
    private PhotoLinks photoLinks() {
        if (!photoSettings.isAdEnabled()) {
            return PhotoLinks.folderOnly(photos.files());
        }
        Map<Long, String> hashes = new HashMap<>();
        adPhotos.findByState(PhotoState.PRESENT).forEach(p -> hashes.put(p.getEmployeeId(), p.getSha256()));
        return PhotoLinks.of(photos.files(), hashes, photoSettings.priority());
    }

    /** Where AD alone puts the person (function and default group), with the dictionaries of the last build */
    public synchronized EmployeeDtoAssembler.AdPlacement adPlacement(cs_orgchart.employee.Employee employee) {
        return assembler.adPlacement(employee);
    }

    /**
     * The directory from the database, without telling the pages (startup, photo changes)
     *
     * @return the number of rows of the directory
     */
    public synchronized int load() {
        List<Employee> assembled = readOnly.execute(status -> {
            dictionaries.reload();
            return assembler.assemble(employees.findAll(), overlay.findAll(), groups.findAll(),
                    new HashSet<>(visibility.findHiddenEmployeeIds()), photoLinks());
        });
        List<Employee> rows = assembled == null ? List.of() : assembled;
        directory.replace(rows);
        if (rows.isEmpty()) {
            log.warn("EMPLOYEE_SOURCE=db but the database has no enabled employee: the site shows nobody. Run an"
                    + " AD sync, or set EMPLOYEE_SOURCE=excel to show the Excel file again");
        } else {
            log.info("Employee directory built from the database: {} rows in {} functions", rows.size(),
                    directory.getFunctions().size());
        }
        return rows.size();
    }
}
