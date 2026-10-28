package cs_orgchart.ad.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import cs_orgchart.ad.client.AdDirectoryClient;
import cs_orgchart.ad.client.MockAdDirectoryClient;
import cs_orgchart.ad.model.AdRecord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The directory of the sync tests: the mock fixture (mock/ad-employees.json) as it is, which a test can change,
 * extend or make fail. The lookups (findByDn / findByUpn) search the whole fixture, like AD.
 */
public class FakeDirectory implements AdDirectoryClient {

    private static final MockAdDirectoryClient MOCK = new MockAdDirectoryClient(new ObjectMapper());
    private static final List<String> ALL_LOGINS = List.of("rrust", "ppearl", "qquince", "copilot-agent01");

    private final List<AdRecord> fetched = new CopyOnWriteArrayList<>();
    private final List<AdRecord> lookups = new CopyOnWriteArrayList<>();
    private volatile RuntimeException failure;
    final AtomicInteger fetchCalls = new AtomicInteger();
    final AtomicInteger lookupCalls = new AtomicInteger();
    /** Photo reads: the logins read, in order; per-login photos and failures that replace the mock photos */
    public final List<String> photoReads = new CopyOnWriteArrayList<>();
    private final Map<String, Optional<byte[]>> photos = new ConcurrentHashMap<>();
    private final Map<String, RuntimeException> photoFailures = new ConcurrentHashMap<>();
    private volatile Runnable beforePhotoRead = () -> { };

    public FakeDirectory() {
        reset();
    }

    /** Back to the fixture: 43 fetched entries (41 valid, 2 skipped by the mapper), no failure */
    public void reset() {
        fetched.clear();
        fetched.addAll(MOCK.fetchAll());
        lookups.clear();
        lookups.addAll(fetched);
        for (String login : ALL_LOGINS) {
            MOCK.findByUpn(login + "@kz.example").ifPresent(lookups::add);
        }
        failure = null;
        photoReads.clear();
        photos.clear();
        photoFailures.clear();
        beforePhotoRead = () -> { };
        fetchCalls.set(0);
        lookupCalls.set(0);
    }

    public void failWith(RuntimeException failure) {
        this.failure = failure;
    }

    public void setFetched(List<AdRecord> records) {
        fetched.clear();
        fetched.addAll(records);
    }

    public List<AdRecord> fetched() {
        return List.copyOf(fetched);
    }

    /** The fetched entry of this login with one attribute changed (null removes it) */
    public void change(String login, String attribute, Object value) {
        AdRecord old = byLogin(login);
        Map<String, Object> attributes = new HashMap<>(old.attributes());
        if (value == null) {
            attributes.remove(attribute);
        } else {
            attributes.put(attribute, value);
        }
        AdRecord changed = new AdRecord(attributes);
        fetched.replaceAll(r -> r == old ? changed : r);
        lookups.replaceAll(r -> r == old ? changed : r);
    }

    /** The account left Central Services: no longer fetched, still found by a lookup */
    public void remove(String login) {
        fetched.remove(byLogin(login));
    }

    public void add(AdRecord record) {
        fetched.add(record);
        lookups.add(record);
    }

    public AdRecord byLogin(String login) {
        List<AdRecord> all = new ArrayList<>(fetched);
        all.addAll(lookups);
        return all.stream().filter(r -> login.equals(r.get("sAMAccountName"))).findFirst().orElseThrow();
    }

    @Override
    public List<AdRecord> fetchAll() {
        fetchCalls.incrementAndGet();
        if (failure != null) {
            throw failure;
        }
        return List.copyOf(fetched);
    }

    @Override
    public Optional<AdRecord> findByDn(String dn) {
        return find("distinguishedName", dn);
    }

    @Override
    public Optional<AdRecord> findByUpn(String upn) {
        return find("userPrincipalName", upn);
    }

    private Optional<AdRecord> find(String attribute, String value) {
        lookupCalls.incrementAndGet();
        if (failure != null) {
            throw failure;
        }
        return lookups.stream()
                .filter(r -> value != null && value.trim().equalsIgnoreCase(Objects.toString(r.get(attribute), null)))
                .findFirst();
    }

    /** The photo AD returns for this login from now on (null: no photo) */
    public void setPhoto(String login, byte[] bytes) {
        photos.put(login, Optional.ofNullable(bytes));
    }

    /** Reading this login's photo fails (null: no longer) */
    public void failPhoto(String login, RuntimeException failure) {
        if (failure == null) {
            photoFailures.remove(login);
        } else {
            photoFailures.put(login, failure);
        }
    }

    /** Runs before every photo read (e.g. to move a test clock) */
    public void beforePhotoRead(Runnable hook) {
        beforePhotoRead = hook;
    }

    @Override
    public Optional<byte[]> readPhoto(String dn, UUID adGuid) {
        beforePhotoRead.run();
        List<AdRecord> all = new ArrayList<>(fetched);
        all.addAll(lookups);
        AdRecord user = all.stream()
                .filter(r -> dn != null && dn.equalsIgnoreCase(Objects.toString(r.get("distinguishedName"), null)))
                .findFirst().orElse(null);
        String login = user == null ? null : Objects.toString(user.get("sAMAccountName"), null);
        photoReads.add(String.valueOf(login));
        if (failure != null) {
            throw failure;
        }
        if (login != null && photoFailures.containsKey(login)) {
            throw photoFailures.get(login);
        }
        if (login != null && photos.containsKey(login)) {
            return photos.get(login).map(byte[]::clone);
        }
        return MOCK.readPhoto(dn, adGuid);
    }
}
