/* The admin page (admin.html, served to admins only, U1): the list of administrators for super admins.
   Everything is built with createElement and textContent: names and notes never become HTML. */

const AdminPage = { armed: null };

async function adminPageError(res) {
  try {
    const body = await res.json();
    return body.message || `Error ${res.status}`;
  } catch (e) {
    return `Error ${res.status}`;
  }
}

function adminCell(text) {
  const cell = document.createElement("td");
  cell.textContent = text == null || text === "" ? "—" : String(text);
  return cell;
}

function renderAdminRows(admins) {
  const rows = document.getElementById("adminAdminsRows");
  rows.replaceChildren(...admins.map((admin) => {
    const row = document.createElement("tr");
    const remove = document.createElement("button");
    remove.type = "button";
    remove.className = "profile-btn admin-danger";
    const armed = AdminPage.armed === admin.id;
    remove.textContent = armed ? "Confirm removal" : "Remove";
    remove.setAttribute("aria-label", armed ? `Confirm removal of ${admin.loginName}` : `Remove ${admin.loginName}`);
    remove.addEventListener("click", () => removeAdmin(admin, admins));
    const actions = document.createElement("td");
    actions.append(remove);
    row.append(adminCell(admin.loginName), adminCell(admin.note), adminCell(admin.addedBy),
      adminCell(admin.addedAt ? new Date(admin.addedAt).toLocaleDateString() : ""), actions);
    return row;
  }));
  if (!admins.length) {
    const row = document.createElement("tr");
    const cell = adminCell("No administrators in the list yet.");
    cell.colSpan = 5;
    row.append(cell);
    rows.append(row);
  }
}

function adminStatus(text) {
  document.getElementById("adminAdminsStatus").textContent = text || "";
}

async function loadAdmins() {
  const res = await apiFetch("/api/manage/admins");
  if (!res.ok) { adminStatus(await adminPageError(res)); return; }
  renderAdminRows(await res.json());
}

/** Two clicks remove: the first arms the button, the second removes (no browser dialogs) */
async function removeAdmin(admin, admins) {
  if (AdminPage.armed !== admin.id) {
    AdminPage.armed = admin.id;
    renderAdminRows(admins);
    return;
  }
  AdminPage.armed = null;
  const res = await apiFetch(`/api/manage/admins/${encodeURIComponent(admin.id)}`, { method: "DELETE" });
  if (!res.ok) { adminStatus(await adminPageError(res)); return; }
  adminStatus(`${admin.loginName} removed`);
  await loadAdmins();
}

async function addAdmin(event) {
  event.preventDefault();
  const form = event.target;
  const loginName = form.loginName.value.trim();
  if (!/^[A-Za-z0-9._@+-]{3,256}$/.test(loginName)) {
    adminStatus("The login name must have 3 to 256 characters: letters A-Z, digits and . _ @ + -");
    form.loginName.focus();
    return;
  }
  const res = await apiFetch("/api/manage/admins", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ loginName, note: form.note.value })
  });
  if (!res.ok) { adminStatus(await adminPageError(res)); return; }
  form.reset();
  adminStatus(`${loginName} added`);
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
