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
