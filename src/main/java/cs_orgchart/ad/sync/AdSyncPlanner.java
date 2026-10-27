package cs_orgchart.ad.sync;

import cs_orgchart.ad.client.AdDirectoryClient;
import cs_orgchart.ad.mapper.AdEmployeeMapper;
import cs_orgchart.ad.mapper.AdEmployeeMapper.Mapped;
import cs_orgchart.ad.mapper.DepartmentNormalizer;
import cs_orgchart.ad.mapper.FunctionResolver;
import cs_orgchart.ad.mapper.NormalizedDepartment;
import cs_orgchart.ad.model.AdEmployee;
import cs_orgchart.ad.model.AdRecord;
import cs_orgchart.ad.sync.SyncPlan.ManagerMatch;
import cs_orgchart.ad.sync.SyncPlan.Member;
import cs_orgchart.ad.sync.SyncPlan.PmMatch;
import cs_orgchart.department.JpaDepartmentDictionaries;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Works out a {@link SyncPlan} (spec v3 §7 steps 3–4 and the resolution of 6.2–6.4) without writing anything:
 * fetch, validation with skips (AdEmployeeMapper), department normalization, site function, line manager by DN,
 * performance manager by UPN → e-mail → unique local part of the e-mail, and the managers/PMs outside the fetched
 * set looked up in AD (one level, no recursion). A null manager DN or PM UPN is no link, not an unresolved one.
 */
@Slf4j
public class AdSyncPlanner {

    private final AdDirectoryClient directory;
    private final AdEmployeeMapper mapper;
    private final DepartmentNormalizer normalizer;
    private final FunctionResolver functions;
    private final JpaDepartmentDictionaries dictionaries;

    public AdSyncPlanner(AdDirectoryClient directory, AdEmployeeMapper mapper, DepartmentNormalizer normalizer,
                         FunctionResolver functions, JpaDepartmentDictionaries dictionaries) {
        this.directory = directory;
        this.mapper = mapper;
        this.normalizer = normalizer;
        this.functions = functions;
        this.dictionaries = dictionaries;
    }

    /**
     * @throws cs_orgchart.ad.client.AdDirectoryException when AD cannot be read
     */
    public SyncPlan plan() {
        dictionaries.reload();
        List<AdRecord> records = directory.fetchAll();

        AdEmployeeMapper.Run validation = mapper.newRun();
        List<AdEmployee> valid = new ArrayList<>();
        Map<UUID, AdEmployee> byGuid = new HashMap<>();
        for (AdRecord record : records) {
            if (validation.map(record) instanceof Mapped mapped) {
                AdEmployee employee = mapped.employee();
                if (byGuid.putIfAbsent(employee.adGuid(), employee) == null) {
                    valid.add(employee);
                } else {
                    log.warn("AD sync: objectGUID {} returned twice, the second entry is ignored", employee.adGuid());
                }
            }
        }
        validation.finish();

        DepartmentNormalizer.Run departments = normalizer.newRun();
        FunctionResolver.Run siteFunctions = functions.newRun();
        Resolver resolver = new Resolver(valid);
        List<Member> members = new ArrayList<>();
        for (AdEmployee employee : valid) {
            NormalizedDepartment department = departments.normalize(employee.department());
            String cs = siteFunctions.resolve(department).csName();
            Resolver.Link manager = resolver.manager(employee);
            Resolver.Link pm = resolver.pm(employee);
            members.add(new Member(employee, department, cs, manager.guid(), ManagerMatch.valueOf(manager.how()),
                    pm.guid(), PmMatch.valueOf(pm.how())));
        }
        resolver.externalValidation.finish();

        return new SyncPlan(records.size(), validation.skipped(), List.copyOf(members),
                List.copyOf(resolver.externals.values()), resolver.externalValidation.skippedCount(),
                departments.unmappedSegments(), siteFunctions.unmappedFunctions());
    }

    /** Manager and PM resolution over the fetched employees, then AD for the ones outside (cached per run) */
    private final class Resolver {

        record Link(UUID guid, String how) {
            static final Link NONE = new Link(null, "NONE");
            static final Link UNRESOLVED = new Link(null, "UNRESOLVED");
        }

        private final Map<String, AdEmployee> byDn = new HashMap<>();
        private final Map<String, AdEmployee> byUpn = new HashMap<>();
        private final Map<String, AdEmployee> byEmail = new HashMap<>();
        private final Map<String, List<AdEmployee>> byLocalPart = new HashMap<>();

        private final Map<UUID, AdEmployee> externals = new LinkedHashMap<>();
        private final Map<String, AdEmployee> externalsByUpn = new HashMap<>();
        private final Map<String, AdEmployee> externalsByEmail = new HashMap<>();
        private final Map<String, Optional<AdEmployee>> lookedUp = new HashMap<>();
        private final AdEmployeeMapper.Run externalValidation = mapper.newRun();

        Resolver(List<AdEmployee> employees) {
            for (AdEmployee e : employees) {
                byDn.putIfAbsent(lower(e.dn()), e);
                byUpn.putIfAbsent(lower(e.upn()), e);
                if (e.email() != null) {
                    byEmail.putIfAbsent(lower(e.email()), e);
                    byLocalPart.computeIfAbsent(localPart(e.email()), k -> new ArrayList<>()).add(e);
                }
            }
        }

        Link manager(AdEmployee employee) {
            if (employee.managerDn() == null) {
                return Link.NONE;
            }
            AdEmployee fetched = byDn.get(lower(employee.managerDn()));
            if (fetched != null) {
                return new Link(fetched.adGuid(), "FETCHED");
            }
            return external("dn:" + lower(employee.managerDn()), directory::findByDn, employee.managerDn())
                    .map(e -> new Link(e.adGuid(), "EXTERNAL"))
                    .orElse(Link.UNRESOLVED);
        }

        Link pm(AdEmployee employee) {
            String pmUpn = employee.pmUpn();
            if (pmUpn == null) {
                return Link.NONE;
            }
            String key = lower(pmUpn);
            AdEmployee byUpnMatch = byUpn.get(key);
            if (byUpnMatch != null) {
                return new Link(byUpnMatch.adGuid(), "UPN");
            }
            AdEmployee byEmailMatch = byEmail.get(key);
            if (byEmailMatch != null) {
                return new Link(byEmailMatch.adGuid(), "EMAIL");
            }
            List<AdEmployee> sameLocalPart = byLocalPart.getOrDefault(localPart(pmUpn), List.of());
            if (sameLocalPart.size() == 1) {
                log.info("AD sync: PM of objectGUID {} found only by the local part of the e-mail", employee.adGuid());
                return new Link(sameLocalPart.get(0).adGuid(), "LOCAL_PART");
            }
            AdEmployee known = externalsByUpn.getOrDefault(key, externalsByEmail.get(key));
            if (known != null) {
                return new Link(known.adGuid(), "EXTERNAL");
            }
            return external("upn:" + key, directory::findByUpn, pmUpn)
                    .map(e -> new Link(e.adGuid(), "EXTERNAL"))
                    .orElse(Link.UNRESOLVED);
        }

        /** A manager/PM looked up in AD once per run; invalid entries are skipped (logged, link left empty) */
        private Optional<AdEmployee> external(String cacheKey, Function<String, Optional<AdRecord>> lookup,
                                              String value) {
            return lookedUp.computeIfAbsent(cacheKey, k -> lookup.apply(value)
                    .map(externalValidation::mapExternal)
                    .flatMap(result -> result instanceof Mapped m ? Optional.of(m.employee()) : Optional.empty())
                    .map(found -> {
                        AdEmployee fetched = byDn.get(lower(found.dn()));
                        if (fetched != null) {
                            return fetched;
                        }
                        externals.putIfAbsent(found.adGuid(), found);
                        externalsByUpn.putIfAbsent(lower(found.upn()), found);
                        if (found.email() != null) {
                            externalsByEmail.putIfAbsent(lower(found.email()), found);
                        }
                        return externals.get(found.adGuid());
                    }));
        }

        private static String localPart(String address) {
            String lower = lower(address);
            int at = lower.indexOf('@');
            return at < 0 ? lower : lower.substring(0, at);
        }

        private static String lower(String value) {
            return value.trim().toLowerCase(Locale.ROOT);
        }
    }
}
