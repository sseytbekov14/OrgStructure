// Shared by index.html and function.html: escaping, avatars, employee lookups,
// the employee profile dialog and reactions.

const DEFAULT_PHOTO = "/photos/default.jpg";
const MY_REACTIONS_KEY = "orgchart-my-reactions";
const REPORTS_PREVIEW = 8;

const ICONS = {
  mail: '<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><rect x="3" y="5" width="18" height="14" rx="2"/><path d="m3 7 9 6 9-6"/></svg>',
  copy: '<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><rect x="9" y="9" width="12" height="12" rx="2"/><path d="M5 15V5a2 2 0 0 1 2-2h10"/></svg>',
  target: '<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><circle cx="12" cy="12" r="8"/><circle cx="12" cy="12" r="3"/></svg>',
  chevronRight: '<svg class="person-row-chevron" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.2" aria-hidden="true"><path d="m9 6 6 6-6 6"/></svg>',
  close: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.2" aria-hidden="true"><path d="M18 6 6 18M6 6l12 12"/></svg>',
  arrowLeft: '<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2.2" aria-hidden="true"><path d="M19 12H5m6-6-6 6 6 6"/></svg>',
  check: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.2" aria-hidden="true"><path d="M20 6 9 17l-5-5"/></svg>',
  star: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="m12 2 3.1 6.3 6.9 1-5 4.9 1.2 6.8L12 17.8 5.8 21l1.2-6.8-5-4.9 6.9-1z"/></svg>'
};

function escapeHtml(value) {
  return String(value ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

function normalizeKey(value) {
  return String(value ?? "").trim().toLowerCase();
}

function getInitials(name) {
  const parts = String(name ?? "").replace(",", " ").trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return "?";
  if (parts.length === 1) return parts[0][0].toUpperCase();
  return (parts[0][0] + parts[1][0]).toUpperCase();
}

// Wraps every occurrence of each query word in <mark>. Returns escaped HTML.
function highlightText(text, query) {
  const source = String(text ?? "");
  const words = String(query ?? "").trim().split(/\s+/).filter((word) => word.length >= 2);
  if (!source || words.length === 0) return escapeHtml(source);

  const pattern = new RegExp(`(${words.map((w) => w.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")).join("|")})`, "gi");
  return source
    .split(pattern)
    .map((part, index) => (index % 2 === 1 ? `<mark>${escapeHtml(part)}</mark>` : escapeHtml(part)))
    .join("");
}

/** CSS pixels of each avatar size (the img gets them as width/height: no layout shift; at most 96, a 96 px AD photo is never upscaled) */
const AVATAR_SIZES = { sm: 30, md: 40, lg: 60, xl: 88 };
const AVATAR_TONES = 6;

/** The same soft background for the same name on every page (0..5, --color-avatar-N) */
function avatarTone(name) {
  let hash = 0;
  for (const ch of String(name ?? "")) hash = (hash * 31 + ch.codePointAt(0)) >>> 0;
  return hash % AVATAR_TONES;
}

/**
 * The one avatar of a person on every page (tree, groups, search, profile, counselee lists): the photo (an AD photo
 * from /api/photos or a folder photo) as a round, lazily loaded image of a fixed size, else the initials on the
 * person's tone. A photo that fails to load becomes the initials (avatarFallback, a listener: no inline handler).
 * options.label: the photo is the only identification of the person, so its alt is the name; otherwise the name is
 * shown next to it and the avatar is decorative (empty alt, aria-hidden).
 */
function renderAvatar(person, size = "md", { label = false } = {}) {
  const name = person?.name || "";
  const initials = escapeHtml(getInitials(name));
  const pixels = AVATAR_SIZES[size] || AVATAR_SIZES.md;
  const classes = `avatar avatar-${size} avatar-tone-${avatarTone(name)}`;
  const hidden = label ? `role="img" aria-label="${escapeHtml(name)}"` : 'aria-hidden="true"';
  const photo = person?.photoUrl;
  if (photo && photo !== DEFAULT_PHOTO) {
    return `<span class="${classes}" data-initials="${initials}"${label ? "" : ' aria-hidden="true"'}><img src="${escapeHtml(photo)}" alt="${label ? escapeHtml(name) : ""}" width="${pixels}" height="${pixels}" loading="lazy" decoding="async" /></span>`;
  }
  return `<span class="${classes}" ${hidden}>${initials}</span>`;
}

/* ---------- Employee cards (U6): the same facts on every card ---------- */

/** "City, Country" of a person (from AD), or "" */
function personLocation(person) {
  return [person?.city, person?.country].filter(Boolean).join(", ");
}

/**
 * The location line and the counselee count of a card in a list (tree, groups, search): one line each, the full
 * text as a tooltip. Nothing when there is nothing to show.
 */
function renderCardFacts(person, { counselees = true } = {}) {
  const location = personLocation(person);
  const count = counselees ? findDirectReports(person).length : 0;
  if (!location && !count) return "";
  return `<span class="card-facts">${location
    ? `<span class="card-location" title="${escapeHtml(location)}">${escapeHtml(location)}</span>` : ""}${count
    ? `<span class="card-counselees">${count} ${count === 1 ? "counselee" : "counselees"}</span>` : ""}</span>`;
}

/** A photo that could not be loaded: the avatar shows the initials on its tone instead (textContent only) */
function avatarFallback(img) {
  const avatar = img?.closest?.(".avatar");
  if (!avatar) return false;
  const label = img.getAttribute("alt");
  avatar.textContent = avatar.dataset.initials || "?";
  if (label) {
    avatar.setAttribute("role", "img");
    avatar.setAttribute("aria-label", label);
  }
  return true;
}

// Broken photo -> initials. Error events do not bubble, so listen in the capture phase.
document.addEventListener("error", (event) => {
  const img = event.target;
  if (!img || img.tagName !== "IMG") return;
  avatarFallback(img);
}, true);

/* ---------- Employee directory ---------- */

const OrgDirectory = {
  employees: [],
  byEmail: new Map(),
  byName: new Map(),
  byId: new Map()
};

function setDirectory(employees) {
  OrgDirectory.employees = employees || [];
  OrgDirectory.byEmail = new Map();
  OrgDirectory.byName = new Map();
  OrgDirectory.byId = new Map();
  OrgDirectory.employees.forEach((employee) => {
    const email = normalizeKey(employee.email);
    const name = normalizeKey(employee.name);
    if (email && !OrgDirectory.byEmail.has(email)) OrgDirectory.byEmail.set(email, employee);
    if (name && !OrgDirectory.byName.has(name)) OrgDirectory.byName.set(name, employee);
    if (employee.id != null && !OrgDirectory.byId.has(employee.id)) OrgDirectory.byId.set(employee.id, employee);
  });
}

function findPerson({ name, email }) {
  return OrgDirectory.byEmail.get(normalizeKey(email)) || OrgDirectory.byName.get(normalizeKey(name)) || null;
}

// PM name wins over PM email (same rule as the tree); the email covers typos in the name. Employees from the
// database carry ids: their parent is parentId (null: the parent is not on the site, shown from the pm fields)
function resolveManager(employee) {
  if (employee?.id != null) return OrgDirectory.byId.get(employee.parentId) || null;
  return OrgDirectory.byName.get(normalizeKey(employee?.pm)) || OrgDirectory.byEmail.get(normalizeKey(employee?.pmEmail)) || null;
}

function findManager(employee) {
  if (!employee?.pm && !employee?.pmEmail) return null;
  const found = resolveManager(employee);
  if (found) return found;
  return {
    name: employee.pm,
    email: employee.pmEmail || "",
    jobTitle: employee.pmJobTitle || "",
    photoUrl: employee.pmPhotoUrl || DEFAULT_PHOTO,
    external: true,
    // U6: outside Central Services, with a limited card (GET /api/people/{id}/external)
    externalId: employee.pmExternal === true && employee.pmId != null ? employee.pmId : null
  };
}

function findDirectReports(employee) {
  const email = normalizeKey(employee?.email);
  const name = normalizeKey(employee?.name);
  const seen = new Set();
  return OrgDirectory.employees
    .filter((candidate) => {
      if (candidate === employee) return false;
      const manager = resolveManager(candidate);
      if (!manager) return false;
      const isThisPerson = email ? normalizeKey(manager.email) === email : normalizeKey(manager.name) === name;
      if (!isThisPerson) return false;
      const key = normalizeKey(candidate.email) || normalizeKey(candidate.name);
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    })
    .sort(compareByGrade);
}

function compareByGrade(left, right) {
  const gradeDiff = (left?.gradeOrder ?? 999) - (right?.gradeOrder ?? 999);
  if (gradeDiff !== 0) return gradeDiff;
  return (left?.name || "").localeCompare(right?.name || "");
}

/* ---------- Requests ---------- */

const RELOAD_GUARD_KEY = "orgchart-reloaded-at";

// The CSRF token the server keeps in the XSRF-TOKEN cookie, sent back as the X-XSRF-TOKEN header
function csrfToken() {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/);
  return match ? decodeURIComponent(match[1]) : "";
}

// 401: the session ended. Reloading signs in again and comes back to this page
// (at most once in 10 seconds, so a sign-in that keeps failing does not loop).
function reloadOnUnauthorized(res) {
  if (res.status !== 401) return res;
  let last = 0;
  try { last = Number(sessionStorage.getItem(RELOAD_GUARD_KEY)) || 0; } catch (e) { /* storage unavailable */ }
  if (Date.now() - last > 10000) {
    try { sessionStorage.setItem(RELOAD_GUARD_KEY, String(Date.now())); } catch (e) { /* storage unavailable */ }
    window.location.reload();
  } else {
    notify("Session expired. Reload the page to sign in again.");
  }
  return res;
}

// fetch for /api/**: the CSRF header on every write, a reload on 401
function apiFetch(url, options = {}) {
  const method = (options.method || "GET").toUpperCase();
  const headers = new Headers(options.headers || {});
  if (method !== "GET" && method !== "HEAD") headers.set("X-XSRF-TOKEN", csrfToken());
  return fetch(url, { ...options, headers }).then(reloadOnUnauthorized);
}

/* ---------- Analytics ---------- */

function getVisitorId() {
  try {
    let id = localStorage.getItem("visitorId");
    if (!id) {
      id = "user-" + Math.random().toString(36).substring(2, 10) + Date.now().toString(36);
      localStorage.setItem("visitorId", id);
    }
    return id;
  } catch (e) {
    return "anonymous";
  }
}

function postJson(url, body) {
  return apiFetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body)
  });
}

function logPageVisit(pageName) {
  postJson("/api/page-visits", { pageName, visitorId: getVisitorId() })
    .catch((e) => console.error("Failed to log page visit", e));
}

function logInteraction(email, type) {
  if (!email || !type) return;
  postJson("/api/interactions", { employeeEmail: email, interactionType: type, visitorId: getVisitorId() })
    .catch((e) => console.error("Failed to log interaction", e));
}

function logSearchQuery(query, resultsCount) {
  if (!query || query.length < 2) return;
  postJson("/api/search-logs", { query, resultsCount, visitorId: getVisitorId() })
    .catch((e) => console.error("Failed to log search", e));
}

/* ---------- Toast ---------- */

function notify(message, type = "info", options = {}) {
  if (typeof toast === "function") toast(message, type, options);
  else if (typeof showOrgToast === "function") showOrgToast(message);
}

/* ---------- Copy the e-mail address of the profile card ---------- */

/**
 * Copies the address to the clipboard and confirms it with a toast. The Clipboard API needs a secure context
 * (https or localhost); elsewhere a hidden text area and execCommand("copy") are used.
 */
async function copyEmail(email) {
  if (!email) return false;
  let copied = false;
  if (navigator.clipboard && window.isSecureContext) {
    try {
      await navigator.clipboard.writeText(email);
      copied = true;
    } catch (e) { /* falls back below */ }
  }
  if (!copied) {
    const area = document.createElement("textarea");
    area.value = email;
    area.setAttribute("readonly", "");
    area.style.position = "fixed";
    area.style.opacity = "0";
    document.body.appendChild(area);
    area.select();
    try { copied = document.execCommand("copy"); } catch (e) { copied = false; }
    area.remove();
  }
  notify(copied ? "Email copied" : "Could not copy. Select the address and copy it manually.", copied ? "success" : "error");
  return copied;
}

/** The copy button shows a check and "Copied" for a moment after a successful copy */
async function copyEmailFrom(button) {
  if (!(await copyEmail(button.dataset.copyEmail))) return;
  const label = button.getAttribute("aria-label");
  button.classList.add("is-copied");
  button.setAttribute("aria-label", "Copied");
  button.title = "Copied";
  setTimeout(() => {
    button.classList.remove("is-copied");
    button.setAttribute("aria-label", label);
    button.title = "Copy email";
  }, 1500);
}

/* ---------- Managers outside Central Services (U6) ---------- */

/**
 * The limited card of a manager outside Central Services, in the profile dialog: name, badge, job title, e-mail,
 * city and country only; no counselee list, no reviews and no admin controls.
 */
async function openExternalPerson(id, from = null) {
  let person = null;
  try {
    const res = await apiFetch(`/api/people/${encodeURIComponent(id)}/external`);
    if (res.ok) person = await res.json();
  } catch (e) { /* shown below */ }
  if (!person) {
    notify("This person's card is not available.", "error");
    return null;
  }
  // The photo URL comes with the people who report to them (pmPhotoUrl of the directory build)
  const report = OrgDirectory.employees.find((e) => e.pmExternal === true && e.pmId === id && e.pmPhotoUrl);
  person.photoUrl = report ? report.pmPhotoUrl : DEFAULT_PHOTO;
  // Only now: a card that could not be loaded leaves the history as it was
  if (from) profileHistory.push(from);
  showProfile(renderExternalProfile(person), null);
  return person;
}

/* ---------- Reactions ---------- */

let likeSummaries = {};

function setLikeSummaries(summaries) {
  likeSummaries = summaries || {};
}

function readMyReactions() {
  try {
    return JSON.parse(localStorage.getItem(MY_REACTIONS_KEY)) || {};
  } catch (e) {
    return {};
  }
}

function saveMyReaction(email, type, isOn) {
  const all = readMyReactions();
  const key = normalizeKey(email);
  const mine = { ...(all[key] || {}) };
  if (isOn) mine[type] = true;
  else delete mine[type];
  all[key] = mine;
  try { localStorage.setItem(MY_REACTIONS_KEY, JSON.stringify(all)); } catch (e) { /* storage unavailable */ }
}

function renderReactions(email) {
  if (!email) return "";
  const key = normalizeKey(email);
  const summary = likeSummaries[key] || { solvedCount: 0, exceededCount: 0 };
  const mine = readMyReactions()[key] || {};
  const button = (type, cls, icon, label, count) => `
    <button class="like-btn ${cls}" type="button" data-email="${escapeHtml(email)}" data-type="${type}"
      aria-pressed="${mine[type] ? "true" : "false"}">
      ${icon}<span class="like-btn-text">${label}</span><span class="like-btn-count">${count}</span>
    </button>`;

  return `
    <div class="like-buttons">
      ${button("SOLVED", "like-btn-solved", ICONS.check, "Results delivered", summary.solvedCount)}
      ${button("EXCEEDED", "like-btn-exceeded", ICONS.star, "Exceeded expectations", summary.exceededCount)}
    </div>`;
}

function changeLikeCount(btn, email, type, delta) {
  const key = normalizeKey(email);
  const summary = likeSummaries[key] || (likeSummaries[key] = { solvedCount: 0, exceededCount: 0 });
  const field = type === "SOLVED" ? "solvedCount" : "exceededCount";
  summary[field] = Math.max(0, summary[field] + delta);

  const countEl = btn.querySelector(".like-btn-count");
  countEl.textContent = summary[field];
  countEl.classList.remove("like-count-bump");
  void countEl.offsetWidth;
  countEl.classList.add("like-count-bump");
}

// First click sends the review, second click takes it back
async function handleLikeClick(btn) {
  const email = btn.dataset.email;
  const type = btn.dataset.type;
  if (!email || !type || btn.disabled) return;

  const isOn = btn.getAttribute("aria-pressed") === "true";
  const visitorId = getVisitorId();
  btn.disabled = true;
  try {
    if (isOn) {
      const params = new URLSearchParams({ employeeEmail: email, reactionType: type, visitorId });
      const res = await apiFetch(`/api/likes?${params}`, { method: "DELETE" });
      if (res.ok || res.status === 404) {
        if (res.ok) changeLikeCount(btn, email, type, -1);
        saveMyReaction(email, type, false);
        btn.setAttribute("aria-pressed", "false");
      }
    } else {
      const res = await postJson("/api/likes", { employeeEmail: email, reactionType: type, visitorId });
      if (res.ok || res.status === 409) {
        if (res.ok) changeLikeCount(btn, email, type, 1);
        saveMyReaction(email, type, true);
        btn.setAttribute("aria-pressed", "true");
      }
    }
  } catch (e) {
    console.error("Failed to update review", e);
  } finally {
    btn.disabled = false;
  }
}

/* ---------- Profile dialog ---------- */

function chartUrl(employee) {
  return `/function.html?cs=${encodeURIComponent(employee.cs || "")}&emp=${encodeURIComponent(employee.email || "")}`;
}

function renderPersonRow(person, variant = "", hidden = false) {
  const body = `
    ${renderAvatar(person, "sm")}
    <span class="person-row-body">
      <span class="person-row-name">${escapeHtml(person.name || "—")}</span>
      ${person.jobTitle ? `<span class="person-row-title">${escapeHtml(person.jobTitle)}</span>` : ""}
    </span>`;

  const index = OrgDirectory.employees.indexOf(person);
  const hiddenAttr = hidden ? " hidden" : "";
  if (index < 0 && person.externalId != null) {
    // U6: a manager outside Central Services opens the limited card
    return `<button type="button" class="person-row person-row-external ${variant}" data-external-id="${escapeHtml(person.externalId)}"${hiddenAttr}>${body}<span class="badge badge-neutral person-row-badge">Outside Central Services</span>${ICONS.chevronRight}</button>`;
  }
  if (index < 0) {
    return `<div class="person-row person-row-static ${variant}"${hiddenAttr}>${body}</div>`;
  }
  return `<button type="button" class="person-row ${variant}" data-person-index="${index}"${hiddenAttr}>${body}${ICONS.chevronRight}</button>`;
}

/** The blue band of a card: avatar, name, job title and chips */
function renderProfileBand(person, chips) {
  return `
    <div class="profile-band">
      ${renderAvatar(person, "xl")}
      <div class="profile-id">
        <h2 class="profile-name" id="profileName">${escapeHtml(person.name || "—")}</h2>
        <div class="profile-title">${escapeHtml(person.jobTitle || "—")}</div>
        ${chips.length ? `<div class="profile-chips">${chips.map((chip) => `<span class="profile-chip">${escapeHtml(chip)}</span>`).join("")}</div>` : ""}
      </div>
    </div>`;
}

/**
 * The info cards of a card: the e-mail address as plain text with its copy button (only the Email button opens the
 * mail program), then City and Country in a grid of equal columns
 */
function renderProfileInfo(person) {
  const email = person.email || "";
  return `
    <div class="profile-info">
      ${email ? `
        <div class="profile-contact">
          <span class="profile-contact-label">Email</span>
          <span class="profile-email">${escapeHtml(email)}</span>
          <button type="button" class="icon-btn profile-copy-email" data-copy-email="${escapeHtml(email)}" aria-label="Copy email ${escapeHtml(email)}" title="Copy email">${ICONS.copy}${ICONS.check}</button>
        </div>` : '<div class="profile-contact profile-contact-empty">Email not specified</div>'}
      ${person.city || person.country ? `
        <dl class="profile-facts">
          ${person.city ? `<div class="profile-fact"><dt>City</dt><dd>${escapeHtml(person.city)}</dd></div>` : ""}
          ${person.country ? `<div class="profile-fact"><dt>Country</dt><dd>${escapeHtml(person.country)}</dd></div>` : ""}
        </dl>` : ""}
    </div>`;
}

function renderEmailButton(email) {
  return email ? `<a class="profile-btn profile-btn-primary" href="mailto:${escapeHtml(email)}" data-mail="${escapeHtml(email)}">${ICONS.mail}Email</a>` : "";
}

/** Back to the card this one was opened from in the open dialog; nothing on the first card */
function renderBackButton() {
  const previous = profileHistory[profileHistory.length - 1];
  if (!previous) return "";
  return `<button type="button" class="profile-btn" data-profile-back aria-label="Back to ${escapeHtml(previous.name || "the previous profile")}">${ICONS.arrowLeft}Back</button>`;
}

/**
 * The card of a manager outside Central Services: the profile's band and info cards, Back and Email only (closed with
 * the X of the dialog, as every card)
 */
function renderExternalProfile(person) {
  return `
    ${renderProfileBand({ name: person.name, jobTitle: person.jobTitle, photoUrl: person.photoUrl }, ["Outside Central Services"])}

    <div class="profile-content">
      ${renderProfileInfo(person)}

      <div class="profile-actions">
        ${renderBackButton()}
        ${renderEmailButton(person.email || "")}
      </div>
    </div>`;
}

function renderProfile(employee) {
  const manager = findManager(employee);
  const reports = findDirectReports(employee);
  const email = employee.email || "";

  return `
    ${renderProfileBand(employee, [employee.cs, employee.group].filter(Boolean))}

    <div class="profile-content">
      ${renderProfileInfo(employee)}

      <div class="profile-actions">
        ${renderBackButton()}
        ${renderEmailButton(email)}
        ${employee.cs ? `<a class="profile-btn" href="${chartUrl(employee)}" data-show-in-chart>${ICONS.target}Show in chart</a>` : ""}
      </div>

      <div class="profile-grid">
        <div class="profile-col">
          ${employee.areaOfDuties ? `
            <section>
              <h3 class="profile-heading">Area of duties</h3>
              <p class="profile-text">${escapeHtml(employee.areaOfDuties)}</p>
            </section>` : ""}
          ${employee.jobDescription ? `
            <section>
              <h3 class="profile-heading">Job description</h3>
              <p class="profile-text">${escapeHtml(employee.jobDescription)}</p>
            </section>` : ""}
          ${email ? `
            <section>
              <h3 class="profile-heading">Recognition</h3>
              ${renderReactions(email)}
            </section>` : ""}
        </div>

        <div class="profile-col">
          <section>
            <h3 class="profile-heading">Reports to</h3>
            ${manager ? renderPersonRow(manager, "person-row-pm") : '<p class="profile-muted">Not assigned</p>'}
          </section>
          <section>
            <h3 class="profile-heading">Counselees <span class="profile-count">${reports.length}</span></h3>
            ${reports.length
              ? `<div class="person-list">${reports.map((person, index) => renderPersonRow(person, "", index >= REPORTS_PREVIEW)).join("")}</div>
                 ${reports.length > REPORTS_PREVIEW ? `<button type="button" class="profile-show-all" data-show-all-reports>Show all ${reports.length}</button>` : ""}`
              : '<p class="profile-muted">No counselees</p>'}
          </section>
        </div>
      </div>
    </div>`;
}

let profileEmployee = null;
let profileReturnFocus = null;
// The employees whose cards led to the current one in the open dialog (Back); emptied when it closes
let profileHistory = [];

function ensureProfileDialog() {
  let dialog = document.getElementById("profileDialog");
  if (dialog) return dialog;

  document.body.insertAdjacentHTML("beforeend", `
    <dialog class="profile-dialog" id="profileDialog" aria-labelledby="profileName">
      <button class="profile-close" type="button" aria-label="Close profile">${ICONS.close}</button>
      <div id="profileBody"></div>
    </dialog>
  `);
  dialog = document.getElementById("profileDialog");

  dialog.addEventListener("click", (event) => {
    if (event.target === dialog || event.target.closest(".profile-close, [data-profile-close]")) {
      dialog.close();
      return;
    }

    if (event.target.closest("[data-profile-back]")) {
      const previous = profileHistory.pop();
      // The current copy (same person, same function): the directory may have been rebuilt meanwhile
      const fresh = previous && previous.id != null
        ? OrgDirectory.employees.find((e) => e.id === previous.id && e.cs === previous.cs) : null;
      if (previous) openEmployeeProfile(fresh || previous, false);
      return;
    }

    const personRow = event.target.closest("[data-person-index]");
    if (personRow) {
      const person = OrgDirectory.employees[Number(personRow.dataset.personIndex)];
      if (person) {
        if (profileEmployee) profileHistory.push(profileEmployee);
        openEmployeeProfile(person);
      }
      return;
    }

    const showAll = event.target.closest("[data-show-all-reports]");
    if (showAll) {
      showAll.previousElementSibling.querySelectorAll(".person-row[hidden]").forEach((row) => { row.hidden = false; });
      showAll.remove();
      return;
    }

    const copyEmailButton = event.target.closest("[data-copy-email]");
    if (copyEmailButton) {
      copyEmailFrom(copyEmailButton);
      return;
    }

    const external = event.target.closest("[data-external-id]");
    if (external) {
      openExternalPerson(Number(external.dataset.externalId), profileEmployee);
      return;
    }

    const likeBtn = event.target.closest(".like-btn");
    if (likeBtn) {
      handleLikeClick(likeBtn);
      return;
    }

    const mailLink = event.target.closest("[data-mail]");
    if (mailLink) {
      logInteraction(mailLink.dataset.mail, "MAIL_CLICK");
      return;
    }

    const chartLink = event.target.closest("[data-show-in-chart]");
    if (chartLink && profileEmployee && typeof window.showEmployeeInChart === "function") {
      if (window.showEmployeeInChart(profileEmployee)) {
        event.preventDefault();
        dialog.close();
      }
    }
  });

  dialog.addEventListener("close", () => {
    profileEmployee = null;
    profileHistory = [];
    if (profileReturnFocus && document.contains(profileReturnFocus)) {
      profileReturnFocus.focus({ preventScroll: true });
    }
    profileReturnFocus = null;
  });

  return dialog;
}

/**
 * Shows a card in the profile dialog (a native modal dialog: ESC, focus kept inside, focus returned on close).
 * html comes from renderProfile / renderExternalProfile, which escape every value. employee: the person of the site
 * shown (null for a manager outside Central Services).
 */
function showProfile(html, employee) {
  const dialog = ensureProfileDialog();
  profileEmployee = employee;
  const body = document.getElementById("profileBody");
  body.innerHTML = html;
  if (!dialog.open) {
    profileReturnFocus = document.activeElement;
    dialog.showModal();
  }
  dialog.scrollTop = 0;
  dialog.querySelector(".profile-close").focus();
  return body;
}

/** logView: false for Back (the card was already viewed and counted) */
function openEmployeeProfile(employee, logView = true) {
  if (!employee) return;
  const body = showProfile(renderProfile(employee), employee);
  if (typeof window.onProfileRendered === "function") window.onProfileRendered(employee, body);
  // Change photo (photo-upload.js): own card, or any card for admins
  if (typeof window.addPhotoUploadButton === "function") window.addPhotoUploadButton(employee, body);

  if (logView && employee.email) logInteraction(employee.email, "PROFILE_VIEW");
}

/* ---------- Admin tools (U1) ---------- */

// GET /api/me/roles once per page. admin.js and the "Admin" link exist only for admins (admin=true); everybody else
// gets neither, and every /api/manage/** call would answer 403 anyway.
const Me = { loading: null };

function loadMe() {
  if (!Me.loading) {
    Me.loading = apiFetch("/api/me/roles")
      .then((res) => (res.ok ? res.json() : null))
      .catch(() => null)
      .then((me) => me || { authenticated: false, roles: [], admin: false, superAdmin: false, editingEnabled: false });
  }
  return Me.loading;
}

const ADMIN_SCRIPT = "/js/admin.js?v=3.1";

function addAdminLink() {
  const header = document.querySelector(".header-inner");
  if (!header || header.querySelector(".header-admin-link")) return;
  const link = document.createElement("a");
  link.className = "header-admin-link";
  link.href = "/admin.html";
  link.textContent = "Admin";
  header.appendChild(link);
}

// The admin scripts in order (async=false keeps the order of dynamically added scripts)
const ADMIN_SCRIPTS = ["/js/validation.js?v=3.0", ADMIN_SCRIPT, "/js/admin-groups.js?v=3.2", "/js/admin-faq.js?v=3.1"];

function loadAdminScript() {
  if (document.querySelector("script[data-admin-tools]")) return;
  ADMIN_SCRIPTS.forEach((src) => {
    const script = document.createElement("script");
    script.src = src;
    script.async = false;
    script.setAttribute("data-admin-tools", "");
    document.body.appendChild(script);
  });
}

/** Loads the admin tools when the signed-in person is an admin; resolves to true in that case */
function initAdminTools(options = {}) {
  return loadMe().then((me) => {
    if (me.admin !== true) return false;
    addAdminLink();
    if (options.script !== false) loadAdminScript();
    return true;
  });
}

// Admin tools for admins only (the admin page loads its own script)
if (document.body?.dataset?.page !== "admin") initAdminTools();
