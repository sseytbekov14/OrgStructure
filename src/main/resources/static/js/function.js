const COUNTRY_CODES = ['UZ', 'AZ', 'GE', 'AM', 'KG', 'KZ', 'RU'];
const TREE_BY_DEFAULT = ['ITS'];
const VIEW_STORAGE_KEY = 'orgchart-view:';

// Card sizes and gaps of the tree layout, in px (must match .tree-stage card sizes in style.css)
const TREE_SIZE = { W: 220, H: 76, HW: 300, HH: 96, GX: 24, GY: 56, SG: 10, LH: 34 };

let currentCs = '';
let employeeDirectory = [];
let currentView = 'groups';

/* ---------- FAQ & Request Guidelines (U5: from GET /api/functions/{cs}/faq, built with DOM APIs) ---------- */

const FAQ_OPEN_KEY = 'orgchart-faq-open';
const SVG_NS = 'http://www.w3.org/2000/svg';
const FaqState = { cs: null, view: null, editing: false };

/** A small stroked SVG icon from path data (no HTML strings) */
function faqIcon(paths, size = 18) {
  const svg = document.createElementNS(SVG_NS, 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('width', String(size));
  svg.setAttribute('height', String(size));
  svg.setAttribute('fill', 'none');
  svg.setAttribute('stroke', 'currentColor');
  svg.setAttribute('stroke-width', '2');
  svg.setAttribute('aria-hidden', 'true');
  paths.forEach((d) => {
    const path = document.createElementNS(SVG_NS, 'path');
    path.setAttribute('d', d);
    svg.appendChild(path);
  });
  return svg;
}

function faqEl(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text != null) node.textContent = text;
  return node;
}

/** Text nodes, with https:// and mailto: addresses as links (rel="noopener noreferrer"); never HTML */
function faqLinkify(text) {
  const nodes = [];
  const pattern = /(https:\/\/[^\s<>"]+|mailto:[^\s<>"]+)/g;
  let last = 0;
  let match;
  while ((match = pattern.exec(text))) {
    if (match.index > last) nodes.push(document.createTextNode(text.slice(last, match.index)));
    let url = match[0];
    const trailing = (url.match(/[.,;:!?)]+$/) || [''])[0];
    if (trailing) url = url.slice(0, -trailing.length);
    const link = document.createElement('a');
    link.href = url;
    link.rel = 'noopener noreferrer';
    if (url.startsWith('https://')) link.target = '_blank';
    link.textContent = url.startsWith('mailto:') ? url.slice('mailto:'.length) : url;
    nodes.push(link);
    if (trailing) nodes.push(document.createTextNode(trailing));
    last = match.index + match[0].length;
  }
  if (last < text.length) nodes.push(document.createTextNode(text.slice(last)));
  return nodes;
}

function isValidEmail(email) {
  return /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email || '');
}

/** The sections of a FAQ document as a grid (also the preview of the admin editor) */
function renderFaqSections(document_) {
  const grid = faqEl('div', 'faq-grid');
  ((document_ && document_.sections) || []).forEach((section) => {
    const block = faqEl('div', `faq-section faq-section--${String(section.kind || '').toLowerCase()}`);
    block.appendChild(faqEl('h3', 'faq-section-title', section.title || ''));
    const items = section.items || [];
    if (section.kind === 'GUIDELINES') {
      const list = faqEl('ul', 'faq-rules');
      items.forEach((item) => {
        const li = document.createElement('li');
        const icon = faqEl('span', 'faq-rule-icon');
        icon.setAttribute('aria-hidden', 'true');
        icon.appendChild(faqIcon(['M20 6 9 17l-5-5'], 14));
        const text = faqEl('span', 'faq-rule-text');
        faqLinkify(item.text || '').forEach((n) => text.appendChild(n));
        li.append(icon, text);
        list.appendChild(li);
      });
      block.appendChild(list);
    } else if (section.kind === 'MAILBOXES') {
      const boxes = faqEl('div', 'faq-mailboxes');
      items.forEach((item) => {
        const box = faqEl('div', 'faq-mailbox');
        const icon = faqEl('span', 'faq-mailbox-icon');
        icon.appendChild(faqIcon(['M4 4h16v16H4z', 'm22 6-10 7L2 6']));
        const body = faqEl('span', 'faq-mailbox-body');
        body.appendChild(faqEl('span', 'faq-mailbox-label', item.label || ''));
        if (isValidEmail(item.email)) {
          const link = faqEl('a', 'faq-mailbox-email', item.email);
          link.href = `mailto:${item.email}`;
          body.appendChild(link);
        } else {
          body.appendChild(faqEl('span', 'faq-mailbox-missing', 'Email to be added'));
        }
        box.append(icon, body);
        boxes.appendChild(box);
      });
      block.appendChild(boxes);
    } else if (section.kind === 'QUESTIONS') {
      const list = faqEl('dl', 'faq-questions');
      items.forEach((item) => {
        const question = faqEl('dt', 'faq-question');
        faqLinkify(item.question || '').forEach((n) => question.appendChild(n));
        const answer = faqEl('dd', 'faq-answer');
        faqLinkify(item.answer || '').forEach((n) => answer.appendChild(n));
        list.append(question, answer);
      });
      block.appendChild(list);
    }
    grid.appendChild(block);
  });
  return grid;
}

async function loadFaq(cs) {
  try {
    const res = await apiFetch(`/api/functions/${encodeURIComponent(cs)}/faq`);
    return res.ok ? await res.json() : null;
  } catch (e) {
    return null;
  }
}

/** The collapsible FAQ card of the function; hidden when the function has no FAQ */
async function renderFaqBanner(cs, view) {
  const container = document.getElementById('faqBanner');
  if (!container) return;
  const faq = view === undefined ? await loadFaq(cs) : view;
  FaqState.cs = cs;
  FaqState.view = faq;
  if (!faq) {
    container.style.display = 'none';
    container.replaceChildren();
    return;
  }

  let isOpen = false;
  try { isOpen = localStorage.getItem(FAQ_OPEN_KEY) === 'true'; } catch (e) { /* storage unavailable */ }

  const card = faqEl('section', `faq-card${isOpen ? '' : ' is-collapsed'}`);
  card.id = 'faqCard';
  const toggle = faqEl('button', 'faq-toggle');
  toggle.type = 'button';
  toggle.setAttribute('aria-expanded', String(isOpen));
  toggle.setAttribute('aria-controls', 'faqBody');
  const badge = faqEl('span', 'faq-badge');
  badge.setAttribute('aria-hidden', 'true');
  badge.appendChild(faqIcon(['M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20', 'M9.1 9a3 3 0 0 1 5.8 1c0 2-3 3-3 3', 'M12 17h.01']));
  const texts = faqEl('span', 'faq-toggle-text');
  texts.append(faqEl('span', 'faq-title', 'FAQ & Request Guidelines'),
    faqEl('span', 'faq-subtitle', `How to reach ${cs} and submit a request`));
  const chevron = faqIcon(['m6 9 6 6 6-6']);
  chevron.setAttribute('class', 'faq-chevron');
  toggle.append(badge, texts, chevron);
  // The header line: the toggle, and the admin controls next to it (admin-faq.js)
  const head = faqEl('div', 'faq-head');
  head.appendChild(toggle);

  const body = faqEl('div', 'faq-body');
  body.id = 'faqBody';
  const inner = faqEl('div', 'faq-body-inner');
  inner.appendChild(renderFaqSections(faq.content));
  body.appendChild(inner);
  card.append(head, body);
  container.replaceChildren(card);
  container.style.display = 'block';

  toggle.addEventListener('click', () => {
    const open = card.classList.toggle('is-collapsed') === false;
    toggle.setAttribute('aria-expanded', String(open));
    try { localStorage.setItem(FAQ_OPEN_KEY, String(open)); } catch (e) { /* storage unavailable */ }
  });
  if (typeof window.onFaqRendered === 'function') window.onFaqRendered(cs, faq, card);
}

window.toggleHeadSection = function (event, btn) {
  event.stopPropagation();
  const headSection = btn.closest('.head-section');
  if (headSection) {
    const isExpanded = btn.getAttribute('aria-expanded') === 'true';
    const newState = !isExpanded;
    btn.setAttribute('aria-expanded', newState);

    // Hide or show connectors
    const connectors = headSection.querySelectorAll('.connector-v, .connector-h-bar');
    connectors.forEach(c => c.style.display = newState ? '' : 'none');

    // Hide or show all section blocks
    let nextEl = headSection.nextElementSibling;
    while (nextEl) {
      if (nextEl.classList.contains('section-block') || nextEl.classList.contains('no-results')) {
        nextEl.style.display = newState ? '' : 'none';
      }
      nextEl = nextEl.nextElementSibling;
    }
  }
};

window.toggleColumn = function (btn) {
  const col = btn.closest('.org-column');
  if (col) {
    col.classList.toggle('is-collapsed');
    const isCollapsed = col.classList.contains('is-collapsed');
    btn.setAttribute('aria-expanded', !isCollapsed);
  }
};

function isCountryGroup(group) {
  return COUNTRY_CODES.includes(group?.trim().toUpperCase());
}

// Kazakhstan is the main country: its heads are function heads, never country heads
const MAIN_COUNTRY_CODE = 'KZ';

function getCountryHeadCode(emp) {
  const group = emp?.group?.trim().toUpperCase();
  const title = emp?.jobTitle?.trim() || '';
  if (group !== 'HEAD') return null;

  // The ", XX" title suffix (Excel titles) wins when present
  const match = title.match(/,\s*([A-Z]{2})$/);
  if (match && isCountryGroup(match[1])) return match[1].toUpperCase();

  // From the database (rows with ids) the AD titles carry no suffix: use the country from AD instead
  if (emp?.id == null) return null;
  const country = emp?.countryCode?.trim().toUpperCase();
  return country && country !== MAIN_COUNTRY_CODE && isCountryGroup(country) ? country : null;
}

function compareEmployeesByHierarchy(left, right) {
  const gradeDiff = (left?.gradeOrder ?? 999) - (right?.gradeOrder ?? 999);
  if (gradeDiff !== 0) return gradeDiff;

  const jobCompare = (left?.jobTitle || '').localeCompare(right?.jobTitle || '');
  if (jobCompare !== 0) return jobCompare;

  return (left?.name || '').localeCompare(right?.name || '');
}

function employeeKey(emp) {
  return normalizeKey(emp?.email) || normalizeKey(emp?.name);
}

/* ---------- Cards ---------- */

// variant: 'main' (the main head, rank 1) or 'additional' (other heads, shown smaller below the main head)
function renderHeadCard(emp, variant = '') {
  const variantClass = variant ? ` head-card--${variant}` : '';
  return `
    <button type="button" class="head-card${variantClass}" data-emp-id="${emp._id}" title="${escapeHtml([emp.name, emp.jobTitle, personLocation(emp)].filter(Boolean).join('\n'))}" aria-label="Open profile of ${escapeHtml(emp.name || 'employee')}${variant === 'additional' ? ', additional head' : ''}">
      ${renderAvatar(emp, 'lg')}
      <span class="head-card-info">
        <span class="head-card-name">${escapeHtml(emp.name || '—')}</span>
        <span class="head-card-job">${escapeHtml(emp.jobTitle || '—')}</span>
      </span>
    </button>
  `;
}

// groupChip (Tree view, U4): the person's group as a chip, so that a move shows in both views
// The tree card (groupChip) has a fixed height: its location is in the tooltip; the group cards show the location
// line and the counselee count (renderCardFacts, U6). Long texts are clamped with the full text as a tooltip.
function renderEmpCard(emp, { additionalHead = false, groupChip = false } = {}) {
  const group = groupChip && emp.group && emp.group.trim().toUpperCase() !== 'HEAD' ? emp.group : '';
  const location = personLocation(emp);
  const tooltip = [emp.name, emp.jobTitle, groupChip ? location : ''].filter(Boolean).join('\n');
  return `
    <button type="button" class="emp-card${additionalHead ? ' emp-card--additional-head' : ''}" data-emp-id="${emp._id}" title="${escapeHtml(tooltip)}" aria-label="Open profile of ${escapeHtml(emp.name || 'employee')}${additionalHead ? ', additional head' : ''}${group ? `, group ${escapeHtml(group)}` : ''}${location ? `, ${escapeHtml(location)}` : ''}">
      ${renderAvatar(emp, 'md')}
      <span class="emp-card-info">
        <span class="emp-card-name">${escapeHtml(emp.name || '—')}</span>
        <span class="emp-card-job">${escapeHtml(emp.jobTitle || '—')}</span>
        ${additionalHead ? '<span class="chip emp-card-chip">Head</span>' : ''}
        ${group ? `<span class="chip emp-card-chip emp-card-group" title="${escapeHtml(group)}">${escapeHtml(group)}</span>` : ''}
        ${groupChip ? '' : renderCardFacts(emp)}
      </span>
    </button>
  `;
}

/** Groups in the order admins gave them (groupOrder, U4); groups without an order keep their place after them */
function byGroupOrder(entries) {
  const order = (emps) => Math.min(...emps.map(e => (e.groupOrder == null ? Infinity : e.groupOrder)));
  return entries.map((entry, index) => ({ entry, index }))
    .sort((a, b) => (order(a.entry[1]) - order(b.entry[1])) || (a.index - b.index))
    .map(item => item.entry);
}

/* ---------- Groups view ---------- */

function renderColumn(title, employees, isCountry = false) {
  const headerClass = isCountry ? 'col-header country' : 'col-header kz';
  return `
    <div class="org-column" data-country="${isCountry ? 'true' : 'false'}">
      <button class="${headerClass} col-header-btn" onclick="toggleColumn(this)" aria-expanded="true">
        <span class="col-title">${escapeHtml(title)}</span>
        <span class="col-count">${employees.length}</span>
        <svg class="col-toggle-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" width="16" height="16">
          <polyline points="6 9 12 15 18 9"></polyline>
        </svg>
      </button>
      <div class="col-body">
        ${employees.map(emp => renderEmpCard(emp)).join('')}
      </div>
    </div>
  `;
}

function renderGroupsView(employees) {
  const headEmps = employees.filter(e =>
    e.group?.trim().toUpperCase() === 'HEAD' && !getCountryHeadCode(e)
  );
  const kzEmps = employees.filter(e =>
    !isCountryGroup(e.group) &&
    e.group?.trim().toUpperCase() !== 'HEAD'
  );
  const ctryEmps = employees.filter(e =>
    isCountryGroup(e.group) || !!getCountryHeadCode(e)
  );

  const kzGroups = {};
  kzEmps.forEach(e => {
    const g = e.group || 'Other';
    if (!kzGroups[g]) kzGroups[g] = [];
    kzGroups[g].push(e);
  });

  const ctryGroups = {};
  ctryEmps.forEach(e => {
    const g = getCountryHeadCode(e) || e.group?.trim().toUpperCase() || 'OTHER';
    if (!ctryGroups[g]) ctryGroups[g] = [];
    ctryGroups[g].push(e);
  });

  // From the database (with ids) the heads already come in head_rank order
  if (!employees.some(e => e.id != null)) headEmps.sort(compareEmployeesByHierarchy);
  Object.values(kzGroups).forEach(group => group.sort(compareEmployeesByHierarchy));
  Object.values(ctryGroups).forEach(group => group.sort(compareEmployeesByHierarchy));

  let html = '';

  if (headEmps.length > 0) {
    const hasChildren = Object.keys(kzGroups).length > 0 || Object.keys(ctryGroups).length > 0;
    html += `
      <div class="head-section">
        <div class="head-cards" style="position: relative;">
          ${renderHeadCard(headEmps[0], headEmps.length > 1 ? 'main' : '')}
          ${hasChildren ? `<button class="tree-toggle-btn" aria-expanded="true" onclick="toggleHeadSection(event, this)" aria-label="Toggle head section"></button>` : ''}
        </div>
        ${headEmps.length > 1 ? `
        <div class="head-cards head-cards--additional" role="group" aria-label="Additional heads">
          ${headEmps.slice(1).map(emp => renderHeadCard(emp, 'additional')).join('')}
        </div>` : ''}
        <div class="connector-v"></div>
      </div>
    `;
  }

  const hasKz = Object.keys(kzGroups).length > 0;
  const hasCtry = Object.keys(ctryGroups).length > 0;

  if (hasKz && hasCtry) {
    const combined = [];
    byGroupOrder(Object.entries(kzGroups)).forEach(([g, emps]) => combined.push({ title: g, emps, isCountry: false }));
    byGroupOrder(Object.entries(ctryGroups)).forEach(([g, emps]) => combined.push({ title: g, emps, isCountry: true }));

    html += `
      <div class="section-block mixed-section-block">
        <div class="org-grid org-grid-mixed">
          ${combined.map(c => renderColumn(c.title, c.emps, c.isCountry)).join('')}
        </div>
      </div>
    `;
  } else if (hasKz) {
    html += `
      <div class="section-block">
        <div class="org-grid">
          ${byGroupOrder(Object.entries(kzGroups)).map(([g, emps]) => renderColumn(g, emps, false)).join('')}
        </div>
      </div>
    `;
  } else if (hasCtry) {
    html += `
      <div class="section-block country-section-block">
        <div class="org-grid">
          ${byGroupOrder(Object.entries(ctryGroups)).map(([c, emps]) => renderColumn(c, emps, true)).join('')}
        </div>
      </div>
    `;
  }

  return html;
}

function debounce(fn, wait = 120) {
  let timeout;
  return (...args) => {
    clearTimeout(timeout);
    timeout = setTimeout(() => fn(...args), wait);
  };
}

function layoutOrgGrids() {
  document.querySelectorAll('.org-grid').forEach(grid => {
    const isMixedGrid = grid.classList.contains('org-grid-mixed');
    const minColWidth = isMixedGrid ? 220 : 260;
    const gap = 16;
    const availableWidth = Math.max(grid.clientWidth, grid.offsetWidth);
    const items = Array.from(grid.querySelectorAll('.org-column'));
    if (items.length === 0) return;

    let count = Math.min(
      items.length,
      Math.max(1, Math.floor((availableWidth + gap) / (minColWidth + gap)))
    );

    if (isMixedGrid && items.length >= 4 && availableWidth >= 1120) {
      count = Math.max(count, Math.min(items.length, 4));
    }

    grid.innerHTML = '';
    const columns = [];
    for (let i = 0; i < count; i++) {
      const wrapper = document.createElement('div');
      wrapper.className = 'org-column-wrapper';
      columns.push(wrapper);
      grid.appendChild(wrapper);
    }

    const placeIntoShortestColumn = (columnItems, targetColumns) => {
      columnItems.forEach(item => {
        item.style.width = '100%';
        const target = targetColumns.reduce((shortest, col) => {
          return col.offsetHeight < shortest.offsetHeight ? col : shortest;
        }, targetColumns[0]);
        target.appendChild(item);
      });
    };

    if (isMixedGrid) {
      const mainItems = items.filter(item => item.dataset.country !== 'true');
      const countryItems = items.filter(item => item.dataset.country === 'true');

      if (mainItems.length > 0 && countryItems.length > 0 && columns.length > 1) {
        const proportionalMainColumns = Math.round((mainItems.length / items.length) * columns.length);
        const mainColumnCount = Math.max(1, Math.min(columns.length - 1, proportionalMainColumns));
        placeIntoShortestColumn(mainItems, columns.slice(0, mainColumnCount));
        placeIntoShortestColumn(countryItems, columns.slice(mainColumnCount));
        return;
      }
    }

    placeIntoShortestColumn(items, columns);
  });
}

/* ---------- Tree view ---------- */

const tree = {
  root: null,
  nodes: [],
  byKey: new Map(),
  preorder: [],
  expandedKeys: null,
  view: { s: 1, x: 0, y: 0 },
  fitted: false,
  cardEls: new Map(),
  toggleEls: new Map()
};

function buildTree(employees) {
  const nodes = employees.map(emp => ({ emp, id: emp._id, key: employeeKey(emp), children: [], parent: null }));
  const byEmail = new Map();
  const byName = new Map();
  const byId = new Map();
  nodes.forEach(node => {
    const email = normalizeKey(node.emp.email);
    const name = normalizeKey(node.emp.name);
    if (email && !byEmail.has(email)) byEmail.set(email, node);
    if (name && !byName.has(name)) byName.set(name, node);
    if (node.emp.id != null && !byId.has(node.emp.id)) byId.set(node.emp.id, node);
  });

  // Employees from the database carry ids: the parent is parentId (null: no parent in the function)
  const fromDb = byId.size > 0;
  let roots = [];
  nodes.forEach(node => {
    const parent = fromDb
      ? byId.get(node.emp.parentId)
      : byName.get(normalizeKey(node.emp.pm)) || byEmail.get(normalizeKey(node.emp.pmEmail));
    if (parent && parent !== node) {
      parent.children.push(node);
      node.parent = parent;
    } else {
      roots.push(node);
    }
  });

  // From the database the heads come in head_rank order: the first one is the root of the function tree
  const mainHeadFromDb = fromDb ? nodes.find(node => node.emp.group?.trim().toUpperCase() === 'HEAD') : null;
  if (mainHeadFromDb && mainHeadFromDb.parent) {
    mainHeadFromDb.parent.children = mainHeadFromDb.parent.children.filter(child => child !== mainHeadFromDb);
    mainHeadFromDb.parent = null;
    roots.push(mainHeadFromDb);
  }

  // Break PM cycles so nobody disappears from the chart
  const reachable = new Set();
  const mark = node => { reachable.add(node); node.children.forEach(mark); };
  roots.forEach(mark);
  nodes.forEach(node => {
    if (reachable.has(node)) return;
    node.parent.children = node.parent.children.filter(child => child !== node);
    node.parent = null;
    roots.push(node);
    mark(node);
  });

  // The function head stays on top: other top-level people hang under the first head
  const headRoots = roots
    .filter(node => node.emp.group?.trim().toUpperCase() === 'HEAD')
    .sort((a, b) => fromDb ? nodes.indexOf(a) - nodes.indexOf(b) : compareEmployeesByHierarchy(a.emp, b.emp));
  if (roots.length > 1 && headRoots.length > 0) {
    const mainHead = headRoots[0];
    roots.filter(node => node !== mainHead).forEach(node => {
      node.parent = mainHead;
      mainHead.children.push(node);
    });
    roots = [mainHead];
  }

  // From the database the other heads (rank 2..n) follow directly below the main head, in head_rank order
  const mainHeadNode = roots.length === 1 && roots[0].emp?.group?.trim().toUpperCase() === 'HEAD' ? roots[0] : null;
  const additionalHeads = fromDb && mainHeadNode
    ? nodes.filter(node => node !== mainHeadNode && node.emp.group?.trim().toUpperCase() === 'HEAD')
    : [];
  additionalHeads.forEach(node => {
    if (node.parent && node.parent !== mainHeadNode) {
      node.parent.children = node.parent.children.filter(child => child !== node);
      node.parent = mainHeadNode;
      mainHeadNode.children.push(node);
    }
    node.isAdditionalHead = true;
  });
  const headOrder = node => (node.isAdditionalHead ? nodes.indexOf(node) : Number.MAX_SAFE_INTEGER);

  const sortChildren = node => {
    node.children.sort((a, b) => headOrder(a) - headOrder(b) || compareEmployeesByHierarchy(a.emp, b.emp));
    node.children.forEach(sortChildren);
  };
  roots.forEach(sortChildren);
  roots.sort((a, b) => compareEmployeesByHierarchy(a.emp, b.emp));

  let root = roots[0];
  if (roots.length > 1) {
    root = { virtual: true, key: '__root__', children: roots, parent: null };
    roots.forEach(node => { node.parent = root; });
  }
  nodes.forEach(node => { node.isHead = !node.parent || node.parent.virtual; });

  const preorder = [];
  const visit = (node, depth) => {
    node.depth = depth;
    if (!node.virtual) preorder.push(node);
    node.children.forEach(child => visit(child, node.virtual ? depth : depth + 1));
  };
  if (root) visit(root, 0);

  tree.root = root || null;
  tree.nodes = nodes;
  tree.preorder = preorder;
  tree.byKey = new Map(nodes.map(node => [node.key, node]));
}

function defaultExpandedKeys() {
  const expandAll = tree.nodes.length <= 25;
  return new Set(tree.nodes
    .filter(node => node.children.length && (expandAll || node.depth <= 1))
    .map(node => node.key));
}

const nodeWidth = node => node.virtual ? 0 : node.isHead ? TREE_SIZE.HW : TREE_SIZE.W;
const nodeHeight = node => node.virtual ? 0 : node.isHead ? TREE_SIZE.HH : TREE_SIZE.H;
const isExpanded = node => node.virtual || tree.expandedKeys.has(node.key);
const countDescendants = node => node.children.reduce((sum, child) => sum + 1 + countDescendants(child), 0);

function slotLabel(parent, child) {
  if (parent.virtual) return null;
  return child.emp.group && child.emp.group !== parent.emp.group ? child.emp.group : null;
}

function measureNode(node) {
  if (!node.children.length || !isExpanded(node)) {
    node.slots = null;
    node.width = nodeWidth(node);
    return;
  }

  const slots = [];
  node.children.filter(child => child.children.length).forEach(child => {
    measureNode(child);
    slots.push({ type: 'branch', node: child, width: child.width, label: slotLabel(node, child) });
  });

  const leafGroups = new Map();
  node.children.filter(child => !child.children.length).forEach(child => {
    const label = slotLabel(node, child) || '';
    if (!leafGroups.has(label)) leafGroups.set(label, []);
    leafGroups.get(label).push(child);
  });
  leafGroups.forEach((leaves, label) => {
    leaves.forEach(leaf => { leaf.slots = null; leaf.width = TREE_SIZE.W; });
    slots.push({ type: 'stack', nodes: leaves, width: TREE_SIZE.W, label: label || null });
  });

  node.slots = slots;
  node.slotsWidth = slots.reduce((sum, slot) => sum + slot.width, 0) + TREE_SIZE.GX * (slots.length - 1);
  node.width = Math.max(nodeWidth(node), node.slotsWidth);
}

function placeNode(node, x, y) {
  node.x = x + (node.width - nodeWidth(node)) / 2;
  node.y = y;
  if (!node.slots) return;

  const top = y + nodeHeight(node) + (node.virtual ? 0 : TREE_SIZE.GY);
  let cursor = x + (node.width - node.slotsWidth) / 2;
  node.slots.forEach(slot => {
    slot.top = top;
    slot.start = top + (slot.label ? TREE_SIZE.LH : 0);
    slot.center = cursor + slot.width / 2;
    if (slot.type === 'branch') {
      placeNode(slot.node, cursor, slot.start);
    } else {
      slot.nodes.forEach((leaf, index) => {
        leaf.x = cursor;
        leaf.y = slot.start + index * (TREE_SIZE.H + TREE_SIZE.SG);
      });
    }
    cursor += slot.width + TREE_SIZE.GX;
  });
}

function walkVisible(node, fn) {
  fn(node);
  if (!node.slots) return;
  node.slots.forEach(slot => {
    if (slot.type === 'branch') walkVisible(slot.node, fn);
    else slot.nodes.forEach(fn);
  });
}

function renderTreeShell() {
  return `
    <div class="tree-shell">
      <div class="tree-toolbar">
        <button type="button" class="tool-btn" data-tree-action="expand-all">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="m7 15 5 5 5-5M7 9l5-5 5 5"/></svg>
          Expand all
        </button>
        <div class="tree-zoom" role="group" aria-label="Zoom">
          <button type="button" class="icon-btn" data-tree-action="zoom-out" aria-label="Zoom out">
            <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.4" aria-hidden="true"><path d="M5 12h14"/></svg>
          </button>
          <span class="tree-zoom-value" id="treeZoomValue">100%</span>
          <button type="button" class="icon-btn" data-tree-action="zoom-in" aria-label="Zoom in">
            <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.4" aria-hidden="true"><path d="M12 5v14M5 12h14"/></svg>
          </button>
          <button type="button" class="icon-btn tree-zoom-fit" data-tree-action="fit">Fit</button>
        </div>
      </div>
      <div class="tree-viewport" id="treeViewport">
        <div class="tree-stage" id="treeStage">
          <div class="tree-overlay" id="treeOverlay">
            <svg class="tree-edges" id="treeEdges" aria-hidden="true"></svg>
            <div id="treeLabels"></div>
          </div>
        </div>
        <div class="tree-legend">
          <span class="tree-legend-hint">Drag to move · Ctrl + wheel to zoom</span>
        </div>
      </div>
    </div>
  `;
}

function initTree() {
  buildTree(employeeDirectory);
  if (!tree.root) return;
  if (!tree.expandedKeys) tree.expandedKeys = defaultExpandedKeys();

  const stage = document.getElementById('treeStage');
  tree.cardEls = new Map();
  tree.toggleEls = new Map();

  const holder = document.createElement('div');
  tree.nodes.forEach(node => {
    holder.innerHTML = node.isHead ? renderHeadCard(node.emp)
      : renderEmpCard(node.emp, { additionalHead: !!node.isAdditionalHead, groupChip: true });
    const card = holder.firstElementChild;
    card.hidden = true;
    stage.appendChild(card);
    tree.cardEls.set(node.id, card);

    if (node.children.length) {
      const toggle = document.createElement('button');
      toggle.type = 'button';
      toggle.className = 'tree-count-btn';
      toggle.dataset.toggleKey = node.key;
      toggle.hidden = true;
      stage.appendChild(toggle);
      tree.toggleEls.set(node.id, toggle);
    }
  });

  bindTreeViewport(document.getElementById('treeViewport'));
  layoutTree(false);

  if (!tree.fitted) {
    showTreeTop();
    tree.fitted = true;
  } else {
    applyTreeView(false);
  }
}

function moveTreeEl(el, target, from, suffix = '') {
  if (from) {
    el.style.transition = 'none';
    el.style.transform = from + suffix;
    el.hidden = false;
    void el.offsetWidth;
    el.style.transition = '';
  }
  el.hidden = false;
  el.style.transform = target + suffix;
}

function layoutTree(animate = true) {
  const root = tree.root;
  if (!root) return;

  measureNode(root);
  placeNode(root, 0, 0);
  const visible = new Set();
  walkVisible(root, node => visible.add(node));

  let maxY = 0;
  tree.nodes.forEach(node => {
    const card = tree.cardEls.get(node.id);
    const toggle = tree.toggleEls.get(node.id);
    if (!visible.has(node)) {
      card.hidden = true;
      if (toggle) toggle.hidden = true;
      return;
    }

    const parent = node.parent && !node.parent.virtual ? node.parent : null;
    const from = animate && card.hidden && parent && visible.has(parent)
      ? `translate(${parent.x + (nodeWidth(parent) - nodeWidth(node)) / 2}px,${parent.y}px)`
      : null;
    moveTreeEl(card, `translate(${node.x}px,${node.y}px)`, from);
    maxY = Math.max(maxY, node.y + nodeHeight(node));

    if (toggle) {
      const open = isExpanded(node);
      const count = countDescendants(node);
      toggle.setAttribute('aria-expanded', String(open));
      toggle.setAttribute('aria-label', `${open ? 'Collapse' : 'Expand'} ${count} people under ${node.emp.name}`);
      toggle.innerHTML = `<svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2.6" aria-hidden="true"><path d="m18 15-6-6-6 6"/></svg>${open ? count : '+' + count}`;
      const toggleFrom = from ? `translate(${parent.x + nodeWidth(parent) / 2}px,${parent.y + nodeHeight(parent)}px)` : null;
      moveTreeEl(toggle, `translate(${node.x + nodeWidth(node) / 2}px,${node.y + nodeHeight(node)}px)`, toggleFrom, ' translate(-50%,-50%)');
    }
  });

  let edges = '';
  let labels = '';
  walkVisible(root, node => {
    if (!node.slots || node.virtual) return;
    const x = node.x + nodeWidth(node) / 2;
    const bottom = node.y + nodeHeight(node);
    const bus = bottom + TREE_SIZE.GY / 2;
    edges += `M${x} ${bottom}V${bus}`;

    const centers = node.slots.map(slot => slot.center);
    if (centers.length > 1) edges += `M${Math.min(...centers)} ${bus}H${Math.max(...centers)}`;

    node.slots.forEach(slot => {
      const end = slot.type === 'branch' ? slot.node.y : slot.nodes[slot.nodes.length - 1].y + TREE_SIZE.H / 2;
      edges += `M${slot.center} ${bus}V${end}`;
      if (slot.label) {
        labels += `<span class="tree-label" style="transform:translate(${slot.center}px,${slot.top}px) translate(-50%,0)">${escapeHtml(slot.label)}</span>`;
      }
    });
  });

  const width = Math.max(root.width, 1);
  const height = maxY + 24;
  const svg = document.getElementById('treeEdges');
  svg.setAttribute('width', width);
  svg.setAttribute('height', height);
  svg.setAttribute('viewBox', `0 0 ${width} ${height}`);
  svg.innerHTML = `<path class="tree-edge" d="${edges}"/>`;
  document.getElementById('treeLabels').innerHTML = labels;

  const stage = document.getElementById('treeStage');
  stage.style.width = `${width}px`;
  stage.style.height = `${height}px`;

  const overlay = document.getElementById('treeOverlay');
  overlay.classList.remove('is-visible');
  if (animate) void overlay.offsetWidth;
  overlay.classList.add('is-visible');
  overlay.classList.toggle('no-delay', !animate);
}

function applyTreeView(glide) {
  const stage = document.getElementById('treeStage');
  if (!stage) return;
  const { s, x, y } = tree.view;
  stage.classList.toggle('is-gliding', !!glide);
  stage.style.transform = `translate(${x}px,${y}px) scale(${s})`;
  const value = document.getElementById('treeZoomValue');
  if (value) value.textContent = `${Math.round(s * 100)}%`;
  if (glide) setTimeout(() => stage.classList.remove('is-gliding'), 420);
}

function zoomTree(factor, cx, cy) {
  const view = tree.view;
  const scale = Math.min(2, Math.max(0.25, view.s * factor));
  view.x = cx - (cx - view.x) * (scale / view.s);
  view.y = cy - (cy - view.y) * (scale / view.s);
  view.s = scale;
  applyTreeView(true);
}

function zoomTreeFromCenter(factor) {
  const rect = document.getElementById('treeViewport').getBoundingClientRect();
  zoomTree(factor, rect.width / 2, rect.height / 2);
}

function fitTree(glide) {
  const viewport = document.getElementById('treeViewport');
  const stage = document.getElementById('treeStage');
  if (!viewport || !stage || !tree.root) return;
  const rect = viewport.getBoundingClientRect();
  if (!rect.width) return;
  const width = tree.root.width;
  const height = parseFloat(stage.style.height) || 400;
  const scale = Math.max(0.25, Math.min((rect.width - 48) / width, (rect.height - 72) / height, 1));
  tree.view = { s: scale, x: (rect.width - width * scale) / 2, y: 24 };
  applyTreeView(glide);
}

// First view: fit the width only (tall branches can be panned), never below a readable scale
function showTreeTop(glide = false) {
  const viewport = document.getElementById('treeViewport');
  if (!viewport || !tree.root) return;
  const rect = viewport.getBoundingClientRect();
  if (!rect.width) return;
  const width = tree.root.width;
  const scale = Math.max(0.7, Math.min((rect.width - 48) / width, 1));
  const rootCenter = tree.root.virtual ? width / 2 : tree.root.x + nodeWidth(tree.root) / 2;
  const x = width * scale <= rect.width ? (rect.width - width * scale) / 2 : rect.width / 2 - rootCenter * scale;
  tree.view = { s: scale, x, y: 24 };
  applyTreeView(glide);
}

function focusTreeNode(node) {
  const rect = document.getElementById('treeViewport').getBoundingClientRect();
  const view = tree.view;
  view.s = Math.max(view.s, 0.85);
  view.x = rect.width / 2 - (node.x + nodeWidth(node) / 2) * view.s;
  view.y = rect.height / 2 - (node.y + nodeHeight(node) / 2) * view.s;
  applyTreeView(true);
}

function expandPathTo(node) {
  let current = node.parent;
  while (current && !current.virtual) {
    tree.expandedKeys.add(current.key);
    current = current.parent;
  }
}

function bindTreeViewport(viewport) {
  let drag = null;

  viewport.addEventListener('wheel', event => {
    if (!event.ctrlKey && !event.metaKey) return;
    event.preventDefault();
    const rect = viewport.getBoundingClientRect();
    zoomTree(event.deltaY < 0 ? 1.1 : 1 / 1.1, event.clientX - rect.left, event.clientY - rect.top);
  }, { passive: false });

  viewport.addEventListener('pointerdown', event => {
    if (event.target.closest('button, a, input, .tree-legend')) return;
    drag = { x: event.clientX, y: event.clientY, viewX: tree.view.x, viewY: tree.view.y };
    viewport.setPointerCapture(event.pointerId);
    viewport.classList.add('is-dragging');
  });

  viewport.addEventListener('pointermove', event => {
    if (!drag) return;
    tree.view.x = drag.viewX + event.clientX - drag.x;
    tree.view.y = drag.viewY + event.clientY - drag.y;
    applyTreeView(false);
  });

  const endDrag = () => {
    drag = null;
    viewport.classList.remove('is-dragging');
  };
  viewport.addEventListener('pointerup', endDrag);
  viewport.addEventListener('pointercancel', endDrag);
}

function handleTreeAction(action) {
  if (action === 'expand-all') {
    tree.expandedKeys = new Set(tree.nodes.filter(node => node.children.length).map(node => node.key));
    layoutTree();
    showTreeTop(true);
  } else if (action === 'zoom-in') {
    zoomTreeFromCenter(1.2);
  } else if (action === 'zoom-out') {
    zoomTreeFromCenter(1 / 1.2);
  } else if (action === 'fit') {
    fitTree(true);
  }
}

/* ---------- Search inside the function ---------- */

const funcSearch = { query: '', matches: [], index: 0 };

function searchableEmployees() {
  if (currentView === 'tree') return tree.preorder.map(node => node.emp);
  const seen = new Set();
  return Array.from(document.querySelectorAll('#orgChart [data-emp-id]'))
    .map(el => employeeDirectory[Number(el.dataset.empId)])
    .filter(emp => emp && !seen.has(emp._id) && seen.add(emp._id));
}

function updateCardHighlights() {
  const current = funcSearch.matches[funcSearch.index];
  document.querySelectorAll('#orgChart [data-emp-id]').forEach(card => {
    const emp = employeeDirectory[Number(card.dataset.empId)];
    if (!emp) return;
    const isMatch = funcSearch.matches.includes(emp);
    card.classList.toggle('is-match', isMatch);
    card.classList.toggle('is-current', emp === current);
    const nameEl = card.querySelector('.emp-card-name, .head-card-name');
    if (nameEl) nameEl.innerHTML = isMatch ? highlightText(emp.name || '—', funcSearch.query) : escapeHtml(emp.name || '—');
  });
}

function runFunctionSearch(reveal) {
  const query = funcSearch.query;
  funcSearch.matches = query.length >= 2
    ? searchableEmployees().filter(emp =>
        [emp.name, emp.jobTitle, emp.email, emp.group].join(' ').toLowerCase().includes(query))
    : [];
  if (funcSearch.index >= funcSearch.matches.length) funcSearch.index = 0;
  showSearchMatch(reveal);
}

function showSearchMatch(reveal) {
  const total = funcSearch.matches.length;
  document.getElementById('funcSearchCount').textContent =
    funcSearch.query.length < 2 ? '' : total ? `${funcSearch.index + 1} of ${total}` : 'No match';
  document.getElementById('funcSearchPrev').disabled = total < 2;
  document.getElementById('funcSearchNext').disabled = total < 2;

  const current = funcSearch.matches[funcSearch.index];
  if (current && reveal) {
    revealEmployee(current, false);
  } else {
    updateCardHighlights();
  }
}

function stepSearch(delta) {
  const total = funcSearch.matches.length;
  if (!total) return;
  funcSearch.index = (funcSearch.index + delta + total) % total;
  showSearchMatch(true);
}

/* ---------- Revealing an employee ---------- */

function flashCard(card) {
  if (!card) return;
  card.classList.remove('is-flash');
  void card.offsetWidth;
  card.classList.add('is-flash');
}

function revealEmployee(emp, flash) {
  if (currentView === 'tree') {
    const node = tree.nodes.find(item => item.emp === emp);
    if (!node) return;
    expandPathTo(node);
    layoutTree();
    updateCardHighlights();
    focusTreeNode(node);
    if (flash) flashCard(tree.cardEls.get(node.id));
    return;
  }

  updateCardHighlights();
  const card = document.querySelector(`#orgChart [data-emp-id="${emp._id}"]`);
  if (!card) return;

  const column = card.closest('.org-column');
  if (column?.classList.contains('is-collapsed')) {
    column.classList.remove('is-collapsed');
    column.querySelector('.col-header-btn')?.setAttribute('aria-expanded', 'true');
  }
  const headToggle = document.querySelector('.head-section .tree-toggle-btn[aria-expanded="false"]');
  if (headToggle && !card.closest('.head-section')) {
    window.toggleHeadSection({ stopPropagation() {} }, headToggle);
  }

  card.scrollIntoView({ behavior: 'smooth', block: 'center' });
  if (flash) flashCard(card);
}

// Called by the profile dialog's "Show in chart" link
window.showEmployeeInChart = function (employee) {
  if (!employee || employee.cs !== currentCs) return false;
  const key = employeeKey(employee);
  const local = employeeDirectory.find(emp => employeeKey(emp) === key);
  if (!local) return false;
  revealEmployee(local, true);
  return true;
};

/* ---------- Rendering ---------- */

function readViewPreference(cs) {
  try {
    const saved = localStorage.getItem(VIEW_STORAGE_KEY + cs);
    if (saved === 'tree' || saved === 'groups') return saved;
  } catch (e) { /* storage unavailable */ }
  return TREE_BY_DEFAULT.includes(cs) ? 'tree' : 'groups';
}

function setView(view) {
  currentView = view;
  try { localStorage.setItem(VIEW_STORAGE_KEY + currentCs, view); } catch (e) { /* storage unavailable */ }
  renderChart();
}

function renderChart() {
  document.querySelectorAll('.view-toggle [data-view]').forEach(btn => {
    btn.setAttribute('aria-pressed', String(btn.dataset.view === currentView));
  });

  const chart = document.getElementById('orgChart');
  if (employeeDirectory.length === 0) {
    chart.innerHTML = `<div class="no-results">No employees found for "${escapeHtml(currentCs)}"</div>`;
    return;
  }

  if (currentView === 'tree') {
    chart.innerHTML = renderTreeShell();
    initTree();
  } else {
    chart.innerHTML = renderGroupsView(employeeDirectory);
    layoutOrgGrids();
  }
  runFunctionSearch(false);
}

function bindChartEvents() {
  const chart = document.getElementById('orgChart');

  chart.addEventListener('click', event => {
    const action = event.target.closest('[data-tree-action]');
    if (action) {
      handleTreeAction(action.dataset.treeAction);
      return;
    }

    const toggle = event.target.closest('[data-toggle-key]');
    if (toggle) {
      const key = toggle.dataset.toggleKey;
      if (tree.expandedKeys.has(key)) tree.expandedKeys.delete(key);
      else tree.expandedKeys.add(key);
      layoutTree();
      return;
    }

    if (window.getSelection()?.toString().trim()) return;
    const card = event.target.closest('.emp-card, .head-card');
    if (!card) return;
    const emp = employeeDirectory[Number(card.dataset.empId)];
    if (emp) openEmployeeProfile(emp);
  });

  // Back to the main page keeps its search (?q=) when we came from there
  document.querySelector('.btn-back')?.addEventListener('click', event => {
    let fromHome = false;
    try {
      const referrer = new URL(document.referrer);
      fromHome = referrer.origin === location.origin && (referrer.pathname === '/' || referrer.pathname === '/index.html');
    } catch (e) { /* no referrer */ }
    if (fromHome && history.length > 1) {
      event.preventDefault();
      history.back();
    }
  });

  document.querySelectorAll('.view-toggle [data-view]').forEach(btn => {
    btn.addEventListener('click', () => {
      if (btn.dataset.view !== currentView) setView(btn.dataset.view);
    });
  });

  const input = document.getElementById('funcSearch');
  const runDebounced = debounce(() => {
    funcSearch.query = input.value.trim().toLowerCase();
    funcSearch.index = 0;
    runFunctionSearch(true);
  }, 160);
  input.addEventListener('input', runDebounced);
  input.addEventListener('keydown', event => {
    if (event.key !== 'Enter') return;
    event.preventDefault();
    stepSearch(event.shiftKey ? -1 : 1);
  });
  document.getElementById('funcSearchPrev').addEventListener('click', () => stepSearch(-1));
  document.getElementById('funcSearchNext').addEventListener('click', () => stepSearch(1));
}

/** U6: placeholder cards while the employees load (DOM only) */
function showChartSkeleton() {
  const chart = document.getElementById('orgChart');
  if (!chart) return;
  const cards = Array.from({ length: 6 }, () => {
    const card = document.createElement('div');
    card.className = 'emp-card emp-card-skeleton';
    const avatar = document.createElement('span');
    avatar.className = 'skeleton skeleton-avatar';
    const lines = document.createElement('span');
    lines.className = 'emp-card-info';
    ['skeleton skeleton-line', 'skeleton skeleton-line skeleton-line--short'].forEach((className) => {
      const line = document.createElement('span');
      line.className = className;
      lines.appendChild(line);
    });
    card.append(avatar, lines);
    return card;
  });
  const grid = document.createElement('div');
  grid.className = 'chart-skeleton';
  grid.setAttribute('aria-busy', 'true');
  grid.setAttribute('aria-label', 'Loading the employees');
  grid.append(...cards);
  chart.replaceChildren(grid);
}

async function loadFunctionData({ refresh = false } = {}) {
  const params = new URLSearchParams(window.location.search);
  const cs = params.get('cs');
  if (!cs) { window.location.href = '/'; return; }

  if (!refresh) {
    currentCs = cs;
    currentView = readViewPreference(cs);
    document.title = `CS OrgChart - ${cs}`;
    document.getElementById('funcTitle').textContent = cs;
    renderFaqBanner(cs);
    logPageVisit(cs);
    showChartSkeleton();
  } else if (!FaqState.editing) {
    // An admin changed the FAQ (SSE): show the new one, unless this page is editing it
    renderFaqBanner(cs);
  }

  try {
    const [allRes, likesRes] = await Promise.all([
      apiFetch('/api/employees'),
      apiFetch('/api/likes/summary')
    ]);

    const allEmployees = await allRes.json();
    setDirectory(allEmployees);
    try { setLikeSummaries(await likesRes.json()); } catch (e) { setLikeSummaries({}); }

    employeeDirectory = allEmployees
      .filter(employee => employee.cs === cs)
      .map((employee, index) => ({ ...employee, _id: index }));
    document.getElementById('funcCount').textContent = `${employeeDirectory.length} employees`;

    renderChart();
    // admin.js may load after this (admins only): it picks up the last render itself
    window.lastFunctionRender = { cs, members: employeeDirectory };
    if (typeof window.onFunctionRendered === 'function') window.onFunctionRendered(cs, employeeDirectory);

    const target = !refresh && params.get('emp');
    if (target) {
      const emp = employeeDirectory.find(item => normalizeKey(item.email) === normalizeKey(target));
      if (emp) requestAnimationFrame(() => revealEmployee(emp, true));
    }
  } catch (e) {
    console.error(e);
    document.getElementById('orgChart').innerHTML = '<div class="no-results">Error loading data</div>';
  }
}

window.addEventListener('resize', debounce(layoutOrgGrids, 120));

window.refreshOrgChart = async () => {
  await loadFunctionData({ refresh: true });
};

bindChartEvents();
loadFunctionData();
