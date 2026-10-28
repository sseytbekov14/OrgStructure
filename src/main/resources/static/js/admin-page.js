/* The admin page (admin.html, served to admins only, U1): the list of administrators for super admins.
   Everything is built with createElement and textContent: names and notes never become HTML. */

async function adminPageError(res) {
  try {
    const body = await res.json();
    return body.message || `Error ${res.status}`;
  } catch (e) {
    return `Error ${res.status}`;
  }
}

function adminCell(text) {
  return uiEl("td", { text: text == null || text === "" ? "—" : String(text) });
}

function renderAdminRows(admins) {
  AdminPeople.admins = new Set(admins.map((admin) => String(admin.loginName || "").toLowerCase()));
  const rows = document.getElementById("adminAdminsRows");
  rows.replaceChildren(...admins.map((admin) => uiEl("tr", {},
    adminCell(admin.loginName), adminCell(admin.note), adminCell(admin.addedBy),
    adminCell(admin.addedAt ? new Date(admin.addedAt).toLocaleDateString() : ""),
    uiEl("td", {}, uiEl("button", {
      type: "button", class: "btn btn-ghost btn-sm", text: "Remove", "aria-label": `Remove ${admin.loginName}`,
      onclick: () => removeAdmin(admin)
    })))));
  if (!admins.length) {
    rows.append(uiEl("tr", {}, uiEl("td", { colspan: "5", text: "No administrators in the list yet." })));
  }
}

async function loadAdmins() {
  const res = await apiFetch("/api/manage/admins");
  if (!res.ok) { toast(await adminPageError(res), "error"); return; }
  renderAdminRows(await res.json());
}

async function removeAdmin(admin) {
  const confirmed = await confirmDialog({
    title: `Remove ${admin.loginName}?`,
    message: `${admin.loginName} will no longer be able to edit the site.`,
    confirmLabel: "Remove", danger: true
  });
  if (!confirmed) return;
  const res = await apiFetch(`/api/manage/admins/${encodeURIComponent(admin.id)}`, { method: "DELETE" });
  if (!res.ok) { toast(await adminPageError(res), "error"); return; }
  toast(`${admin.loginName} removed`, "success");
  await loadAdmins();
}

async function addAdmin(event) {
  event.preventDefault();
  const form = event.target;
  const input = form.loginName;
  const error = document.getElementById("adminLoginNameError");
  const loginName = input.value.trim();
  const problem = /^[A-Za-z0-9._@+-]{3,256}$/.test(loginName) ? ""
    : "The login name must have 3 to 256 characters: letters A-Z, digits and . _ @ + -";
  error.textContent = problem;
  input.setAttribute("aria-invalid", problem ? "true" : "false");
  if (problem) { input.focus(); return; }
  const submit = form.querySelector("button[type=submit]");
  submit.disabled = true;
  const res = await apiFetch("/api/manage/admins", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ loginName, note: form.note.value })
  });
  submit.disabled = false;
  if (!res.ok) { error.textContent = await adminPageError(res); return; }
  form.reset();
  toast(`${loginName} added`, "success");
  await loadAdmins();
}

/* ---------- Login name: a list of all people to pick from, or a typed account name ---------- */

const AdminPeople = { list: null, admins: new Set(), shown: [], active: -1 };
const ADMIN_PEOPLE_SHOWN = 300;

/** Everybody on the site once (by e-mail), sorted by name */
async function loadAdminPeople() {
  if (AdminPeople.list) return AdminPeople.list;
  const res = await apiFetch("/api/employees");
  if (!res.ok) return [];
  const byEmail = new Map();
  (await res.json()).forEach((person) => {
    const email = String(person.email || "").trim();
    if (!email || byEmail.has(email.toLowerCase())) return;
    byEmail.set(email.toLowerCase(), { name: person.name || email, email, jobTitle: person.jobTitle || "" });
  });
  AdminPeople.list = [...byEmail.values()].sort((a, b) => a.name.localeCompare(b.name));
  return AdminPeople.list;
}

function initLoginPicker() {
  const combo = document.getElementById("adminLoginNameCombo");
  const input = document.getElementById("adminLoginName");
  const list = document.getElementById("adminLoginNameList");
  const toggle = document.getElementById("adminLoginNameToggle");
  if (!combo || !input || !list) return;

  const isOpen = () => !list.hidden;
  const close = () => {
    list.hidden = true;
    input.setAttribute("aria-expanded", "false");
    input.removeAttribute("aria-activedescendant");
    AdminPeople.active = -1;
  };
  const setActive = (index) => {
    const options = [...list.querySelectorAll("[role=option]")];
    if (!options.length) return;
    AdminPeople.active = (index + options.length) % options.length;
    options.forEach((option, i) => option.setAttribute("aria-selected", i === AdminPeople.active ? "true" : "false"));
    const active = options[AdminPeople.active];
    input.setAttribute("aria-activedescendant", active.id);
    if (typeof active.scrollIntoView === "function") active.scrollIntoView({ block: "nearest" });
  };
  const choose = (person) => {
    input.value = person.email;
    document.getElementById("adminLoginNameError").textContent = "";
    input.setAttribute("aria-invalid", "false");
    close();
    input.focus();
  };
  const open = async (all = false) => {
    const people = await loadAdminPeople();
    const query = all ? "" : input.value.trim().toLowerCase();
    const matches = people.filter((p) => !query || p.name.toLowerCase().includes(query) || p.email.toLowerCase().includes(query));
    AdminPeople.shown = matches.slice(0, ADMIN_PEOPLE_SHOWN);
    list.replaceChildren(...AdminPeople.shown.map((person, i) => {
      const isAdmin = AdminPeople.admins.has(person.email.toLowerCase());
      const option = uiEl("li", { class: "combo-option", role: "option", id: `adminLoginNameOption${i}`,
        "aria-selected": "false", "aria-disabled": isAdmin ? "true" : null },
      uiEl("span", { class: "combo-option-main" },
        uiEl("span", { class: "combo-option-name", text: person.name }),
        uiEl("span", { class: "combo-option-sub", text: [person.email, person.jobTitle].filter(Boolean).join(" · ") })),
      isAdmin ? uiEl("span", { class: "badge badge-neutral", text: "Admin" }) : null);
      // mousedown keeps the focus in the input
      option.addEventListener("mousedown", (event) => event.preventDefault());
      option.addEventListener("click", () => { if (!isAdmin) choose(person); });
      return option;
    }));
    if (!AdminPeople.shown.length) {
      list.append(uiEl("li", { class: "combo-empty", text: query ? "Nobody found. You can still add the typed name." : "No people loaded." }));
    } else if (matches.length > AdminPeople.shown.length) {
      list.append(uiEl("li", { class: "combo-empty", text: `${matches.length - AdminPeople.shown.length} more: type to narrow down.` }));
    }
    list.hidden = false;
    input.setAttribute("aria-expanded", "true");
    AdminPeople.active = -1;
    input.removeAttribute("aria-activedescendant");
  };

  input.addEventListener("click", () => { if (!isOpen()) open(); });
  input.addEventListener("input", () => open());
  input.addEventListener("keydown", async (event) => {
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      if (!isOpen()) await open();
      const step = event.key === "ArrowDown" ? 1 : -1;
      setActive(AdminPeople.active < 0 ? (step > 0 ? 0 : -1) : AdminPeople.active + step);
    } else if (event.key === "Enter" && isOpen() && AdminPeople.active >= 0) {
      event.preventDefault();
      const person = AdminPeople.shown[AdminPeople.active];
      if (person && !AdminPeople.admins.has(person.email.toLowerCase())) choose(person);
    } else if (event.key === "Escape" && isOpen()) {
      event.preventDefault();
      event.stopPropagation();
      close();
    }
  });
  input.addEventListener("blur", () => setTimeout(() => { if (document.activeElement !== input) close(); }, 150));
  toggle?.addEventListener("mousedown", (event) => event.preventDefault());
  toggle?.addEventListener("click", () => { if (isOpen()) close(); else { input.focus(); open(true); } });
}

/* ---------- Hidden people (U4) ---------- */

function renderHiddenRows(people) {
  const rows = document.getElementById("adminHiddenRows");
  rows.replaceChildren(...people.map((person) => uiEl("tr", {},
    adminCell(person.name), adminCell(person.jobTitle), adminCell(person.reason), adminCell(person.hiddenBy),
    adminCell(person.hiddenAt ? new Date(person.hiddenAt).toLocaleDateString() : ""),
    uiEl("td", {}, uiEl("button", {
      type: "button", class: "btn btn-secondary btn-sm", text: "Restore", "aria-label": `Restore ${person.name || "this person"}`,
      onclick: () => restoreHidden(person)
    })))));
  if (!people.length) {
    rows.append(uiEl("tr", {}, uiEl("td", { colspan: "6", text: "Nobody is hidden." })));
  }
}

async function loadHidden() {
  const res = await apiFetch("/api/manage/hidden");
  if (!res.ok) { toast(await adminPageError(res), "error"); return; }
  renderHiddenRows(await res.json());
}

async function restoreHidden(person) {
  const name = person.name || "this person";
  const confirmed = await confirmDialog({
    title: `Restore ${name}?`,
    message: `${name} appears on the site again for everyone: in the tree, the groups and the search.`,
    confirmLabel: "Restore"
  });
  if (!confirmed) return;
  const res = await apiFetch(`/api/manage/employees/${encodeURIComponent(person.employeeId)}/hide`, { method: "DELETE" });
  if (!res.ok) { toast(await adminPageError(res), "error"); return; }
  toast(`${person.name || "The person"} is back on the site`, "success");
  await loadHidden();
}

/* ---------- Tabs ---------- */

function initAdminTabs() {
  const tabs = [...document.querySelectorAll("[role=tab]")].filter((tab) => !tab.hidden);
  const select = (tab) => {
    tabs.forEach((t) => {
      const selected = t === tab;
      t.setAttribute("aria-selected", selected ? "true" : "false");
      t.setAttribute("tabindex", selected ? "0" : "-1");
      document.getElementById(t.getAttribute("aria-controls")).hidden = !selected;
    });
    tab.focus();
  };
  tabs.forEach((tab, index) => {
    tab.addEventListener("click", () => select(tab));
    tab.addEventListener("keydown", (event) => {
      if (event.key !== "ArrowRight" && event.key !== "ArrowLeft") return;
      event.preventDefault();
      select(tabs[(index + (event.key === "ArrowRight" ? 1 : tabs.length - 1)) % tabs.length]);
    });
  });
}

async function initAdminPage() {
  const me = await loadMe();
  document.getElementById("adminYou").textContent = me.you ? `You are signed in as ${me.you}.` : "";
  if (me.superAdmin === true) {
    document.getElementById("adminTabAdmins").hidden = false;
    document.getElementById("adminAddForm").addEventListener("submit", addAdmin);
    initLoginPicker();
    await loadAdmins();
  }
  initAdminTabs();
  if (me.editingEnabled === true) {
    await loadHidden();
  } else {
    renderHiddenRows([]);
  }
}

initAdminPage();
