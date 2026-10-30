/* Groups management of the function page (U4, admins with EMPLOYEE_SOURCE=db): a "Manage groups" toggle shows every
   group of the function (also empty ones) with a menu (rename, move left / right, delete when empty), a "New group"
   tile, "Add people" (search, function members first with their group, "All employees": it adds and it moves people
   between groups), and on every card "Remove from group". Moves show a toast with Undo. People inside a group change places with the arrows of Manage
   groups, with the small bar that appears on their card in Tree and Groups, or with Alt + arrow keys on a card.
   Loaded by common.js after admin.js, for admins only. DOM APIs and textContent only. */

const AdminGroups = { cs: null, members: [], data: null, managing: false };

(function hookFunctionRender() {
  const previous = window.onFunctionRendered;
  window.onFunctionRendered = async (cs, members) => {
    if (typeof previous === "function") await previous(cs, members);
    if (!(await loadAdminSession())) return;
    AdminGroups.cs = cs;
    AdminGroups.members = members || [];
    renderManageToggle();
    initOrderBar();
    reanchorOrderBar();
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
      uiEl("ul", { class: "admin-group-members" }, data.ungrouped.map((m, i) => renderGroupMember(m, null, i, data.ungrouped))),
      data.ungrouped.length > 1 ? uiEl("button", { type: "button", class: "btn btn-ghost btn-sm", "data-focus-key": "reset-ungrouped",
        onclick: () => resetGroupOrder("Without a group", ungroupedOrderUrl()) }, "Reset order (by grade)") : null));
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

window.addEventListener("resize", () => { closeGroupMenus(); leaveOrderBar(); });
// Capture: also the scrolling of the page's own containers
window.addEventListener("scroll", () => { closeGroupMenus(); leaveOrderBar(); }, { capture: true, passive: true });

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
    ["Reset order (by grade)", () => resetGroupOrder(group.name, groupOrderUrl(group.id)), group.members.length < 2],
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
    group.members.length ? uiEl("ul", { class: "admin-group-members" }, group.members.map((m, i) => renderGroupMember(m, group, i, group.members)))
      : uiEl("p", { class: "field-help admin-group-empty", text: "No people yet." }),
    uiEl("button", { type: "button", class: "btn btn-secondary btn-sm admin-group-add", "data-focus-key": `add-${group.id}`,
      onclick: () => openAddPeopleDialog(group) }, "Add people"));
}

function memberOrderButton(direction, member, group, index, members) {
  const up = direction === "up";
  const label = `Move ${member.name || "this person"} ${direction}`;
  const button = uiEl("button", { type: "button", class: "icon-btn admin-group-order-btn", "aria-label": label, title: label,
    "data-focus-key": `order-${direction}-${member.employeeId}`, onclick: () => moveMemberInGroup(group ? groupOrderUrl(group.id) : ungroupedOrderUrl(), members, index, up ? -1 : 1) },
  orderIcon(up ? ORDER_ICON_UP : ORDER_ICON_DOWN));
  button.disabled = up ? index === 0 : index === members.length - 1;
  return button;
}

function renderGroupMember(member, group, index, members) {
  const byAd = member.source === "AD";
  return uiEl("li", { class: "admin-group-member", "data-employee-id": String(member.employeeId) },
    members.length > 1 ? uiEl("span", { class: "admin-group-order" }, memberOrderButton("up", member, group, index, members),
      memberOrderButton("down", member, group, index, members)) : null,
    uiEl("span", { class: "admin-heads-person" },
      uiEl("span", { class: "admin-heads-name", text: member.name || "—" }),
      member.jobTitle ? uiEl("span", { class: "admin-heads-title", text: member.jobTitle }) : null,
      member.countryCode ? uiEl("span", { class: "chip", text: member.countryCode }) : null,
      group ? uiEl("span", { class: "badge badge-neutral", text: byAd ? "AD" : "Manual" }) : null),
    uiEl("span", { class: "admin-heads-buttons" },
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

/* ---------- The order inside a group ---------- */

const ORDER_ICON_UP = "m18 15-6-6-6 6";
const ORDER_ICON_DOWN = "m6 9 6 6 6-6";

/** A chevron as an SVG element (no HTML strings) */
function orderIcon(path) {
  const ns = "http://www.w3.org/2000/svg";
  const svg = document.createElementNS(ns, "svg");
  Object.entries({ viewBox: "0 0 24 24", width: "16", height: "16", fill: "none", stroke: "currentColor",
    "stroke-width": "2.5", "aria-hidden": "true" }).forEach(([name, value]) => svg.setAttribute(name, value));
  const line = document.createElementNS(ns, "path");
  line.setAttribute("d", path);
  svg.appendChild(line);
  return svg;
}

/** The ids of the group after one person moved right before or right after another */
function movedOrder(ids, id, targetId, before) {
  const rest = ids.filter((other) => other !== id);
  rest.splice(rest.indexOf(targetId) + (before ? 0 : 1), 0, id);
  return rest;
}

function groupOrderUrl(groupId) {
  return `/api/manage/groups/${groupId}/order`;
}

/** The people of the function without a group */
function ungroupedOrderUrl() {
  return `/api/manage/functions/${encodeURIComponent(AdminGroups.cs)}/order`;
}

/** Manage groups: the arrows of a member */
async function moveMemberInGroup(url, members, index, step) {
  const ids = members.map((m) => m.employeeId);
  const target = ids[index + step];
  if (target == null) return;
  const res = await adminJson(url, "PUT", { employeeIds: movedOrder(ids, ids[index], target, step < 0) });
  if (!res.ok) { toast(await adminError(res), "error"); return; }
  await adminGroupsChanged();
}

async function resetGroupOrder(name, url) {
  const res = await apiFetch(url, { method: "DELETE" });
  if (!res.ok) { toast(await adminError(res), "error"); return; }
  toast(`${name}: back to the order by grade`, "success");
  await adminGroupsChanged();
}

// One bar for the whole page, kept outside the chart: the cards are buttons (no buttons inside them) and the tree
// cards move and zoom. It sits on the card under the mouse or the keyboard focus. While the mouse is on the bar it
// stays there and keeps moving the same person (their card is outlined): the next click goes on with that person,
// however far the card has moved from the pointer.
const OrderBar = { el: null, up: null, down: null, status: null, empId: null, card: null, saving: false };

function orderEmployee(card) {
  return card ? employeeDirectory[Number(card.dataset.empId)] : null;
}

function orderCardOf(empId) {
  const emp = employeeDirectory.find((e) => e.id === empId);
  return emp ? document.querySelector(`#orgChart .emp-card[data-emp-id="${emp._id}"]`) : null;
}

function initOrderBar() {
  if (OrderBar.el) return;
  OrderBar.up = uiEl("button", { type: "button", class: "order-bar-btn", onclick: () => moveOnCard(-1) }, orderIcon(ORDER_ICON_UP));
  OrderBar.down = uiEl("button", { type: "button", class: "order-bar-btn", onclick: () => moveOnCard(1) }, orderIcon(ORDER_ICON_DOWN));
  OrderBar.el = uiEl("div", { class: "order-bar", role: "group", "aria-label": "Order in the group", hidden: true },
    OrderBar.up, OrderBar.down);
  OrderBar.status = uiEl("p", { class: "visually-hidden", "aria-live": "polite" });
  document.body.append(OrderBar.el, OrderBar.status);
  OrderBar.el.addEventListener("mouseleave", (event) => {
    if (!OrderBar.card?.contains(event.relatedTarget)) hideOrderBar();
  });

  const chart = document.getElementById("orgChart");
  const cardOf = (event) => event.target?.closest?.(".emp-card") || null;
  // mousemove, not mouseover: scrolling hides the bar, and the next small movement on the same card brings it back
  chart.addEventListener("mousemove", (event) => {
    const card = cardOf(event);
    if (card && card !== OrderBar.card) showOrderBar(card);
  });
  chart.addEventListener("mouseout", (event) => {
    const to = event.relatedTarget;
    if (cardOf(event) && !OrderBar.el.contains(to) && !OrderBar.card?.contains(to)) hideOrderBar();
  });
  chart.addEventListener("focusin", (event) => {
    const card = cardOf(event);
    if (card) showOrderBar(card);
  });
  chart.addEventListener("focusout", (event) => {
    if (cardOf(event) && !OrderBar.el.contains(event.relatedTarget)) hideOrderBar();
  });
  chart.addEventListener("keydown", (event) => {
    const emp = orderEmployee(cardOf(event));
    if (!emp || !event.altKey) return;
    const place = orderSiblings(emp);
    const keys = place?.horizontal ? ["ArrowLeft", "ArrowRight"] : ["ArrowUp", "ArrowDown"];
    const step = keys.indexOf(event.key);
    if (step < 0 || !place) return;
    event.preventDefault();
    moveOnCard(step === 0 ? -1 : 1, cardOf(event));
  });
  // The cards move away under the bar when the tree is panned or zoomed (scrolling and resizing: see the menus)
  chart.addEventListener("wheel", leaveOrderBar, { passive: true });
  chart.addEventListener("pointerdown", hideOrderBar);
}

/** The page moved: the bar goes, unless the mouse is on it (redrawing the chart scrolls too) */
function leaveOrderBar() {
  if (!OrderBar.el?.matches(":hover")) hideOrderBar();
}

function hideOrderBar() {
  if (!OrderBar.el || OrderBar.saving) return;
  OrderBar.el.hidden = true;
  OrderBar.card?.classList.remove("emp-card--ordering");
  OrderBar.card = null;
  OrderBar.empId = null;
}

/** The bar for this card: labels, the ends of the list. False when the person has nobody to swap with */
function updateOrderBar(card) {
  const emp = orderEmployee(card);
  const place = emp ? orderSiblings(emp) : null;
  if (!OrderBar.el || !place || place.list.length < 2) return false;
  const index = place.list.findIndex((other) => other.id === emp.id);
  const [before, after] = place.horizontal ? ["left", "right"] : ["up", "down"];
  const name = emp.name || "this person";
  [[OrderBar.up, before, index === 0], [OrderBar.down, after, index === place.list.length - 1]].forEach(([button, word, end]) => {
    const label = `Move ${name} ${word}`;
    button.setAttribute("aria-label", label);
    button.title = `${label} (Alt + arrow key)`;
    button.disabled = end;
  });
  OrderBar.el.classList.toggle("order-bar--horizontal", place.horizontal);
  OrderBar.el.hidden = false;
  if (OrderBar.card !== card) OrderBar.card?.classList.remove("emp-card--ordering");
  card.classList.add("emp-card--ordering");
  OrderBar.card = card;
  OrderBar.empId = emp.id;
  return true;
}

function showOrderBar(card) {
  if (!updateOrderBar(card)) { hideOrderBar(); return; }
  const rect = card.getBoundingClientRect();
  const bar = OrderBar.el.getBoundingClientRect();
  OrderBar.el.style.left = `${Math.max(4, rect.right - bar.width - 6)}px`;
  OrderBar.el.style.top = `${Math.max(4, rect.top + (rect.height - bar.height) / 2)}px`;
}

/** After the chart was drawn again: the bar stays with the same person (and under the mouse, when it is there) */
function reanchorOrderBar() {
  if (!OrderBar.el || OrderBar.empId == null) return;
  const card = orderCardOf(OrderBar.empId);
  OrderBar.card = card;
  if (!card) hideOrderBar();
  else if (OrderBar.el.matches(":hover")) updateOrderBar(card);
  else showOrderBar(card);
}

/**
 * The person of the bar (with the keyboard: of the focused card) one place up or down: the whole group is saved in
 * its new order
 */
async function moveOnCard(step, focused = null) {
  const emp = orderEmployee(focused || OrderBar.card);
  const place = emp ? orderSiblings(emp) : null;
  if (!place || OrderBar.saving) return;
  const index = place.list.findIndex((other) => other.id === emp.id);
  const target = place.list[index + step];
  if (!target) return;
  const group = orderGroupOf(emp);
  const ids = groupMembersInOrder(group).map((member) => member.id);
  OrderBar.saving = true;
  OrderBar.up.disabled = true;
  OrderBar.down.disabled = true;
  let res;
  try {
    res = await adminJson(group === OTHER_GROUP ? ungroupedOrderUrl() : groupOrderUrl(group), "PUT",
      { employeeIds: movedOrder(ids, emp.id, target.id, step < 0) });
    if (res.ok && typeof window.refreshOrgChart === "function") await window.refreshOrgChart();
  } finally {
    OrderBar.saving = false;
  }
  if (!res.ok) {
    toast(await adminError(res), "error");
    hideOrderBar();
    return;
  }
  const card = orderCardOf(emp.id);
  if (!card) { hideOrderBar(); return; }
  if (focused) {
    card.focus({ preventScroll: true });
    showOrderBar(card);
  } else {
    updateOrderBar(card);
  }
  card.classList.add("emp-card--moved");
  setTimeout(() => card.classList.remove("emp-card--moved"), 900);
  const moved = orderSiblings(employeeDirectory.find((e) => e.id === emp.id));
  if (moved) {
    OrderBar.status.textContent = `${emp.name}: place ${moved.list.findIndex((other) => other.id === emp.id) + 1} of ${moved.list.length}`;
  }
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
      uiEl("p", { class: "field-help", text: "Whoever is in another group moves here." }),
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
    initOrderBar();
  });
}
