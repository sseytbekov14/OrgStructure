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

async function initAdminPage() {
  const me = await loadMe();
  document.getElementById("adminYou").textContent = me.you ? `You are signed in as ${me.you}.` : "";
  if (me.superAdmin === true) {
    document.getElementById("adminAdmins").hidden = false;
    document.getElementById("adminAddForm").addEventListener("submit", addAdmin);
    await loadAdmins();
  } else {
    document.getElementById("adminNotSuper").hidden = false;
  }
}

initAdminPage();
