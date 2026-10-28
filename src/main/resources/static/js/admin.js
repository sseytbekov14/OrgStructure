/* Admin editing (admins with EMPLOYEE_SOURCE=db, spec v3 §10.2): an Edit button in the profile card and the
   "Function heads" block of the function page. common.js loads this script for admins only (U1). Hiding the controls is cosmetic: the server checks every change.
   Everything is built with createElement and textContent / value: texts typed by admins are shown to every user and
   never become HTML. */

/** Editing needs an admin (database list or env super admin, U1) and EMPLOYEE_SOURCE=db */
function loadAdminSession() {
  return loadMe().then((me) => me.admin === true && me.editingEnabled === true);
}

/** An element with attributes, a text and children; never HTML */
function adminEl(tag, attributes = {}, ...children) {
  const node = document.createElement(tag);
  Object.entries(attributes).forEach(([key, value]) => {
    if (value == null || value === false) return;
    if (key === "class") node.className = value;
    else if (key === "text") node.textContent = value;
    else if (key.startsWith("on")) node.addEventListener(key.slice(2), value);
    else node.setAttribute(key, value === true ? "" : value);
  });
  children.flat().filter(Boolean).forEach((child) => node.append(child));
  return node;
}

/** The server's message of a refused change (always shown as text) */
async function adminError(res) {
  try {
    const body = await res.json();
    return body.message || `Error ${res.status}`;
  } catch (e) {
    return `Error ${res.status}`;
  }
}

function adminJson(url, method, body) {
  return apiFetch(url, { method, headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
}

const ADMIN_TEXT_MAX = 500;
const ADMIN_GROUP_MAX = 256;

/** Mirrors the server: no < or > (no HTML), length limit */
function adminInvalid(label, value, max) {
  if (/[<>]/.test(value)) return `${label} must not contain < or >`;
  if (value.trim().length > max) return `${label} has more than ${max} characters`;
  return null;
}

/* ---------- Edit in the profile card ---------- */

window.onProfileRendered = async (employee, body) => {
  if (employee?.id == null || !employee.cs || !(await loadAdminSession())) return;
  const actions = body.querySelector(".profile-actions");
  if (!actions || actions.querySelector("[data-admin-edit]")) return;
  actions.append(adminEl("button", {
    type: "button", class: "profile-btn admin-edit-btn", "data-admin-edit": true, text: "Edit",
    "aria-label": `Edit the texts of this person in ${employee.cs}`,
    onclick: () => openAdminEditor(employee)
  }));
};

function adminDialog(id) {
  let dialog = document.getElementById(id);
  if (!dialog) {
    dialog = adminEl("dialog", { class: "profile-dialog admin-dialog", id });
    dialog.addEventListener("click", (event) => { if (event.target === dialog) dialog.close(); });
    document.body.append(dialog);
  }
  dialog.replaceChildren();
  return dialog;
}

/** The groups already used in the function, as suggestions (free text stays possible) */
function adminGroupSuggestions(cs) {
  const groups = new Set();
  OrgDirectory.employees
    .filter((e) => e.cs === cs && e.group && e.group.trim().toUpperCase() !== "HEAD")
    .forEach((e) => groups.add(e.group.trim()));
  return [...groups].sort((a, b) => a.localeCompare(b));
}

async function openAdminEditor(employee) {
  const cs = employee.cs;
  const dialog = adminDialog("adminEditDialog");
  const status = adminEl("p", { class: "admin-status", role: "status", "aria-live": "polite", text: "Loading…" });
  dialog.append(adminEl("div", { class: "admin-dialog-body" },
    adminEl("h2", { class: "admin-title", text: `Edit: ${employee.name || "—"}` }),
    adminEl("p", { class: "admin-subtitle", text: `Function ${cs}` }),
    status));
  if (!dialog.open) dialog.showModal();

  let current = null;
  try {
    const res = await apiFetch(`/api/manage/overrides?cs=${encodeURIComponent(cs)}&q=${encodeURIComponent(employee.email || "")}`);
    if (!res.ok) throw new Error(await adminError(res));
    current = (await res.json()).find((item) => item.employeeId === employee.id) || null;
  } catch (e) {
    status.textContent = e.message;
    return;
  }
  const override = current?.override || null;
  const isHead = override?.headRank != null;

  const listId = "adminGroupList";
  const groupInput = adminEl("input", {
    id: "adminGroup", type: "text", maxlength: ADMIN_GROUP_MAX, list: listId, autocomplete: "off",
    placeholder: override?.groupName ? "" : (current?.group && current.group !== "HEAD" ? `${current.group} (from AD)` : "No group")
  });
  groupInput.value = override?.groupName || "";
  const groupList = adminEl("datalist", { id: listId },
    adminGroupSuggestions(cs).map((group) => adminEl("option", { value: group })));
  const area = adminEl("textarea", { id: "adminArea", rows: 2, maxlength: ADMIN_TEXT_MAX });
  area.value = override?.areaOfDuties || "";
  const description = adminEl("textarea", { id: "adminDescription", rows: 3, maxlength: ADMIN_TEXT_MAX });
  description.value = override?.jobDescription || "";

  const save = adminEl("button", { type: "submit", class: "profile-btn profile-btn-primary", text: "Save" });
  const cancel = adminEl("button", { type: "button", class: "profile-btn", text: "Cancel", onclick: () => dialog.close() });
  const remove = override ? adminEl("button", {
    type: "button", class: "profile-btn admin-danger", text: "Remove entry",
    title: "Back to the AD default (or out of this function, if it is an extra one)",
    onclick: () => removeAdminEntry(employee, override, status, dialog)
  }) : null;

  const form = adminEl("form", { class: "admin-form", novalidate: true },
    adminEl("label", { for: "adminGroup", text: "Group" }), groupInput, groupList,
    isHead ? adminEl("p", { class: "admin-hint", text: "Shown as HEAD while this person is a head of the function." }) : null,
    adminEl("label", { for: "adminArea", text: "Area of duties" }), area,
    adminEl("label", { for: "adminDescription", text: "Job description" }), description,
    adminEl("div", { class: "admin-actions" }, save, cancel, remove));
  status.textContent = "";
  status.before(form);

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const problem = adminInvalid("Group", groupInput.value, ADMIN_GROUP_MAX)
      || adminInvalid("Area of duties", area.value, ADMIN_TEXT_MAX)
      || adminInvalid("Job description", description.value, ADMIN_TEXT_MAX);
    if (problem) { status.textContent = problem; return; }
    save.disabled = true;
    status.textContent = "Saving…";
    const res = await adminJson(`/api/manage/overrides/${employee.id}?cs=${encodeURIComponent(cs)}`, "PUT", {
      groupName: groupInput.value, areaOfDuties: area.value, jobDescription: description.value,
      version: override ? override.version : null
    });
    save.disabled = false;
    if (!res.ok) { status.textContent = await adminError(res); return; }
    dialog.close();
    document.getElementById("profileDialog")?.close();
    notify("Saved");
    if (typeof window.refreshOrgChart === "function") window.refreshOrgChart();
  });
}

async function removeAdminEntry(employee, override, status, dialog) {
  if (!(await confirmDialog({
    title: "Remove this entry?",
    message: "The person goes back to the AD default (or leaves this extra function).",
    confirmLabel: "Remove", danger: true
  }))) return;
  const url = `/api/manage/overrides/${employee.id}?cs=${encodeURIComponent(employee.cs)}&version=${override.version}`;
  let res = await apiFetch(url, { method: "DELETE" });
  if (res.status === 409) {
    const message = await adminError(res);
    if (!message.includes("last head") || !(await confirmDialog({
      title: "Remove the last head?", message: `${message}. Remove anyway?`, confirmLabel: "Remove", danger: true
    }))) { status.textContent = message; return; }
    res = await apiFetch(`${url}&confirmLastHead=true`, { method: "DELETE" });
  }
  if (!res.ok) { status.textContent = await adminError(res); return; }
  dialog.close();
  document.getElementById("profileDialog")?.close();
  notify("Entry removed");
  if (typeof window.refreshOrgChart === "function") window.refreshOrgChart();
}

/* ---------- Function heads (function page) ---------- */

const AdminHeads = { cs: null, expected: [], heads: [], hidden: 0, all: false, query: "" };

window.onFunctionRendered = async (cs, members) => {
  if (!(await loadAdminSession())) return;
  let res;
  try {
    res = await apiFetch(`/api/manage/functions/${encodeURIComponent(cs)}/heads`);
  } catch (e) {
    return;
  }
  if (!res.ok) return;
  const state = await res.json();
  AdminHeads.cs = cs;
  AdminHeads.expected = state.heads.map((h) => h.employeeId);
  AdminHeads.heads = state.heads.map((h) => ({ id: h.employeeId, name: h.name, jobTitle: h.jobTitle }));
  AdminHeads.hidden = state.hiddenHeads;
  AdminHeads.members = members;
  renderAdminHeads();
};

function renderAdminHeads() {
  let section = document.getElementById("adminHeads");
  if (!section) {
    section = adminEl("section", { id: "adminHeads", class: "admin-heads", "aria-label": "Function heads" });
    const chart = document.getElementById("orgChart");
    chart.parentNode.insertBefore(section, chart);
  }
  const changed = JSON.stringify(AdminHeads.heads.map((h) => h.id)) !== JSON.stringify(AdminHeads.expected);
  const status = adminEl("p", { class: "admin-status", role: "status", "aria-live": "polite" });

  const list = adminEl("ol", { class: "admin-heads-list" },
    AdminHeads.heads.map((head, index) => adminEl("li", { class: "admin-heads-item" },
      adminEl("span", { class: "admin-heads-rank", text: String(index + 1) }),
      adminEl("span", { class: "admin-heads-name", text: head.name || "—" }),
      adminEl("span", { class: "admin-heads-title", text: head.jobTitle || "" }),
      adminEl("span", { class: "admin-heads-buttons" },
        adminEl("button", { type: "button", class: "icon-btn", text: "↑", "aria-label": `Move ${head.name} up`,
          disabled: index === 0, onclick: () => moveAdminHead(index, -1) }),
        adminEl("button", { type: "button", class: "icon-btn", text: "↓", "aria-label": `Move ${head.name} down`,
          disabled: index === AdminHeads.heads.length - 1, onclick: () => moveAdminHead(index, 1) }),
        adminEl("button", { type: "button", class: "icon-btn", text: "✕", "aria-label": `Remove ${head.name} as head`,
          onclick: () => { AdminHeads.heads.splice(index, 1); renderAdminHeads(); } })))));

  const search = adminEl("input", { type: "search", class: "admin-heads-search", placeholder: "Add a head…",
    "aria-label": "Find a person to add as head", autocomplete: "off" });
  search.value = AdminHeads.query;
  const allToggle = adminEl("input", { type: "checkbox", id: "adminHeadsAll", checked: AdminHeads.all });
  const results = adminEl("div", { class: "admin-heads-results" });
  const showResults = debounce(async () => {
    AdminHeads.query = search.value;
    results.replaceChildren(...(await adminHeadCandidates(search.value)).map((person) => adminEl("button", {
      type: "button", class: "admin-heads-candidate", text: `${person.name}${person.jobTitle ? ` — ${person.jobTitle}` : ""}`,
      onclick: () => { AdminHeads.heads.push(person); AdminHeads.query = ""; renderAdminHeads(); }
    })));
  }, 200);
  search.addEventListener("input", showResults);
  allToggle.addEventListener("change", () => { AdminHeads.all = allToggle.checked; showResults(); });

  const save = adminEl("button", { type: "button", class: "profile-btn profile-btn-primary", text: "Save heads",
    disabled: !changed, onclick: () => saveAdminHeads(status) });
  const reset = adminEl("button", { type: "button", class: "profile-btn", text: "Undo changes", disabled: !changed,
    onclick: () => window.refreshOrgChart && window.refreshOrgChart() });

  // replaceChildren(null) would show the text "null": leave the missing parts out
  section.replaceChildren(...[
    adminEl("div", { class: "admin-heads-header" },
      adminEl("h2", { class: "admin-heads-heading", text: "Function heads" }),
      adminEl("span", { class: "admin-badge", text: "Admin" })),
    AdminHeads.heads.length ? null : adminEl("p", { class: "admin-warning", text: "This function has no active head: the tree has no root." }),
    AdminHeads.hidden ? adminEl("p", { class: "admin-hint", text: `${AdminHeads.hidden} head(s) disabled in AD are kept and come back when re-enabled.` }) : null,
    list,
    adminEl("div", { class: "admin-heads-add" }, search,
      adminEl("label", { class: "admin-heads-all", for: "adminHeadsAll" }, allToggle, adminEl("span", { text: "All employees" }))),
    results,
    adminEl("div", { class: "admin-actions" }, save, reset),
    status].filter(Boolean));
  if (AdminHeads.query) showResults();
}

function moveAdminHead(index, step) {
  const heads = AdminHeads.heads;
  [heads[index], heads[index + step]] = [heads[index + step], heads[index]];
  renderAdminHeads();
}

/** Members of the function first; with "All employees" anybody on the site */
async function adminHeadCandidates(query) {
  const q = query.trim().toLowerCase();
  if (q.length < 2) return [];
  const taken = new Set(AdminHeads.heads.map((h) => h.id));
  let people;
  if (AdminHeads.all) {
    const res = await apiFetch(`/api/manage/overrides?q=${encodeURIComponent(q)}`);
    people = res.ok ? (await res.json()).map((m) => ({ id: m.employeeId, name: m.name, jobTitle: m.jobTitle })) : [];
  } else {
    people = (AdminHeads.members || [])
      .filter((e) => e.id != null && [e.name, e.email, e.jobTitle].join(" ").toLowerCase().includes(q))
      .map((e) => ({ id: e.id, name: e.name, jobTitle: e.jobTitle }));
  }
  const seen = new Set();
  return people.filter((p) => !taken.has(p.id) && !seen.has(p.id) && seen.add(p.id)).slice(0, 8);
}

async function saveAdminHeads(status) {
  const employeeIds = AdminHeads.heads.map((h) => h.id);
  let confirmRemoveAllHeads = false;
  if (!employeeIds.length && AdminHeads.expected.length) {
    if (!(await confirmDialog({
      title: `Leave ${AdminHeads.cs} without a head?`, message: "The tree then has no root.",
      confirmLabel: "Save without head", danger: true
    }))) return;
    confirmRemoveAllHeads = true;
  }
  status.textContent = "Saving…";
  const res = await adminJson(`/api/manage/functions/${encodeURIComponent(AdminHeads.cs)}/heads`, "PUT",
    { employeeIds, expectedHeads: AdminHeads.expected, confirmRemoveAllHeads });
  if (!res.ok) { status.textContent = await adminError(res); return; }
  notify("Heads saved");
  if (typeof window.refreshOrgChart === "function") window.refreshOrgChart();
}

// Loaded after the function page may already have rendered: show the heads block for that render
if (window.lastFunctionRender && typeof window.onFunctionRendered === "function") {
  window.onFunctionRendered(window.lastFunctionRender.cs, window.lastFunctionRender.members);
}
