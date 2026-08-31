const COUNTRY_CODES = ['UZ', 'AZ', 'GE', 'AM', 'KG', 'KZ', 'RU'];
const COUNTRY_FLAGS = { UZ: '🇺🇿', AZ: '🇦🇿', GE: '🇬🇪', AM: '🇦🇲', KG: '🇰🇬', KZ: '🇰🇿', RU: '🇷🇺' };

let employeeDirectory = [];
let allEmployeesLookup = [];
let likeSummaries = {};

const FUNCTION_FAQ = {
  People: {
    emails: [
      { label: "General Questions & HR Administration", email: "people@kpmg.kz" },
      { label: "Learning & Development (L&D)", email: "learning@kpmg.kz" },
      { label: "Graduate & Experienced Recruiting", email: "recruiting@kpmg.kz" }
    ],
    rules: [
      "Check the KPMG HR Portal first for standard templates and guidelines.",
      "Send all L&D/training request approvals at least two weeks in advance.",
      "Submit candidate referrals directly through the internal referral system."
    ]
  },
  ITS: {
    emails: [
      { label: "IT Service Desk (Incidents & Requests)", email: "itservicedesk@kpmg.kz" },
      { label: "IT Operations & Infrastructure", email: "ccg-fmhni@kpmg.kz" },
      { label: "1C Development Team", email: "@kpmg.kz" }
    ],
    rules: [
      "Submit all standard requests and hardware issues via the IT Portal.",
      "Urgent issues: Call the Service Desk hotline at extension CCG-CQ-Ithelpdesk for immediate help.",
      "Request new software licenses using the official Software Request form."
    ]
  },
  Finance: {
    emails: [
      { label: "Accounts Payable (Vendor Invoices)", email: "ap@kpmg.kz" },
      { label: "Travel & Business Expense Claims", email: "travel@kpmg.kz" },
      { label: "Client Billing & Invoicing Support", email: "billing@kpmg.kz" }
    ],
    rules: [
      "Submit expense claims before the 25th of the current month to ensure payout.",
      "All invoices must include a valid Purchase Order (PO) number to be processed.",
      "Use the SharePoint Finance portal to download current tax and billing templates."
    ]
  },
  Markets: {
    emails: [
      { label: "Brand Review & Logo Approvals", email: "brand@kpmg.kz" },
      { label: "Events Management & Catering Requests", email: "events@kpmg.kz" },
      { label: "BD Opportunities & Markets General", email: "markets@kpmg.kz" }
    ],
    rules: [
      "Submit brand review requests at least 3 working days before the deadline.",
      "Book meeting rooms and catering requests through the SharePoint portal.",
      "Use official business templates and presentation slide packs from the portal."
    ]
  },
  OGC: {
    emails: [
      { label: "Contract Reviews & Legal Queries", email: "legal@kpmg.kz" },
      { label: "Regulatory Compliance Assistance", email: "compliance@kpmg.kz" },
      { label: "General Legal Counsel Support", email: "ogc@kpmg.kz" }
    ],
    rules: [
      "Submit contracts for OGC review via the Legal Workflow System (LWS).",
      "Standard contract templates do not require OGC review unless customized.",
      "Involve OGC early for high-risk engagements or new service offerings."
    ]
  },
  Tenders: {
    emails: [
      { label: "Tender Proposals & Bid Submission Support", email: "tenders@kpmg.kz" },
      { label: "Procurement & Bid Documentation", email: "procurement@kpmg.kz" },
      { label: "General Bid Support Inquiries", email: "tender-ops@kpmg.kz" }
    ],
    rules: [
      "All bid documents must be signed off by the Engagement Partner before submission.",
      "Register new tender opportunities in the Tender Tracker tool immediately.",
      "Contact the Tender Support team as soon as the client's RFP is released."
    ]
  },
  QRM: {
    emails: [
      { label: "Quality & Risk Management (Sentinel checks)", email: "qrm@kpmg.kz" },
      { label: "Client & Engagement Acceptance Rules", email: "acceptance@kpmg.kz" },
      { label: "Risk Management Helpdesk Support", email: "risk@kpmg.kz" }
    ],
    rules: [
      "Complete client and engagement acceptance checks prior to commencing any work.",
      "Submit Sentinel code checks for review before distributing proposals.",
      "Contact QRM immediately if potential conflicts of interest are identified."
    ]
  }
};

function renderFaqBanner(cs) {
  const container = document.getElementById('faqBanner');
  if (!container) return;

  const faq = FUNCTION_FAQ[cs];
  if (!faq) {
    container.style.display = 'none';
    return;
  }

  container.innerHTML = `
    <div class="faq-card is-collapsed" id="faqCard">
      <button class="faq-card-header" onclick="toggleFaqBanner()" aria-expanded="false">
        <div class="faq-header-title">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="18" height="18">
            <circle cx="12" cy="12" r="10"></circle>
            <line x1="12" y1="16" x2="12" y2="12"></line>
            <line x1="12" y1="8" x2="12.01" y2="8"></line>
          </svg>
          <span>FAQ & Request Guidelines</span>
        </div>
        <svg class="faq-toggle-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" width="16" height="16">
          <polyline points="6 9 12 15 18 9"></polyline>
        </svg>
      </button>
      
      <div class="faq-card-body" id="faqCardBody">
        <div class="faq-card-content">
          <div class="faq-card-section rules-section">
            <div class="faq-section-title">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
                <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path>
                <polyline points="14 2 14 8 20 8"></polyline>
                <line x1="16" y1="13" x2="8" y2="13"></line>
                <line x1="16" y1="17" x2="8" y2="17"></line>
                <polyline points="10 9 9 9 8 9"></polyline>
              </svg>
              Request Submission Rules
            </div>
            <ul class="faq-rules-list">
              ${faq.rules.map(rule => `<li>${escapeHtml(rule)}</li>`).join('')}
            </ul>
          </div>
          
          <div class="faq-card-divider"></div>

          <div class="faq-card-section emails-section">
            <div class="faq-section-title">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
                <path d="M4 4h16c1.1 0 2 .9 2 2v12c0 1.1-.9 2-2 2H4c-1.1 0-2-.9-2-2V6c0-1.1.9-2 2-2z"></path>
                <polyline points="22,6 12,13 2,6"></polyline>
              </svg>
              General Team Emails (FAQ)
            </div>
            <div class="faq-emails-list">
              ${faq.emails.map(item => `
                <div class="faq-email-item">
                  <span class="faq-email-label">${escapeHtml(item.label)}:</span>
                  <a href="mailto:${escapeHtml(item.email)}" class="faq-email-link">${escapeHtml(item.email)}</a>
                </div>
              `).join('')}
            </div>
          </div>
        </div>
      </div>
    </div>
  `;
  container.style.display = 'block';
}

window.toggleFaqBanner = function () {
  const card = document.getElementById('faqCard');
  if (!card) return;
  const isCollapsed = card.classList.toggle('is-collapsed');
  const btn = card.querySelector('.faq-card-header');
  if (btn) {
    btn.setAttribute('aria-expanded', !isCollapsed);
  }
};

window.toggleTreeBranch = function (event, btn) {
  event.stopPropagation();
  const wrapper = btn.closest('.tree-node-wrapper');
  if (wrapper) {
    wrapper.classList.toggle('is-collapsed');
    const isCollapsed = wrapper.classList.contains('is-collapsed');
    btn.setAttribute('aria-expanded', !isCollapsed);
  }
};

window.toggleLeafGroup = function (event, btn) {
  event.stopPropagation();
  const container = btn.closest('.tree-group-label-container');
  if (container) {
    const cardsContainer = container.nextElementSibling;
    if (cardsContainer && cardsContainer.classList.contains('leaf-group-cards')) {
      const isCollapsed = cardsContainer.style.display === 'none';
      cardsContainer.style.display = isCollapsed ? 'flex' : 'none';
      btn.setAttribute('aria-expanded', isCollapsed);
    }
  }
};

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



function getInitials(name) {
  if (!name) return '?';
  const parts = name.trim().split(/\s+/);
  if (parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase();
  return parts[0][0].toUpperCase();
}

function escapeHtml(value) {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function isCountryGroup(group) {
  return COUNTRY_CODES.includes(group?.trim().toUpperCase());
}

function getCountryHeadCode(emp) {
  const group = emp?.group?.trim().toUpperCase();
  const title = emp?.jobTitle?.trim() || '';
  if (group !== 'HEAD') return null;

  const match = title.match(/,\s*([A-Z]{2})$/);
  if (!match) return null;

  const code = match[1].toUpperCase();
  return isCountryGroup(code) ? code : null;
}

function compareEmployeesByHierarchy(left, right) {
  const gradeDiff = (left?.gradeOrder ?? 999) - (right?.gradeOrder ?? 999);
  if (gradeDiff !== 0) return gradeDiff;

  const jobCompare = (left?.jobTitle || '').localeCompare(right?.jobTitle || '');
  if (jobCompare !== 0) return jobCompare;

  return (left?.name || '').localeCompare(right?.name || '');
}

function renderPhoto(emp, size = 'sm') {
  const initials = getInitials(emp.name);
  const cls = size === 'lg' ? 'photo-lg' : 'photo-sm';
  if (emp.photoUrl && emp.photoUrl !== '/photos/default.jpg') {
    return `<img class="${cls}" src="${escapeHtml(emp.photoUrl)}" alt="${escapeHtml(emp.name || 'Employee photo')}"
      onerror="this.outerHTML='<div class=\\'${cls} initials-circle\\'>${escapeHtml(initials)}</div>'" />`;
  }
  return `<div class="${cls} initials-circle">${escapeHtml(initials)}</div>`;
}

function renderHeadCard(emp, namePrefix = "") {
  return `
    <div class="head-card" data-emp-id="${emp._id}" tabindex="0" role="button" aria-label="Open details for ${escapeHtml(emp.name || 'employee')}">
      ${renderPhoto(emp, 'lg')}
      <div class="head-card-info">
        <div class="head-card-name">${namePrefix}${escapeHtml(emp.name || '—')}</div>
        <div class="head-card-job">${escapeHtml(emp.jobTitle || '—')}</div>
        ${emp.email ? `<div class="emp-email">${escapeHtml(emp.email)}</div>` : ''}
      </div>
    </div>
  `;
}

function renderEmpCard(emp, namePrefix = "") {
  return `
    <div class="emp-card" data-emp-id="${emp._id}" tabindex="0" role="button" aria-label="Open details for ${escapeHtml(emp.name || 'employee')}">
      ${renderPhoto(emp, 'sm')}
      <div class="emp-card-info">
        <div class="emp-card-name">${namePrefix}${escapeHtml(emp.name || '—')}</div>
        <div class="emp-card-job">${escapeHtml(emp.jobTitle || '—')}</div>
        ${emp.email ? `<div class="emp-email">${escapeHtml(emp.email)}</div>` : ''}
      </div>
    </div>
  `;
}

function renderColumn(title, employees, isCountry = false) {
  const flag = isCountry ? (COUNTRY_FLAGS[title] || '🌍') : '';
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

function findEmployeeMatch({ name, email }) {
  return allEmployeesLookup.find(candidate =>
    (email && candidate.email === email) ||
    (name && candidate.name === name)
  );
}

function buildPmDetails(emp) {
  if (!emp.pm) return null;

  const linkedPm = findEmployeeMatch({ name: emp.pm, email: emp.pmEmail });
  return {
    name: emp.pm,
    email: emp.pmEmail || linkedPm?.email || '',
    jobTitle: emp.pmJobTitle || linkedPm?.jobTitle || '',
    photoUrl: linkedPm?.photoUrl || '/photos/default.jpg',
    cs: linkedPm?.cs || '',
    group: linkedPm?.group || '',
    areaOfDuties: linkedPm?.areaOfDuties || '',
    jobDescription: linkedPm?.jobDescription || ''
  };
}

function renderModalAvatar(person, variant) {
  const initials = getInitials(person?.name || (variant === 'pm' ? 'PM' : '?'));
  const avatarClass = `modal-avatar ${variant === 'pm' ? 'pm-avatar' : 'employee-avatar'}`;

  if (person?.photoUrl && person.photoUrl !== '/photos/default.jpg') {
    return `<img class="${avatarClass}" src="${escapeHtml(person.photoUrl)}" alt="${escapeHtml(person.name || 'Employee photo')}"
      onerror="this.outerHTML='<div class=\\'${avatarClass} initials-circle\\'>${escapeHtml(initials)}</div>'" />`;
  }

  return `<div class="${avatarClass} initials-circle">${escapeHtml(initials)}</div>`;
}

function renderMailAction(email) {
  if (!email) {
    return '<div class="modal-email-empty">Email не указан</div>';
  }

  const safeEmail = escapeHtml(email);
  return `
    <div class="modal-email-row">
      <div class="modal-email-link">${safeEmail}</div>
      <a class="modal-mail-btn" href="mailto:${safeEmail}" data-email="${safeEmail}">Mail</a>
    </div>
  `;
}

function renderLikeButtons(email) {
  if (!email) return '';
  const key = email.trim().toLowerCase();
  const summary = likeSummaries[key] || { solvedCount: 0, exceededCount: 0 };

  return `
    <div class="like-section">
      <div class="like-section-label">Reviews</div>
      <div class="like-buttons">
        <button class="like-btn like-btn-solved" data-email="${escapeHtml(email)}" data-type="SOLVED" type="button">
          <span class="like-btn-icon">👍</span>
          <span class="like-btn-text">Delivered Results</span>
          <span class="like-btn-count" id="likeCount_SOLVED_${escapeHtml(key)}">${summary.solvedCount}</span>
        </button>
        <button class="like-btn like-btn-exceeded" data-email="${escapeHtml(email)}" data-type="EXCEEDED" type="button">
          <span class="like-btn-icon">❤️</span>
          <span class="like-btn-text">Above and Beyond</span>
          <span class="like-btn-count" id="likeCount_EXCEEDED_${escapeHtml(key)}">${summary.exceededCount}</span>
        </button>
      </div>
    </div>
  `;
}

function renderModalContent(emp) {
  const pm = buildPmDetails(emp);
  const employeeMeta = [emp.cs, emp.group].filter(Boolean).join(' · ') || '—';

  return `
    <div class="modal-section">
      <div class="modal-section-label">Employee</div>
      <div class="modal-profile">
        ${renderModalAvatar(emp, 'employee')}
        <div class="modal-profile-body">
          <h2 class="modal-name" id="employeeModalTitle">${escapeHtml(emp.name || '—')}</h2>
          <div class="modal-job">${escapeHtml(emp.jobTitle || '—')}</div>
          <div class="modal-meta">${escapeHtml(employeeMeta)}</div>
        </div>
      </div>
      ${emp.areaOfDuties ? `
        <div class="modal-duties-block">
          <div class="modal-duties-label">Area of Duties</div>
          <div class="modal-duties-value">${escapeHtml(emp.areaOfDuties)}</div>
        </div>
      ` : ''}
      ${emp.jobDescription ? `
        <div class="modal-duties-block">
          <div class="modal-duties-label">Job Description</div>
          <div class="modal-duties-value">${escapeHtml(emp.jobDescription)}</div>
        </div>
      ` : ''}
      ${renderMailAction(emp.email)}
      ${renderLikeButtons(emp.email)}
    </div>
    <div class="modal-section modal-section-pm">
      <div class="modal-section-label">Performance Manager</div>
      ${pm ? `
          <div class="modal-profile">
            ${renderModalAvatar(pm, 'pm')}
            <div class="modal-profile-body">
              <h3 class="modal-name">${escapeHtml(pm.name || '—')}</h3>
              ${pm.jobTitle ? `<div class="modal-job modal-job-pm">${escapeHtml(pm.jobTitle)}</div>` : ''}
              ${[pm.cs, pm.group].filter(Boolean).join(' · ') ? `<div class="modal-meta">${escapeHtml([pm.cs, pm.group].filter(Boolean).join(' · '))}</div>` : ''}
            </div>
          </div>
          ${pm.areaOfDuties ? `
            <div class="modal-duties-block">
              <div class="modal-duties-label">Area of Duties</div>
              <div class="modal-duties-value">${escapeHtml(pm.areaOfDuties)}</div>
            </div>
          ` : ''}
          ${pm.jobDescription ? `
            <div class="modal-duties-block">
              <div class="modal-duties-label">Job Description</div>
              <div class="modal-duties-value">${escapeHtml(pm.jobDescription)}</div>
            </div>
          ` : ''}
          ${renderMailAction(pm.email)}
        ` : `
          <div class="modal-profile modal-profile-empty">
            <div class="modal-avatar pm-avatar initials-circle">PM</div>
            <div class="modal-profile-body">
              <h3 class="modal-name">Performance Manager not assigned</h3>
            </div>
          </div>
        `
    }
    </div>
  `;
}

function ensureEmployeeModal() {
  let overlay = document.getElementById('employeeModal');
  if (overlay) return overlay;

  document.body.insertAdjacentHTML('beforeend', `
    <div class="employee-modal-overlay" id="employeeModal" aria-hidden="true">
      <div class="employee-modal" role="dialog" aria-modal="true" aria-labelledby="employeeModalTitle">
        <button class="employee-modal-close" type="button" aria-label="Close employee details">&times;</button>
        <div class="employee-modal-grid" id="employeeModalContent"></div>
      </div>
    </div>
  `);

  overlay = document.getElementById('employeeModal');
  overlay.addEventListener('click', event => {
    if (event.target === overlay || event.target.closest('.employee-modal-close')) {
      closeEmployeeModal();
    }
  });

  overlay.addEventListener('click', (event) => {
    const likeBtn = event.target.closest('.like-btn');
    if (likeBtn) {
      event.stopPropagation();
      handleLikeClick(likeBtn);
    }

    const mailBtn = event.target.closest('.modal-mail-btn');
    if (mailBtn && mailBtn.dataset.email) {
      logInteraction(mailBtn.dataset.email, 'MAIL_CLICK');
    }
  });

  return overlay;
}

async function handleLikeClick(btn) {
  const email = btn.dataset.email;
  const type = btn.dataset.type;
  if (!email || !type) return;

  btn.disabled = true;
  btn.classList.add('like-btn-sending');

  try {
    const res = await fetch('/api/likes', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ employeeEmail: email, reactionType: type, visitorId: getVisitorId() })
    });

    if (res.ok) {
      const key = email.trim().toLowerCase();
      if (!likeSummaries[key]) likeSummaries[key] = { solvedCount: 0, exceededCount: 0 };
      if (type === 'SOLVED') likeSummaries[key].solvedCount++;
      else likeSummaries[key].exceededCount++;

      const countEl = document.getElementById(`likeCount_${type}_${key}`);
      if (countEl) {
        countEl.textContent = type === 'SOLVED' ? likeSummaries[key].solvedCount : likeSummaries[key].exceededCount;
        countEl.classList.add('like-count-bump');
        setTimeout(() => countEl.classList.remove('like-count-bump'), 400);
      }

      btn.classList.add('like-btn-success');
      setTimeout(() => btn.classList.remove('like-btn-success'), 1200);
    }
  } catch (e) {
    console.error('Failed to send like', e);
  } finally {
    btn.disabled = false;
    btn.classList.remove('like-btn-sending');
  }
}

function openEmployeeModal(emp) {
  const overlay = ensureEmployeeModal();
  const content = document.getElementById('employeeModalContent');
  content.innerHTML = renderModalContent(emp);
  overlay.classList.add('is-open');
  overlay.setAttribute('aria-hidden', 'false');
  document.body.classList.add('modal-open');
  overlay.querySelector('.employee-modal-close')?.focus();

  if (emp && emp.email) {
    logInteraction(emp.email, 'PROFILE_VIEW');
  }
}

function logInteraction(email, type) {
  if (!email || !type) return;
  fetch('/api/interactions', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ employeeEmail: email, interactionType: type, visitorId: getVisitorId() })
  }).catch(e => console.error('Failed to log interaction', e));
}

function closeEmployeeModal() {
  const overlay = document.getElementById('employeeModal');
  if (!overlay) return;
  overlay.classList.remove('is-open');
  overlay.setAttribute('aria-hidden', 'true');
  document.body.classList.remove('modal-open');
}

function bindEmployeeCardEvents() {
  const chart = document.getElementById('orgChart');
  if (!chart || chart.dataset.modalBound === 'true') return;

  chart.addEventListener('click', event => {
    if (event.target.closest('a, .emp-email')) return;
    if (window.getSelection()?.toString().trim()) return;
    const card = event.target.closest('.emp-card, .head-card');
    if (!card) return;

    const emp = employeeDirectory.find(item => String(item._id) === card.dataset.empId);
    if (emp) openEmployeeModal(emp);
  });

  chart.addEventListener('keydown', event => {
    if (event.key !== 'Enter' && event.key !== ' ') return;
    const card = event.target.closest('.emp-card, .head-card');
    if (!card) return;

    event.preventDefault();
    const emp = employeeDirectory.find(item => String(item._id) === card.dataset.empId);
    if (emp) openEmployeeModal(emp);
  });

  document.addEventListener('keydown', event => {
    if (event.key === 'Escape') {
      closeEmployeeModal();
    }
  });

  chart.dataset.modalBound = 'true';
}

function buildEmployeeTree(employees) {
  const emailMap = new Map();
  const nameMap = new Map();

  const nodes = employees.map(emp => {
    const node = {
      emp: emp,
      children: []
    };
    if (emp.email) emailMap.set(emp.email.toLowerCase().trim(), node);
    if (emp.name) nameMap.set(emp.name.toLowerCase().trim(), node);
    return node;
  });

  const roots = [];

  const javidan = employees.find(e => e.name === 'Guliyev, Javidan');
  if (javidan) {
    javidan.pm = 'Turusbekov, Serik';
    javidan.group = 'L1 | L2';
    javidan.isJointNode = true;
  }

  nodes.forEach(node => {
    const emp = node.emp;
    let parentNode = null;

    let pmName = emp.pm;

    if (pmName) {
      parentNode = nameMap.get(pmName.toLowerCase().trim());
    }
    if (!parentNode && emp.pmEmail) {
      parentNode = emailMap.get(emp.pmEmail.toLowerCase().trim());
    }

    if (parentNode) {
      parentNode.children.push(node);
    } else {
      roots.push(node);
    }
  });

  // Логика "Head всегда наверху"
  const headRoots = roots.filter(r => r.emp.group && r.emp.group.trim().toUpperCase() === 'HEAD');
  if (roots.length > 1 && headRoots.length > 0) {
    const mainHead = headRoots[0]; // Берем первого Head как главного
    const otherRoots = roots.filter(r => r !== mainHead);
    otherRoots.forEach(r => mainHead.children.push(r));
    roots.length = 0;
    roots.push(mainHead);
  }

  const sortNodes = (n) => {
    n.children.sort(compareEmployeesByHierarchyForTree);
    n.children.forEach(sortNodes);
  };

  roots.sort(compareEmployeesByHierarchyForTree);
  roots.forEach(sortNodes);

  return roots;
}

function compareEmployeesByHierarchyForTree(a, b) {
  if (a.emp.name === 'Guliyev, Javidan' && b.emp.name === 'Hajiyev, Sabir') return 1;
  if (a.emp.name === 'Hajiyev, Sabir' && b.emp.name === 'Guliyev, Javidan') return -1;
  return compareEmployeesByHierarchy(a.emp, b.emp);
}

function getGroupLabel(parentEmp, children) {
  // Rely exclusively on Excel groups

  // Fallback: if all children have the same group, use it
  const groups = [...new Set(children.map(c => c.emp.group).filter(Boolean))];
  if (groups.length === 1) return groups[0];
  return "";
}

function renderTreeNode(node, isRoot = false, indexInParent = -1, parentName = "") {
  const emp = node.emp;
  const hasChildren = node.children.length > 0;

  // Separate children into leaves and non-leaves (branches)
  const leafChildren = node.children.filter(child => child.children.length === 0);
  const nonLeafChildren = node.children.filter(child => child.children.length > 0);
  let namePrefix = "";

  let html = `
    <div class="tree-node-wrapper">
      <div class="tree-node ${hasChildren ? 'tree-node-has-children' : ''}">
        ${isRoot ? renderHeadCard(emp, namePrefix) : renderEmpCard(emp, namePrefix)}
        ${hasChildren ? `<button class="tree-toggle-btn" aria-expanded="true" onclick="toggleTreeBranch(event, this)" aria-label="Toggle branches"></button>` : ''}
      </div>
  `;

  if (hasChildren) {
    const branches = [];
    const leavesByGroup = new Map();

    node.children.forEach(child => {
      if (child.children.length > 0 || child.emp.isJointNode) {
        branches.push(child);
      } else {
        let gLabel = child.emp.group || "";
        if (!leavesByGroup.has(gLabel)) leavesByGroup.set(gLabel, []);
        leavesByGroup.get(gLabel).push(child);
      }
    });

    html += `
      <div class="tree-children">
        ${branches.map((child, idx) => {
      let gLabel = child.emp.group || "";
      const showLabel = gLabel && gLabel !== emp.group;
      if (showLabel || child.emp.isJointNode) {
        const isSmall = gLabel === 'L1' || gLabel === 'L2' || gLabel === 'L1 | L2';
        const style = isSmall ? 'font-size: 0.75rem; padding: 2px 8px;' : '';
        const jointClass = child.emp.isJointNode ? 'javidan-joint-node' : '';
        return `
                <div class="tree-node-wrapper ${jointClass}">
                  <div class="tree-vertical-children">
                    <div class="tree-vertical-connector"></div>
                    <div class="tree-group-label" style="${style}">${escapeHtml(gLabel)}</div>
                    <div class="tree-node-vertical-branch">
                      ${renderTreeNode(child, false, -1, emp.name)}
                    </div>
                  </div>
                </div>
                `;
      } else {
        return renderTreeNode(child, false, idx, emp.name);
      }
    }).join('')}

        ${leavesByGroup.size > 0 ? `
          <div class="tree-node-wrapper">
            <div class="tree-vertical-children">
              <div class="tree-vertical-connector"></div>
              ${Array.from(leavesByGroup.entries()).map(([gLabel, leaves], groupIdx) => {
      const showLabel = gLabel && gLabel !== emp.group;
      const isSmall = gLabel === 'L1' || gLabel === 'L2';
      const style = isSmall ? 'font-size: 0.75rem; padding: 2px 8px;' : '';
      let groupHtml = '';

      if (showLabel) {
        groupHtml += `
                        <div class="tree-vertical-connector"></div>
                        <div class="tree-group-label" style="${style}">${escapeHtml(gLabel)}</div>
                      `;
      }

      groupHtml += leaves.map((child, idx) => `
                    <div class="tree-vertical-connector"></div>
                    <div class="tree-node-vertical">
                      ${renderEmpCard(child.emp)}
                    </div>
                  `).join('');

      return groupHtml;
    }).join('')}
            </div>
          </div>
        ` : ''}
      </div>
    `;
  }

  html += `
    </div>
  `;
  return html;
}

async function loadFunctionData() {
  const params = new URLSearchParams(window.location.search);
  const cs = params.get('cs');
  if (!cs) { window.location.href = '/'; return; }

  document.title = `CS OrgChart - ${cs}`;
  document.getElementById('funcTitle').textContent = cs;
  renderFaqBanner(cs);
  logPageVisit(cs);

  try {
    const [res, allRes, likesRes] = await Promise.all([
      fetch(`/api/employees?cs=${encodeURIComponent(cs)}`),
      fetch('/api/employees'),
      fetch('/api/likes/summary')
    ]);

    allEmployeesLookup = await allRes.json();
    try { likeSummaries = await likesRes.json(); } catch (e) { likeSummaries = {}; }

    let employees = (await res.json()).map((employee, index) => ({
      ...employee,
      _id: index
    }));

    employeeDirectory = employees;
    closeEmployeeModal();
    document.getElementById('funcCount').textContent = `${employees.length} employees`;

    if (cs === 'ITS') {
      const roots = buildEmployeeTree(employees);
      if (roots.length > 0) {
        let treeHtml = '';
        if (roots.length === 1) {
          treeHtml = `
            <div class="org-tree-container">
              <div class="org-tree-wrapper">
                ${renderTreeNode(roots[0], true)}
              </div>
            </div>
          `;
        } else {
          treeHtml = `
            <div class="org-tree-container">
              <div class="org-tree-wrapper">
                <div class="tree-children" style="padding-top: 0; margin-top: 0;">
                  ${roots.map(r => renderTreeNode(r, true)).join('')}
                </div>
              </div>
            </div>
          `;
        }
        document.getElementById('orgChart').innerHTML = treeHtml;
        bindEmployeeCardEvents();
        setTimeout(applyJavidanHack, 50);
        return;
      }
    }

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

    headEmps.sort(compareEmployeesByHierarchy);
    Object.values(kzGroups).forEach(group => group.sort(compareEmployeesByHierarchy));
    Object.values(ctryGroups).forEach(group => group.sort(compareEmployeesByHierarchy));

    let html = '';

    if (headEmps.length > 0) {
      const hasChildren = Object.keys(kzGroups).length > 0 || Object.keys(ctryGroups).length > 0;
      html += `
        <div class="head-section">
          <div class="head-cards" style="position: relative;">
            ${headEmps.map(emp => renderHeadCard(emp)).join('')}
            ${hasChildren ? `<button class="tree-toggle-btn" aria-expanded="true" onclick="toggleHeadSection(event, this)" aria-label="Toggle head section"></button>` : ''}
          </div>
          <div class="connector-v"></div>
        </div>
      `;
    }

    const hasKz = Object.keys(kzGroups).length > 0;
    const hasCtry = Object.keys(ctryGroups).length > 0;

    if (hasKz || hasCtry) {
      if (hasKz && hasCtry) {
        const combined = [];
        Object.entries(kzGroups).forEach(([g, emps]) => combined.push({ title: g, emps, isCountry: false }));
        Object.entries(ctryGroups).forEach(([g, emps]) => combined.push({ title: g, emps, isCountry: true }));

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
              ${Object.entries(kzGroups).map(([g, emps]) => renderColumn(g, emps, false)).join('')}
            </div>
          </div>
        `;
      } else {
        html += `
          <div class="section-block country-section-block">
            <div class="org-grid">
              ${Object.entries(ctryGroups).map(([c, emps]) => renderColumn(c, emps, true)).join('')}
            </div>
          </div>
        `;
      }
    }

    if (!html) {
      html = `<div class="no-results">No employees found for "${escapeHtml(cs)}"</div>`;
    }

    document.getElementById('orgChart').innerHTML = html;
    bindEmployeeCardEvents();
    layoutOrgGrids();
  } catch (e) {
    console.error(e);
    document.getElementById('orgChart').innerHTML = '<div class="no-results">Error loading data</div>';
  }
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

    if (isMixedGrid) {
      const mainItems = items.filter(item => item.dataset.country !== 'true');
      const countryItems = items.filter(item => item.dataset.country === 'true');

      if (mainItems.length > 0 && countryItems.length > 0 && columns.length > 1) {
        const proportionalMainColumns = Math.round((mainItems.length / items.length) * columns.length);
        const mainColumnCount = Math.max(1, Math.min(columns.length - 1, proportionalMainColumns));
        const countryColumnCount = Math.max(1, columns.length - mainColumnCount);
        const mainColumns = columns.slice(0, mainColumnCount);
        const countryColumns = columns.slice(mainColumnCount);

        const placeIntoShortestColumn = (columnItems, targetColumns) => {
          columnItems.forEach(item => {
            item.style.width = '100%';
            const target = targetColumns.reduce((shortest, col) => {
              return col.offsetHeight < shortest.offsetHeight ? col : shortest;
            }, targetColumns[0]);
            target.appendChild(item);
          });
        };

        placeIntoShortestColumn(mainItems, mainColumns);
        placeIntoShortestColumn(countryItems, countryColumns);
        return;
      }
    }

    items.forEach(item => {
      item.style.width = '100%';
      const target = columns.reduce((shortest, col) => {
        return col.offsetHeight < shortest.offsetHeight ? col : shortest;
      }, columns[0]);
      target.appendChild(item);
    });
  });
}

function getVisitorId() {
  let id = localStorage.getItem('visitorId');
  if (!id) {
    id = 'user-' + Math.random().toString(36).substring(2, 10) + Date.now().toString(36);
    localStorage.setItem('visitorId', id);
  }
  return id;
}

function logPageVisit(pageName) {
  fetch('/api/page-visits', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ pageName: pageName, visitorId: getVisitorId() })
  }).catch(e => console.error('Failed to log page visit', e));
}

window.addEventListener('resize', debounce(layoutOrgGrids, 120));

window.refreshOrgChart = async () => {
  await loadFunctionData();
};

loadFunctionData();

window.applyJavidanHack = function () {
  console.log("applyJavidanHack started");
  try {
    const allNameEls = document.querySelectorAll('.org-tree-wrapper .emp-card-name, .org-tree-wrapper .head-card-name');
    const sabirEl = Array.from(allNameEls).find(el => el.textContent.includes('Hajiyev, Sabir'));
    const zhanelEl = Array.from(allNameEls).find(el => el.textContent.includes('Tleumbetova, Zhanel'));
    const javidanEl = Array.from(allNameEls).find(el => el.textContent.includes('Guliyev, Javidan'));

    if (!sabirEl || !zhanelEl || !javidanEl) {
      console.error("Missing nodes", { sabir: !!sabirEl, zhanel: !!zhanelEl, javidan: !!javidanEl });
      return;
    }

    const container = document.querySelector('.org-tree-wrapper');
    let svg = document.getElementById('javidan-lines');
    if (!svg) {
      svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
      svg.id = 'javidan-lines';
      svg.style.position = 'absolute';
      svg.style.top = '0';
      svg.style.left = '0';
      svg.style.pointerEvents = 'none';
      svg.style.zIndex = '5';
      container.style.position = 'relative';
      container.appendChild(svg);
    }

    const draw = () => {
      try {
        svg.innerHTML = '';
        const customJavidan = javidanEl.closest('.javidan-joint-node');
        if (!customJavidan) return;

        const cRect = container.getBoundingClientRect();
        if (cRect.width === 0 || cRect.height === 0) return;

        svg.setAttribute('width', cRect.width);
        svg.setAttribute('height', cRect.height);
        svg.style.width = cRect.width + 'px';
        svg.style.height = cRect.height + 'px';

        // Find the toggle buttons (collapse chevrons) under Sabir and Zhanel
        const sabirWrapper = sabirEl.closest('.tree-node-wrapper');
        const zhanelWrapper = zhanelEl.closest('.tree-node-wrapper');
        const sabirToggle = sabirWrapper ? sabirWrapper.querySelector('.tree-toggle-btn') : null;
        const zhanelToggle = zhanelWrapper ? zhanelWrapper.querySelector('.tree-toggle-btn') : null;

        // Start from bottom of toggle buttons (or card bottom as fallback)
        const sSource = sabirToggle || sabirEl.closest('.tree-node');
        const zSource = zhanelToggle || zhanelEl.closest('.tree-node');
        const sRect = sSource.getBoundingClientRect();
        const zRect = zSource.getBoundingClientRect();

        // End at the L1|L2 group label
        const jLabel = customJavidan.querySelector('.tree-group-label');
        const jTarget = jLabel || javidanEl.closest('.tree-node');
        const jRect = jTarget.getBoundingClientRect();

        const sX = sRect.left + sRect.width / 2 - cRect.left;
        const sY = sRect.bottom - cRect.top;

        const zX = zRect.left + zRect.width / 2 - cRect.left;
        const zY = zRect.bottom - cRect.top;

        const jX = jRect.left + jRect.width / 2 - cRect.left;
        const jY = jRect.top - cRect.top;

        // Horizontal bend point halfway between source bottom and target top
        const bendY = Math.max(sY, zY) + (jY - Math.max(sY, zY)) * 0.5;

        const drawPath = (x1, y1, x2, y2) => {
          const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
          const d = `M ${x1} ${y1} L ${x1} ${bendY} L ${x2} ${bendY} L ${x2} ${y2}`;
          path.setAttribute('d', d);
          path.setAttribute('stroke', '#d8dce5');
          path.setAttribute('stroke-width', '2');
          path.setAttribute('fill', 'none');
          svg.appendChild(path);
        };

        drawPath(sX, sY, jX, jY);
        drawPath(zX, zY, jX, jY);
        svg.style.overflow = 'visible';
      } catch (e) {
        console.error("Error drawing SVG lines:", e);
      }
    };

    setTimeout(draw, 100);
    setTimeout(draw, 500);
    setTimeout(draw, 1500); // Extra retry
    window.addEventListener('resize', draw);
    document.querySelectorAll('.tree-toggle-btn').forEach(btn => {
      btn.addEventListener('click', () => setTimeout(draw, 350));
    });
  } catch (e) {
    console.error("Javidan hack failed:", e);
  }
};
