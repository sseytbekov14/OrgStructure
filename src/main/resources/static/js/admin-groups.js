/* Groups management of the function page (U4, admins with EMPLOYEE_SOURCE=db): a "Manage groups" toggle shows every
   group of the function (also empty ones) with a menu (rename, move left / right, delete when empty), a "New group"
   tile, "Add people" (search, function members first, "All employees"), and on every card "Move to group…" and
   "Remove from group". Moves show a toast with Undo. Loaded by common.js after admin.js, for admins only.
   DOM APIs and textContent only. */

const AdminGroups = { cs: null, members: [], data: null, managing: false };

(function hookFunctionRender() {
  const previous = window.onFunctionRendered;
  window.onFunctionRendered = async (cs, members) => {
    if (typeof previous === "function") await previous(cs, members);
    if (!(await loadAdminSession())) return;
    AdminGroups.cs = cs;
    AdminGroups.members = members || [];
    renderManageToggle();
    if (AdminGroups.managing) await loadAdminGroups();
  };
})();

function adminGroupsUrl(cs) {
  return `/api/manage/functions/${encodeURIComponent(cs)}/groups`;
}

function renderManageToggle() {
  let toggle = document.getElementById("adminGroupsToggle");
  if (!toggle) {
    toggle = uiEl("button", { type: "button", id: "adminGroupsToggle", class: "btn btn-secondary btn-sm admin-groups-toggle",
      "aria-pressed": "false", onclick: () => setManaging(!AdminGroups.managing) }, "Manage groups");
    const chart = document.getElementById("orgChart");
    chart.parentNode.insertBefore(toggle, chart);
  }
  toggle.setAttribute("aria-pressed", AdminGroups.managing ? "true" : "false");
  toggle.textContent = AdminGroups.managing ? "Done managing groups" : "Manage groups";
}

async function setManaging(on) {
  AdminGroups.managing = on;
  renderManageToggle();
  const chart = document.getElementById("orgChart");
  let manage = document.getElementById("adminGroupsManage");
  if (!on) {
    if (manage) manage.remove();
    chart.hidden = false;
    return;
  }
  if (!manage) {
    manage = uiEl("section", { id: "adminGroupsManage", class: "admin-groups", "aria-label": "Manage groups" });
    chart.parentNode.insertBefore(manage, chart);
  }
  chart.hidden = true;
  await loadAdminGroups();
}

async function loadAdminGroups() {
  const manage = document.getElementById("adminGroupsManage");
  if (!manage) return;
  let res;
  try {
    res = await apiFetch(adminGroupsUrl(AdminGroups.cs));
  } catch (e) {
    res = null;
  }
  if (!res || !res.ok) {
    manage.replaceChildren(uiEl("p", { class: "state-error", text: res ? await adminError(res) : "The groups could not be loaded." }));
    return;
  }
  AdminGroups.data = await res.json();
  renderAdminGroups();
}

/** Every group with its members; the people without a group last */
function renderAdminGroups() {
  const manage = document.getElementById("adminGroupsManage");
  const data = AdminGroups.data;
  if (!manage || !data) return;
  const focusKey = document.activeElement?.getAttribute?.("data-focus-key");
  const groups = data.groups || [];
  const columns = groups.map((group, index) => renderGroupColumn(group, index, groups.length));
  if ((data.ungrouped || []).length) {
    columns.push(uiEl("section", { class: "admin-group-column admin-group-column--none", "aria-label": "Without a group" },
      uiEl("header", { class: "admin-group-header" }, uiEl("h3", { class: "admin-group-name", text: "Without a group" }),
        uiEl("span", { class: "badge badge-neutral", text: String(data.ungrouped.length) })),
      uiEl("ul", { class: "admin-group-members" }, data.ungrouped.map((m) => renderGroupMember(m, null)))));
  }
  columns.push(uiEl("button", { type: "button", class: "admin-group-new", "data-focus-key": "new-group",
    onclick: () => openNewGroupDialog() }, uiEl("span", { "aria-hidden": "true", text: "+" }), uiEl("span", { text: "New group" })));
  manage.replaceChildren(
    uiEl("p", { class: "field-help", text: "Moves are saved at once and can be undone from the message that follows." }),
    uiEl("div", { class: "admin-groups-grid" }, columns));
  if (focusKey) manage.querySelector(`[data-focus-key="${focusKey}"]`)?.focus();
}

/* ---------- The "⋯" menus of the groups ---------- */

// One menu open at a time. The menus have no listeners of their own: one click and one keydown listener on the
// document (installed once, so re-rendering the groups adds none) and resize / scroll of the window close them.
// The action of each item is kept here, keyed by the item button (gone with the button on the next render).
const groupMenuActions = new WeakMap();

function groupMenuTrigger(menu) {
  return menu.closest(".admin-group-menu-wrap")?.querySelector(".admin-group-menu-btn") || null;
}

/** Closes the open menu; returns its trigger button (null when none was open) */
function closeGroupMenus() {
  let trigger = null;
  document.querySelectorAll(".admin-group-menu").forEach((menu) => {
    if (menu.hidden) return;
    menu.hidden = true;
    trigger = groupMenuTrigger(menu);
    trigger?.setAttribute("aria-expanded", "false");
  });
  return trigger;
}

function toggleGroupMenu(trigger) {
  const menu = document.getElementById(trigger.getAttribute("aria-controls"));
  const wasOpen = menu && !menu.hidden;
  closeGroupMenus();
  if (!menu || wasOpen) return;
  menu.hidden = false;
  trigger.setAttribute("aria-expanded", "true");
  [...menu.querySelectorAll("button")].find((b) => !b.disabled)?.focus({ preventScroll: true });
}

document.addEventListener("click", (event) => {
  const target = event.target;
  const trigger = target?.closest?.(".admin-group-menu-btn");
  if (trigger) {
    toggleGroupMenu(trigger);
    return;
  }
  const item = target?.closest?.(".admin-group-menu-item");
  if (item) {
    closeGroupMenus();
    const action = groupMenuActions.get(item);
    if (action && !item.disabled) action();
    return;
  }
  if (!target?.closest?.(".admin-group-menu")) closeGroupMenus();
});

document.addEventListener("keydown", (event) => {
  if (event.key !== "Escape") return;
  const trigger = closeGroupMenus();
  if (!trigger) return;
  event.preventDefault();
  trigger.focus();
});

window.addEventListener("resize", () => closeGroupMenus());
// Capture: also the scrolling of the page's own containers
window.addEventListener("scroll", () => closeGroupMenus(), { capture: true, passive: true });

function groupKindLabel(group) {
  if (group.kind === "COUNTRY") return "Country";
  return group.source === "AUTO" ? "From AD" : "Custom";
}

function renderGroupColumn(group, index, count) {
  const menuId = `adminGroupMenu-${group.id}`;
  const menuButton = uiEl("button", { type: "button", class: "btn btn-ghost btn-sm admin-group-menu-btn",
    "aria-haspopup": "menu", "aria-expanded": "false", "aria-controls": menuId,
    "aria-label": `Actions of group ${group.name}`, "data-focus-key": `menu-${group.id}`, text: "⋯" });
  const items = [
    ["Rename", () => openRenameDialog(group), group.source === "AUTO"],
    ["Move left", () => moveGroup(group, index - 1), index === 0],
    ["Move right", () => moveGroup(group, index + 1), index === count - 1],
    ["Delete", () => deleteGroup(group), group.memberCount > 0]
  ];
  const menu = uiEl("ul", { class: "admin-group-menu", id: menuId, role: "menu", hidden: true },
    items.map(([label, action, disabled]) => {
      const item = uiEl("button", {
        type: "button", role: "menuitem", class: "admin-group-menu-item", text: label, disabled,
        title: disabled && label === "Delete" ? "Only an empty group can be deleted" : disabled && label === "Rename"
          ? "Groups from Active Directory keep their name" : null
      });
      groupMenuActions.set(item, action);
      return uiEl("li", { role: "none" }, item);
    }));
  return uiEl("section", { class: "admin-group-column", "aria-label": `Group ${group.name}`, "data-group-id": String(group.id) },
    uiEl("header", { class: "admin-group-header" },
      uiEl("h3", { class: "admin-group-name", text: group.name }),
      uiEl("span", { class: "badge badge-neutral", text: groupKindLabel(group) }),
      uiEl("span", { class: "badge", text: String(group.memberCount), "aria-label": `${group.memberCount} people` }),
      uiEl("span", { class: "admin-group-menu-wrap" }, menuButton, menu)),
    group.members.length ? uiEl("ul", { class: "admin-group-members" }, group.members.map((m) => renderGroupMember(m, group)))
      : uiEl("p", { class: "field-help admin-group-empty", text: "No people yet." }),
    uiEl("button", { type: "button", class: "btn btn-secondary btn-sm admin-group-add", "data-focus-key": `add-${group.id}`,
      onclick: () => openAddPeopleDialog(group) }, "Add people"));
}

function renderGroupMember(member, group) {
  const byAd = member.source === "AD";
  return uiEl("li", { class: "admin-group-member", "data-employee-id": String(member.employeeId) },
    uiEl("span", { class: "admin-heads-person" },
      uiEl("span", { class: "admin-heads-name", text: member.name || "—" }),
      member.jobTitle ? uiEl("span", { class: "admin-heads-title", text: member.jobTitle }) : null,
      member.countryCode ? uiEl("span", { class: "chip", text: member.countryCode }) : null,
      group ? uiEl("span", { class: "badge badge-neutral", text: byAd ? "AD" : "Manual" }) : null),
    uiEl("span", { class: "admin-heads-buttons" },
      uiEl("button", { type: "button", class: "btn btn-ghost btn-sm", text: "Move to group…",
        "aria-label": `Move ${member.name} to another group`, "data-focus-key": `move-${member.employeeId}`,
        onclick: () => openMoveDialog(member, group) }),
      group ? uiEl("button", { type: "button", class: "btn btn-ghost btn-sm", text: "Remove from group",
        "aria-label": `Remove ${member.name} from ${group.name}`, disabled: byAd,
        title: byAd ? "Belongs here by Active Directory: move them to another group" : null,
        onclick: () => removeFromGroup(member, group) }) : null));
}

/* ---------- Group actions ---------- */

function groupNameField(initial = "") {
  const id = uiId("adminGroupName");
  const input = uiEl("input", { class: "input", id, name: "name", type: "text", autocomplete: "off",
    "aria-describedby": `${id}-help ${id}-error` });
  input.value = initial;
  const error = uiEl("p", { class: "field-error", id: `${id}-error`, "aria-live": "polite" });
  const field = uiEl("div", { class: "field" },
    uiEl("label", { class: "field-label", for: id, text: "Group name" }), input,
    uiEl("p", { class: "field-help", id: `${id}-help`, text: "2-60 characters." }), error);
  const check = () => {
    const problem = checkField(FIELD_RULES.name, normalizeField(input.value));
    error.textContent = problem ? problem.message : "";
    input.setAttribute("aria-invalid", problem ? "true" : "false");
    return problem ? null : normalizeField(input.value);
  };
  return { field, input, error, check };
}

function showRefusal(refusal, fieldError) {
  const problem = refusal.errors && (refusal.errors.name || Object.values(refusal.errors)[0]);
  if (problem && fieldError) fieldError.textContent = problem.message;
  else toast(refusal.message, "error");
}

function openNewGroupDialog() {
  const name = groupNameField();
  const kindId = uiId("adminGroupKind");
  const kind = uiEl("select", { class: "select", id: kindId, name: "kind" },
    uiEl("option", { value: "TEAM", text: "Team" }), uiEl("option", { value: "COUNTRY", text: "Country (two-letter code)" }));
  openDialog({
    title: `New group in ${AdminGroups.cs}`,
    body: [name.field, uiEl("div", { class: "field" }, uiEl("label", { class: "field-label", for: kindId, text: "Kind" }), kind)],
    actions: [
      { label: "Cancel", variant: "secondary" },
      { label: "Create", variant: "primary", onClick: async () => {
        const value = name.check();
        if (!value) return false;
        const res = await adminJson(adminGroupsUrl(AdminGroups.cs), "POST", { name: value, kind: kind.value });
        if (!res.ok) { showRefusal(await adminRefusal(res), name.error); return false; }
        toast(`Group ${value} created`, "success");
        await loadAdminGroups();
        return true;
      } }
    ]
  });
}

function openRenameDialog(group) {
  const name = groupNameField(group.name);
  openDialog({
    title: `Rename ${group.name}`,
    body: [name.field],
    actions: [
      { label: "Cancel", variant: "secondary" },
      { label: "Rename", variant: "primary", onClick: async () => {
        const value = name.check();
        if (!value) return false;
        const res = await adminJson(`/api/manage/groups/${group.id}`, "PUT", { name: value, version: group.version });
        if (!res.ok) { showRefusal(await adminRefusal(res), name.error); return false; }
        toast(`Renamed to ${value}`, "success");
        await adminGroupsChanged();
        return true;
      } }
    ]
  });
}

async function moveGroup(group, position) {
  const res = await adminJson(`/api/manage/groups/${group.id}`, "PUT", { position, version: group.version });
  if (!res.ok) { toast(await adminError(res), "error"); return; }
  await adminGroupsChanged();
}

async function deleteGroup(group) {
  const confirmed = await confirmDialog({ title: `Delete ${group.name}?`, message: "The empty group is removed.",
    confirmLabel: "Delete", danger: true });
  if (!confirmed) return;
  const res = await apiFetch(`/api/manage/groups/${group.id}?version=${group.version}`, { method: "DELETE" });
  if (!res.ok) { toast(await adminError(res), "error"); return; }
  toast(`Group ${group.name} deleted`, "success");
  await adminGroupsChanged();
}

/** After a change: the manage view and the chart behind it */
async function adminGroupsChanged() {
  await loadAdminGroups();
  if (typeof window.refreshOrgChart === "function") await window.refreshOrgChart();
}

/* ---------- Moves ---------- */

/** POST the people to the group; the toast offers Undo (back to the previous group, or to the AD default group) */
async function moveToGroup(group, employeeIds, label) {
  const res = await adminJson(`/api/manage/groups/${group.id}/members`, "POST", { employeeIds });
  if (!res.ok) { toast(await adminError(res), "error"); return false; }
  const result = await res.json();
  toast(label || `Moved to ${group.name}`, "success", { undo: () => undoMove(group, result.moved || []) });
  await adminGroupsChanged();
  return true;
}

async function undoMove(group, moved) {
  const back = new Map();
  for (const m of moved) {
    if (m.previousGroupId === group.id) continue;
    if (m.previousGroupId == null) {
      await apiFetch(`/api/manage/groups/${group.id}/members/${m.employeeId}`, { method: "DELETE" });
    } else {
      back.set(m.previousGroupId, [...(back.get(m.previousGroupId) || []), m.employeeId]);
    }
  }
  for (const [groupId, employeeIds] of back) {
    await adminJson(`/api/manage/groups/${groupId}/members`, "POST", { employeeIds });
  }
  toast("Move undone", "info");
  await adminGroupsChanged();
}

function openMoveDialog(member, current) {
  const groups = (AdminGroups.data?.groups || []).filter((g) => g.active && (!current || g.id !== current.id));
  if (!groups.length) { toast("There is no other group: create one first.", "info"); return; }
  const name = `adminMoveTarget-${member.employeeId}`;
  const options = groups.map((g, i) => {
    const id = `${name}-${g.id}`;
    return uiEl("div", { class: "admin-radio" },
      uiEl("input", { type: "radio", id, name, value: String(g.id), checked: i === 0 }),
      uiEl("label", { for: id, text: `${g.name} (${g.memberCount})` }));
  });
  openDialog({
    title: `Move ${member.name}`,
    body: [uiEl("p", { text: current ? `Now in ${current.name}.` : "Now without a group." }),
      uiEl("fieldset", { class: "admin-radios" }, uiEl("legend", { class: "field-label", text: "To group" }), options)],
    actions: [
      { label: "Cancel", variant: "secondary" },
      { label: "Move", variant: "primary", onClick: async (event, handle) => {
        const chosen = handle.element.querySelector(`input[name="${name}"]:checked`) ||
          [...handle.element.querySelectorAll("input[type=radio]")].find((r) => r.checked);
        const target = groups.find((g) => String(g.id) === chosen?.value);
        if (!target) return false;
        await moveToGroup(target, [member.employeeId], `${member.name} moved to ${target.name}`);
        return true;
      } }
    ]
  });
}

async function removeFromGroup(member, group) {
  const res = await apiFetch(`/api/manage/groups/${group.id}/members/${member.employeeId}`, { method: "DELETE" });
  if (!res.ok) { toast(await adminError(res), "error"); return; }
  toast(`${member.name} is back in the group from Active Directory`, "success", {
    undo: async () => { await adminJson(`/api/manage/groups/${group.id}/members`, "POST", { employeeIds: [member.employeeId] });
      await adminGroupsChanged(); }
  });
  await adminGroupsChanged();
}

/** Function members first, with their current group; "All employees" searches the whole site */
function openAddPeopleDialog(group) {
  const state = { all: false, query: "", chosen: new Map() };
  const searchId = uiId("adminAddSearch");
  const search = uiEl("input", { class: "input", id: searchId, type: "search", autocomplete: "off" });
  const allId = uiId("adminAddAll");
  const all = uiEl("input", { type: "checkbox", id: allId });
  const list = uiEl("ul", { class: "admin-add-list", "aria-label": "People" });
  const summary = uiEl("p", { class: "field-help", "aria-live": "polite" });

  async function people() {
    const q = state.query.trim().toLowerCase();
    if (state.all) {
      if (q.length < 2) return [];
      const res = await apiFetch(`/api/manage/overrides?q=${encodeURIComponent(q)}`);
      if (!res.ok) return [];
      return (await res.json()).map((m) => ({ id: m.employeeId, name: m.name, jobTitle: m.jobTitle, group: `${m.cs}${m.group ? ` · ${m.group}` : ""}` }));
    }
    return AdminGroups.members
      .filter((e) => e.id != null && (!q || [e.name, e.email, e.jobTitle].join(" ").toLowerCase().includes(q)))
      .map((e) => ({ id: e.id, name: e.name, jobTitle: e.jobTitle, group: e.group || "No group" }));
  }

  async function render() {
    const seen = new Set();
    const rows = (await people()).filter((p) => !seen.has(p.id) && seen.add(p.id)).slice(0, 50);
    list.replaceChildren(...rows.map((p) => {
      const id = `adminAdd-${group.id}-${p.id}`;
      const box = uiEl("input", { type: "checkbox", id, checked: state.chosen.has(p.id) });
      box.addEventListener("change", () => {
        if (box.checked) state.chosen.set(p.id, p); else state.chosen.delete(p.id);
        update();
      });
      return uiEl("li", { class: "admin-add-row" }, box,
        uiEl("label", { for: id }, uiEl("span", { class: "admin-heads-name", text: p.name }),
          p.jobTitle ? uiEl("span", { class: "admin-heads-title", text: ` ${p.jobTitle}` }) : null,
          uiEl("span", { class: "admin-heads-group", text: ` · ${p.group}` })));
    }));
    if (!rows.length) list.append(uiEl("li", { class: "field-help", text: state.all ? "Type at least 2 characters." : "Nobody found." }));
  }

  const handle = openDialog({
    title: `Add people to ${group.name}`,
    wide: true,
    body: [
      uiEl("div", { class: "field" }, uiEl("label", { class: "field-label", for: searchId, text: "Find people" }), search),
      uiEl("label", { class: "admin-heads-all", for: allId }, all, uiEl("span", { text: "All employees" })),
      list, summary],
    actions: [
      { label: "Cancel", variant: "secondary" },
      { label: "Add", variant: "primary", onClick: async () => {
        if (!state.chosen.size) return false;
        return moveToGroup(group, [...state.chosen.keys()], `${state.chosen.size} added to ${group.name}`);
      } }
    ]
  });
  const add = [...handle.footer.querySelectorAll("button")].find((b) => b.textContent === "Add");
  function update() {
    summary.textContent = state.chosen.size ? `${state.chosen.size} selected` : "";
    add.disabled = state.chosen.size === 0;
  }
  search.addEventListener("input", debounce(() => { state.query = search.value; render(); }, 200));
  all.addEventListener("change", () => { state.all = all.checked; render(); });
  update();
  render();
  return handle;
}

// Loaded after the function page may already have rendered
if (window.lastFunctionRender) {
  loadAdminSession().then((ok) => {
    if (!ok) return;
    AdminGroups.cs = window.lastFunctionRender.cs;
    AdminGroups.members = window.lastFunctionRender.members || [];
    renderManageToggle();
  });
}
