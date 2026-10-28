/* Admin editing (admins with EMPLOYEE_SOURCE=db, spec v3 §10.2): an Edit button in the profile card and the
   "Function heads" panel of the function page. common.js loads this script for admins only (U1).
   U3: the edit form is a modal dialog of ui.js with the shared field rules of validation.js (the same rules as the
   server), the heads panel is a collapsible with one main head and additional heads, and removing an entry is named
   by context ("Reset to AD data" / "Remove from function"). Hiding the controls is cosmetic: the server checks every
   change. Everything is built with DOM APIs and textContent: texts typed by admins are shown to every user and never
   become HTML. */

/** Editing needs an admin (database list or env super admin, U1) and EMPLOYEE_SOURCE=db */
function loadAdminSession() {
  return loadMe().then((me) => me.admin === true && me.editingEnabled === true);
}

/** The body of a refused request: {message, code, errors} */
async function adminRefusal(res) {
  try {
    const body = await res.json();
    return { message: body.message || `Error ${res.status}`, code: body.code || null, errors: body.errors || null };
  } catch (e) {
    return { message: `Error ${res.status}`, code: null, errors: null };
  }
}

async function adminError(res) {
  return (await adminRefusal(res)).message;
}

function adminJson(url, method, body) {
  return apiFetch(url, { method, headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
}

/** After a change: the chart is loaded again and an open profile shows the new data (no page reload) */
async function adminRefreshInPlace(employee) {
  if (typeof window.refreshOrgChart === "function") await window.refreshOrgChart();
  const profile = document.getElementById("profileDialog");
  if (!profile || !profile.open || !employee) return;
  const updated = (OrgDirectory.employees || []).find((e) => e.id === employee.id && e.cs === employee.cs);
  if (updated) openEmployeeProfile(updated);
  else profile.close();
}

/* ---------- Edit in the profile card ---------- */

window.onProfileRendered = async (employee, body) => {
  if (employee?.id == null || !employee.cs || !(await loadAdminSession())) return;
  const actions = body.querySelector(".profile-actions");
  if (!actions || actions.querySelector("[data-admin-edit]")) return;
  actions.append(uiEl("button", {
    type: "button", class: "profile-btn admin-edit-btn", "data-admin-edit": true, text: "Edit",
    "aria-label": `Edit ${employee.name || "this person"} in ${employee.cs}`,
    onclick: () => openAdminEditor(employee)
  }), uiEl("button", {
    type: "button", class: "profile-btn admin-hide-btn", "data-admin-hide": true, text: "Hide from site",
    "aria-label": `Hide ${employee.name || "this person"} from the site`,
    onclick: () => hideFromSite(employee)
  }));
};

/** U4: "Hide from site" with its dialog (an optional reason is stored for the admin list) */
async function hideFromSite(employee) {
  const name = employee.name || "this person";
  const reasonId = uiId("adminHideReason");
  const reason = uiEl("input", { class: "input", id: reasonId, type: "text", autocomplete: "off",
    "aria-describedby": `${reasonId}-error` });
  const error = uiEl("p", { class: "field-error", id: `${reasonId}-error`, "aria-live": "polite" });
  const handle = openDialog({
    title: `Hide ${name} from the site?`,
    body: [
      uiEl("p", { text: `${name} will no longer appear in the tree, groups or search for anyone. People reporting to`
        + " them are shown under their next manager. You can restore them in Admin > Hidden people." }),
      uiEl("div", { class: "field" }, uiEl("label", { class: "field-label", for: reasonId, text: "Reason (optional)" }),
        reason, error)],
    actions: [
      { label: "Cancel", variant: "secondary" },
      { label: "Hide", variant: "danger", onClick: async () => {
        const problem = checkField(FIELD_RULES.reason, normalizeField(reason.value));
        if (problem) { error.textContent = problem.message; return false; }
        const res = await adminJson(`/api/manage/employees/${employee.id}/hide`, "POST",
          { reason: normalizeField(reason.value) });
        if (!res.ok) {
          const refusal = await adminRefusal(res);
          error.textContent = refusal.errors?.reason?.message || refusal.message;
          return false;
        }
        const result = await res.json();
        toast([`${name} is hidden from the site.`, ...(result.warnings || [])].join(" "), "success");
        document.getElementById("profileDialog")?.close();
        if (typeof window.refreshOrgChart === "function") await window.refreshOrgChart();
        return true;
      } }
    ]
  });
  return handle;
}

const ADMIN_EDIT_FIELDS = [
  { name: "groupName", multiline: false,
    help: "2-60 characters. Leave empty to use the group from Active Directory." },
  { name: "areaOfDuties", multiline: true, rows: 2, help: "Shown on the card. Up to 120 characters." },
  { name: "jobDescription", multiline: true, rows: 3, help: "Shown on the card. Up to 200 characters." }
];

/** The member row of the person in the function (with the overlay row and the AD defaults) */
async function adminLoadMember(employee) {
  const url = `/api/manage/overrides?cs=${encodeURIComponent(employee.cs)}&q=${encodeURIComponent(employee.email || "")}`;
  const res = await apiFetch(url);
  if (!res.ok) throw new Error(await adminError(res));
  const member = (await res.json()).find((item) => item.employeeId === employee.id);
  if (!member) throw new Error(`${employee.name || "This person"} is no longer in ${employee.cs}.`);
  return member;
}

/** What Active Directory gives for a field, shown under it */
function adminAdHint(member, field) {
  if (field !== "groupName") return "Active Directory has no value for this field.";
  if (!member.isPrimaryFunction) return "Active Directory gives no group here: this is an extra function of the person.";
  return member.adDefaultGroup ? `From Active Directory: ${member.adDefaultGroup}` : "Active Directory gives no group.";
}

function adminSavedValues(member) {
  const override = member.override || {};
  return Object.fromEntries(ADMIN_EDIT_FIELDS.map((f) => [f.name, normalizeField(override[f.name])]));
}

/** The stored texts as they are (values from before the U3 rules are kept when left untouched) */
function adminStoredValues(member) {
  const override = member.override || {};
  return Object.fromEntries(ADMIN_EDIT_FIELDS.map((f) => [f.name, override[f.name] ?? null]));
}

async function openAdminEditor(employee) {
  let member;
  try {
    member = await adminLoadMember(employee);
  } catch (e) {
    toast(e.message, "error");
    return null;
  }
  const name = employee.name || "this person";
  const state = { member, saved: adminSavedValues(member), stored: adminStoredValues(member), saving: false };
  const controls = {};

  const fields = ADMIN_EDIT_FIELDS.map((field) => {
    const rule = FIELD_RULES[field.name];
    const id = `adminEdit-${field.name}`;
    const input = field.multiline
      ? uiEl("textarea", { class: "textarea", id, name: field.name, rows: field.rows, autocomplete: "off" })
      : uiEl("input", { class: "input", id, name: field.name, type: "text", autocomplete: "off" });
    input.value = (member.override && member.override[field.name]) || "";
    const help = uiEl("p", { class: "field-help", id: `${id}-help`, text: field.help });
    const hint = uiEl("p", { class: "field-help admin-ad-hint", id: `${id}-ad`, text: adminAdHint(member, field.name) });
    const current = uiEl("p", { class: "field-help admin-current", id: `${id}-current`, hidden: true });
    const error = uiEl("p", { class: "field-error", id: `${id}-error`, "aria-live": "polite" });
    const counter = uiEl("span", { class: "field-counter", id: `${id}-counter`, "aria-hidden": "true" });
    input.setAttribute("aria-describedby", `${id}-help ${id}-ad ${id}-current ${id}-error`);
    controls[field.name] = { rule, input, error, counter, current, touched: false };
    input.addEventListener("input", () => { controls[field.name].touched = true; refresh(); });
    input.addEventListener("blur", () => { controls[field.name].touched = true; refresh(); });
    return uiEl("div", { class: "field" },
      uiEl("label", { class: "field-label", for: id, text: rule.label }), input,
      uiEl("div", { class: "admin-field-meta" }, help, counter), hint, current, error);
  });

  const headNote = member.override?.headRank != null
    ? uiEl("p", { class: "field-help", text: `${name} is a head of ${employee.cs}: the group applies once they are no longer a head.` })
    : null;
  const alert = uiEl("div", { class: "admin-form-alert", role: "alert", hidden: true });
  const form = uiEl("form", { class: "admin-edit-form", novalidate: true }, headNote, fields, alert);

  const save = uiEl("button", { type: "button", class: "btn btn-primary", text: "Save" });
  const cancel = uiEl("button", { type: "button", class: "btn btn-secondary", text: "Cancel" });
  const remove = member.override ? uiEl("button", {
    type: "button", class: "btn btn-ghost admin-remove-btn",
    text: member.isPrimaryFunction ? "Reset to AD data" : "Remove from function"
  }) : null;

  // An untouched stored value is sent back as it is (the server keeps it without checking it again)
  const untouched = (k, c) => state.stored[k] != null && c.input.value === state.stored[k];
  const values = () => Object.fromEntries(Object.entries(controls)
    .map(([k, c]) => [k, untouched(k, c) ? state.stored[k] : normalizeField(c.input.value)]));
  const isDirty = () => Object.entries(controls).some(([k, c]) => !untouched(k, c) && normalizeField(c.input.value) !== state.saved[k]);
  const problems = () => Object.fromEntries(Object.entries(controls)
    .map(([k, c]) => [k, untouched(k, c) ? null : checkField(c.rule, normalizeField(c.input.value))]).filter(([, p]) => p));

  function refresh() {
    const found = problems();
    Object.entries(controls).forEach(([k, c]) => {
      const length = fieldLength(c.input.value);
      c.counter.textContent = `${length} / ${c.rule.max}`;
      c.counter.classList.toggle("is-over", length > c.rule.max);
      const problem = c.touched ? found[k] : null;
      if (!c.serverError || c.touched) c.error.textContent = problem ? problem.message : "";
      c.input.setAttribute("aria-invalid", problem ? "true" : "false");
    });
    save.disabled = state.saving || !isDirty() || Object.keys(found).length > 0;
  }

  const handle = openDialog({
    title: `Edit ${name}`,
    body: [uiEl("p", { class: "admin-edit-context", text: `In ${employee.cs}` }), form],
    wide: true,
    className: "admin-edit-dialog",
    beforeClose: () => !isDirty() || state.saving === "done" || confirmDialog({
      title: "Discard your changes?", message: "Your changes are not saved.", confirmLabel: "Discard", danger: true
    })
  });
  const footer = handle.footer;
  footer.hidden = false;
  if (remove) footer.append(remove);
  footer.append(cancel, save);
  cancel.addEventListener("click", () => handle.requestClose("cancel"));
  remove?.addEventListener("click", () => removeAdminEntry(employee, state.member, handle));

  async function submit() {
    if (state.saving) return;
    Object.values(controls).forEach((c) => { c.touched = true; c.serverError = false; });
    refresh();
    if (!isDirty() || Object.keys(problems()).length) return;
    state.saving = true;
    save.disabled = true;
    save.setAttribute("aria-busy", "true");
    alert.hidden = true;
    const body = { ...values(), version: state.member.override ? state.member.override.version : null };
    let res;
    try {
      res = await adminJson(`/api/manage/overrides/${employee.id}?cs=${encodeURIComponent(employee.cs)}`, "PUT", body);
    } catch (e) {
      res = null;
    }
    state.saving = false;
    save.removeAttribute("aria-busy");
    if (res && res.ok) {
      state.saving = "done";
      handle.close("saved");
      toast(`Saved ${name} in ${employee.cs}`, "success");
      await adminRefreshInPlace(employee);
      return;
    }
    const refusal = res ? await adminRefusal(res) : { message: "The server could not be reached. Try again." };
    if (res && res.status === 400 && refusal.errors) {
      Object.entries(refusal.errors).forEach(([k, problem]) => {
        if (!controls[k]) return;
        controls[k].error.textContent = problem.message;
        controls[k].serverError = true;
        controls[k].touched = false;
        controls[k].input.setAttribute("aria-invalid", "true");
      });
    } else if (res && res.status === 409 && refusal.code === "CHANGED") {
      showConflict();
    } else {
      showAlert(refusal.message);
    }
    save.disabled = !isDirty();
  }

  function showAlert(message, action) {
    alert.replaceChildren(uiEl("p", { text: message }), action || "");
    alert.hidden = false;
  }

  /** 409: someone else saved meanwhile. The typed text stays; "Reload values" shows the saved values and the version */
  function showConflict() {
    showAlert("Changed by someone else. Reload the values to see what was saved; your text stays in the fields.",
      uiEl("button", { type: "button", class: "btn btn-secondary btn-sm", text: "Reload values", onclick: reloadValues }));
  }

  async function reloadValues() {
    try {
      state.member = await adminLoadMember(employee);
    } catch (e) {
      showAlert(e.message);
      return;
    }
    state.saved = adminSavedValues(state.member);
    state.stored = adminStoredValues(state.member);
    Object.entries(controls).forEach(([k, c]) => {
      const savedValue = state.saved[k];
      c.current.textContent = `Saved value: ${savedValue == null ? "(empty)" : savedValue}`;
      c.current.hidden = false;
    });
    alert.hidden = true;
    refresh();
    controls.groupName.input.focus();
  }

  form.addEventListener("submit", (event) => { event.preventDefault(); submit(); });
  save.addEventListener("click", submit);
  // Enter submits from a one-line field only; in a textarea it is a line break
  form.addEventListener("keydown", (event) => {
    if (event.key === "Enter" && event.target?.tagName === "INPUT") {
      event.preventDefault();
      submit();
    }
  });
  refresh();
  controls.groupName.input.focus();
  return { handle, controls, save, form, state, submit, reloadValues };
}

/** "Reset to AD data" (own function) or "Remove from function" (extra function), each with its dialog */
async function removeAdminEntry(employee, member, editor) {
  const name = employee.name || "this person";
  const fn = employee.cs;
  const primary = member.isPrimaryFunction === true;
  const confirmed = await confirmDialog(primary ? {
    title: "Reset to Active Directory data?",
    message: `Manual changes for ${name} in ${fn} (group, area of duties, job description, head status and`
      + ` reporting line) will be removed. ${name} stays on the site with the data from Active Directory.`,
    confirmLabel: "Reset", danger: true
  } : {
    title: `Remove ${name} from ${fn}?`,
    message: `${name} will no longer appear in ${fn}. They stay in their own function.`,
    confirmLabel: "Remove", danger: true
  });
  if (!confirmed) return false;
  const url = `/api/manage/overrides/${employee.id}?cs=${encodeURIComponent(fn)}&version=${member.override.version}`;
  let res = await apiFetch(url, { method: "DELETE" });
  if (res.status === 409) {
    const refusal = await adminRefusal(res);
    if (refusal.code !== "LAST_HEAD") { toast(refusal.message, "error"); return false; }
    const anyway = await confirmDialog({
      title: `${name} is the last head of ${fn}`,
      message: "Without a head the tree of the function has no root. Continue anyway?",
      confirmLabel: primary ? "Reset" : "Remove", danger: true
    });
    if (!anyway) return false;
    res = await apiFetch(`${url}&confirmLastHead=true`, { method: "DELETE" });
  }
  if (!res.ok) { toast(await adminError(res), "error"); return false; }
  editor?.close("removed");
  toast(primary ? `${name} was reset to the Active Directory data` : `${name} was removed from ${fn}`, "success");
  await adminRefreshInPlace(employee);
  return true;
}

/* ---------- Function heads (function page) ---------- */

const AdminHeads = { cs: null, expected: [], heads: [], hidden: 0, all: false, query: "", members: [] };
const ADMIN_HEADS_OPEN_KEY = "orgchart-admin-heads-open";

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
  AdminHeads.heads = state.heads.map((h) => ({ id: h.employeeId, name: h.name, jobTitle: h.jobTitle, countryCode: h.countryCode }));
  AdminHeads.hidden = state.hiddenHeads;
  AdminHeads.members = members;
  renderAdminHeads();
};

function adminHeadsChanged() {
  return JSON.stringify(AdminHeads.heads.map((h) => h.id)) !== JSON.stringify(AdminHeads.expected);
}

/** One person: name, job title and country code only */
function adminHeadPerson(head, className) {
  return uiEl("span", { class: className },
    uiEl("span", { class: "admin-heads-name", text: head.name || "—" }),
    head.jobTitle ? uiEl("span", { class: "admin-heads-title", text: head.jobTitle }) : null,
    head.countryCode ? uiEl("span", { class: "chip admin-heads-country", text: head.countryCode }) : null);
}

function adminHeadButton(text, label, focusKey, onclick, disabled = false) {
  return uiEl("button", { type: "button", class: "btn btn-ghost btn-sm", text, "aria-label": label,
    "data-focus-key": focusKey, disabled, onclick });
}

function renderAdminHeads() {
  let section = document.getElementById("adminHeads");
  if (!section) {
    section = uiEl("section", { id: "adminHeads", class: "admin-heads", "aria-label": "Function heads" });
    const chart = document.getElementById("orgChart");
    chart.parentNode.insertBefore(section, chart);
  }
  const focusKey = document.activeElement?.getAttribute?.("data-focus-key");
  const heads = AdminHeads.heads;
  const changed = adminHeadsChanged();
  const status = uiEl("p", { class: "admin-status", role: "status", "aria-live": "polite" });
  const [main, ...additional] = heads;

  const mainSlot = main
    ? uiEl("div", { class: "admin-heads-main" },
      uiEl("span", { class: "admin-heads-label", text: "Main head" }),
      adminHeadPerson(main, "admin-heads-person admin-heads-person--main"),
      uiEl("span", { class: "admin-heads-buttons" },
        adminHeadButton("Remove", `Remove ${main.name} as head`, `remove-${main.id}`, () => { heads.splice(0, 1); renderAdminHeads(); })))
    : uiEl("p", { class: "admin-warning", text: "This function has no active head: the tree has no root." });

  const additionalList = additional.length ? uiEl("div", { class: "admin-heads-additional" },
    uiEl("span", { class: "admin-heads-label", text: "Additional heads" }),
    uiEl("ol", { class: "admin-heads-list" }, additional.map((head, i) => {
      const index = i + 1;
      return uiEl("li", { class: "admin-heads-item" },
        adminHeadPerson(head, "admin-heads-person"),
        uiEl("span", { class: "admin-heads-buttons" },
          adminHeadButton("Make main", `Make ${head.name} the main head`, `main-${head.id}`, () => makeMainHead(index)),
          adminHeadButton("↑", `Move ${head.name} up`, `up-${head.id}`, () => moveAdminHead(index, -1), index === 1),
          adminHeadButton("↓", `Move ${head.name} down`, `down-${head.id}`, () => moveAdminHead(index, 1), index === heads.length - 1),
          adminHeadButton("✕", `Remove ${head.name} as head`, `remove-${head.id}`, () => { heads.splice(index, 1); renderAdminHeads(); })));
    }))) : null;

  const search = uiEl("input", { type: "search", class: "input admin-heads-search", placeholder: "Add a head…",
    "aria-label": "Find a person to add as head", autocomplete: "off", "data-focus-key": "search" });
  search.value = AdminHeads.query;
  const allToggle = uiEl("input", { type: "checkbox", id: "adminHeadsAll", checked: AdminHeads.all, "data-focus-key": "all" });
  const results = uiEl("div", { class: "admin-heads-results", role: "list" });
  const showResults = debounce(async () => {
    AdminHeads.query = search.value;
    const people = await adminHeadCandidates(search.value);
    results.replaceChildren(...people.map((person) => uiEl("div", { role: "listitem" }, uiEl("button", {
      type: "button", class: "admin-heads-candidate", "aria-label": `Add ${person.name} as head`,
      onclick: () => { AdminHeads.heads.push(person); AdminHeads.query = ""; renderAdminHeads(); }
    }, adminHeadPerson(person, "admin-heads-person"),
    person.group ? uiEl("span", { class: "admin-heads-group", text: person.group }) : null))));
  }, 200);
  search.addEventListener("input", showResults);
  allToggle.addEventListener("change", () => { AdminHeads.all = allToggle.checked; showResults(); });

  const save = uiEl("button", { type: "button", class: "btn btn-primary btn-sm", text: "Save heads", disabled: !changed,
    "data-focus-key": "save", onclick: () => saveAdminHeads(status, save) });
  const reset = uiEl("button", { type: "button", class: "btn btn-secondary btn-sm", text: "Undo changes", disabled: !changed,
    "data-focus-key": "undo", onclick: () => { AdminHeads.heads = []; window.refreshOrgChart && window.refreshOrgChart(); } });

  const summary = uiEl("span", { class: "admin-heads-summary" },
    uiEl("span", { class: "badge", text: "Admin" }),
    heads.length ? uiEl("span", { class: "badge badge-neutral", text: `${heads.length} head${heads.length === 1 ? "" : "s"}` })
      : uiEl("span", { class: "badge badge-warning", text: "No head" }),
    changed ? uiEl("span", { class: "badge badge-warning", text: "Not saved" }) : null);

  const panel = createCollapsible({
    title: "Function heads", headerExtra: summary, storageKey: ADMIN_HEADS_OPEN_KEY, className: "admin-heads-panel",
    content: [mainSlot, additionalList,
      AdminHeads.hidden ? uiEl("p", { class: "field-help", text: `${AdminHeads.hidden} head(s) disabled in Active Directory are kept and come back when re-enabled.` }) : null,
      uiEl("div", { class: "admin-heads-add" }, search,
        uiEl("label", { class: "admin-heads-all", for: "adminHeadsAll" }, allToggle, uiEl("span", { text: "All employees" }))),
      results,
      uiEl("div", { class: "admin-actions" }, save, reset),
      status].filter(Boolean)
  });
  panel.header.setAttribute("data-focus-key", "toggle");
  section.replaceChildren(panel.element);
  if (focusKey) section.querySelector(`[data-focus-key="${focusKey}"]`)?.focus();
  if (AdminHeads.query) showResults();
}

/** The additional head at index becomes the main head; the former main head becomes the first additional one */
function makeMainHead(index) {
  const heads = AdminHeads.heads;
  const [person] = heads.splice(index, 1);
  heads.unshift(person);
  renderAdminHeads();
}

/** Moves within the additional heads (the main head changes only through "Make main") */
function moveAdminHead(index, step) {
  const heads = AdminHeads.heads;
  const target = index + step;
  if (index < 1 || target < 1 || target >= heads.length) return;
  [heads[index], heads[target]] = [heads[target], heads[index]];
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
    people = res.ok ? (await res.json()).map((m) => ({ id: m.employeeId, name: m.name, jobTitle: m.jobTitle,
      countryCode: m.countryCode, group: m.cs })) : [];
  } else {
    people = (AdminHeads.members || [])
      .filter((e) => e.id != null && [e.name, e.email, e.jobTitle].join(" ").toLowerCase().includes(q))
      .map((e) => ({ id: e.id, name: e.name, jobTitle: e.jobTitle, countryCode: e.countryCode, group: e.group }));
  }
  const seen = new Set();
  return people.filter((p) => !taken.has(p.id) && !seen.has(p.id) && seen.add(p.id)).slice(0, 8);
}

async function saveAdminHeads(status, button) {
  const employeeIds = AdminHeads.heads.map((h) => h.id);
  let confirmRemoveAllHeads = false;
  if (!employeeIds.length && AdminHeads.expected.length) {
    if (!(await confirmDialog({
      title: `Leave ${AdminHeads.cs} without a head?`, message: "The tree of the function then has no root.",
      confirmLabel: "Save without head", danger: true
    }))) return;
    confirmRemoveAllHeads = true;
  }
  if (button) { button.disabled = true; button.setAttribute("aria-busy", "true"); }
  status.textContent = "Saving…";
  const res = await adminJson(`/api/manage/functions/${encodeURIComponent(AdminHeads.cs)}/heads`, "PUT",
    { employeeIds, expectedHeads: AdminHeads.expected, confirmRemoveAllHeads });
  if (button) button.removeAttribute("aria-busy");
  if (!res.ok) {
    const refusal = await adminRefusal(res);
    status.textContent = refusal.code === "HEADS_CHANGED"
      ? "Changed by someone else. Use Undo changes to load the current heads." : refusal.message;
    if (button) button.disabled = false;
    return;
  }
  status.textContent = "";
  toast("Heads saved", "success");
  if (typeof window.refreshOrgChart === "function") await window.refreshOrgChart();
}

// Loaded after the function page may already have rendered: show the heads panel for that render
if (window.lastFunctionRender && typeof window.onFunctionRendered === "function") {
  window.onFunctionRendered(window.lastFunctionRender.cs, window.lastFunctionRender.members);
}
