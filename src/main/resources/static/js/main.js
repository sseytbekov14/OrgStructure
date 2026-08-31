const FUNC_ICONS = {
  People: "M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2M23 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75M9 7a4 4 0 1 0 0-8 4 4 0 0 0 0 8z",
  "F&O": "M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z",
  Finance: "M12 1v22M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6",
  Markets: "M18 20V10M12 20V4M6 20v-6",
  OGC: "M3 6l9-4 9 4v6c0 5.25-3.75 10.15-9 11.5C6.75 22.15 3 17.25 3 12V6z",
  QRM: "M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0zM12 9v4M12 17h.01",
  Tenders: "M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8zM14 2v6h6M16 13H8M16 17H8M10 9H8",
  ITS: "M20 16V7a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v9m16 0H4m16 0 1.28 2.55a1 1 0 0 1-.9 1.45H3.62a1 1 0 0 1-.9-1.45L4 16"
};

let allEmployees = [];
let searchTimer = null;
let likeSummaries = {};

function getEmployeeLookupKey(employee) {
  return `${employee.email || ""}||${employee.name || ""}||${employee.cs || ""}||${employee.jobTitle || ""}`;
}

function svgIcon(path) {
  return `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" width="22" height="22"><path d="${path}"></path></svg>`;
}

function getInitials(name) {
  if (!name) return "?";
  const parts = name.trim().split(" ");
  if (parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase();
  return parts[0][0].toUpperCase();
}

function escapeHtml(value = "") {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function renderPhoto(emp, size = "sm") {
  const initials = getInitials(emp.name);
  const cls = size === "lg" ? "photo-lg" : "photo-sm";
  if (emp.photoUrl && emp.photoUrl !== "/photos/default.jpg") {
    return `<img class="${cls}" src="${escapeHtml(emp.photoUrl)}" alt="${escapeHtml(emp.name || "Employee photo")}"
      onerror="this.outerHTML='<div class=\\'${cls} initials-circle\\'>${escapeHtml(initials)}</div>'" />`;
  }
  return `<div class="${cls} initials-circle">${escapeHtml(initials)}</div>`;
}

function findEmployeeMatch({ name, email }) {
  return allEmployees.find((candidate) =>
    (email && candidate.email === email) ||
    (name && candidate.name === name)
  );
}

function buildPmDetails(emp) {
  if (!emp.pm) return null;

  const linkedPm = findEmployeeMatch({ name: emp.pm, email: emp.pmEmail });
  return {
    name: emp.pm,
    email: emp.pmEmail || linkedPm?.email || "",
    jobTitle: emp.pmJobTitle || linkedPm?.jobTitle || "",
    photoUrl: linkedPm?.photoUrl || "/photos/default.jpg",
    cs: linkedPm?.cs || "",
    group: linkedPm?.group || "",
    areaOfDuties: linkedPm?.areaOfDuties || "",
    jobDescription: linkedPm?.jobDescription || ""
  };
}

function renderModalAvatar(person, variant) {
  const initials = getInitials(person?.name || (variant === "pm" ? "PM" : "?"));
  const avatarClass = `modal-avatar ${variant === "pm" ? "pm-avatar" : "employee-avatar"}`;

  if (person?.photoUrl && person.photoUrl !== "/photos/default.jpg") {
    return `<img class="${avatarClass}" src="${escapeHtml(person.photoUrl)}" alt="${escapeHtml(person.name || "Employee photo")}"
      onerror="this.outerHTML='<div class=\\'${avatarClass} initials-circle\\'>${escapeHtml(initials)}</div>'" />`;
  }

  return `<div class="${avatarClass} initials-circle">${escapeHtml(initials)}</div>`;
}

function renderMailAction(email) {
  if (!email) {
    return '<div class="modal-email-empty">Email not specified</div>';
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
  const employeeMeta = [emp.cs, emp.group].filter(Boolean).join(" · ") || "—";

  return `
    <div class="modal-section">
      <div class="modal-section-label">Employee</div>
      <div class="modal-profile">
        ${renderModalAvatar(emp, "employee")}
        <div class="modal-profile-body">
          <h2 class="modal-name" id="employeeModalTitle">${escapeHtml(emp.name || "—")}</h2>
          <div class="modal-job">${escapeHtml(emp.jobTitle || "—")}</div>
          <div class="modal-meta">${escapeHtml(employeeMeta)}</div>
        </div>
      </div>
      ${emp.areaOfDuties ? `
        <div class="modal-duties-block">
          <div class="modal-duties-label">Area of Duties</div>
          <div class="modal-duties-value">${escapeHtml(emp.areaOfDuties)}</div>
        </div>
      ` : ""}
      ${emp.jobDescription ? `
        <div class="modal-duties-block">
          <div class="modal-duties-label">Job Description</div>
          <div class="modal-duties-value">${escapeHtml(emp.jobDescription)}</div>
        </div>
      ` : ""}
      ${renderMailAction(emp.email)}
      ${renderLikeButtons(emp.email)}
    </div>
    <div class="modal-section modal-section-pm">
      <div class="modal-section-label">Performance Manager</div>
      ${
        pm ? `
          <div class="modal-profile">
            ${renderModalAvatar(pm, "pm")}
            <div class="modal-profile-body">
              <h3 class="modal-name">${escapeHtml(pm.name || "—")}</h3>
              ${pm.jobTitle ? `<div class="modal-job modal-job-pm">${escapeHtml(pm.jobTitle)}</div>` : ""}
              ${[pm.cs, pm.group].filter(Boolean).join(" · ") ? `<div class="modal-meta">${escapeHtml([pm.cs, pm.group].filter(Boolean).join(" · "))}</div>` : ""}
            </div>
          </div>
          ${pm.areaOfDuties ? `
            <div class="modal-duties-block">
              <div class="modal-duties-label">Area of Duties</div>
              <div class="modal-duties-value">${escapeHtml(pm.areaOfDuties)}</div>
            </div>
          ` : ""}
          ${pm.jobDescription ? `
            <div class="modal-duties-block">
              <div class="modal-duties-label">Job Description</div>
              <div class="modal-duties-value">${escapeHtml(pm.jobDescription)}</div>
            </div>
          ` : ""}
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
  let overlay = document.getElementById("employeeModal");
  if (overlay) return overlay;

  document.body.insertAdjacentHTML("beforeend", `
    <div class="employee-modal-overlay" id="employeeModal" aria-hidden="true">
      <div class="employee-modal" role="dialog" aria-modal="true" aria-labelledby="employeeModalTitle">
        <button class="employee-modal-close" type="button" aria-label="Close employee details">&times;</button>
        <div class="employee-modal-grid" id="employeeModalContent"></div>
      </div>
    </div>
  `);

  overlay = document.getElementById("employeeModal");
  overlay.addEventListener("click", (event) => {
    if (event.target === overlay || event.target.closest(".employee-modal-close")) {
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
  const content = document.getElementById("employeeModalContent");
  content.innerHTML = renderModalContent(emp);
  overlay.classList.add("is-open");
  overlay.setAttribute("aria-hidden", "false");
  document.body.classList.add("modal-open");
  overlay.querySelector(".employee-modal-close")?.focus();
  
  if (emp && emp.email) {
    logInteraction(emp.email, 'PROFILE_VIEW');
  }
}

function closeEmployeeModal() {
  const overlay = document.getElementById("employeeModal");
  if (!overlay) return;
  overlay.classList.remove("is-open");
  overlay.setAttribute("aria-hidden", "true");
  document.body.classList.remove("modal-open");
}

function bindSearchResultEvents() {
  const container = document.getElementById("searchResults");
  if (!container || container.dataset.modalBound === "true") return;

  container.addEventListener("click", (event) => {
    if (event.target.closest("a, .result-email")) return;
    if (window.getSelection()?.toString().trim()) return;
    const card = event.target.closest(".result-card");
    if (!card) return;

    const employee = allEmployees.find((item) => getEmployeeLookupKey(item) === card.dataset.empKey);
    if (employee) openEmployeeModal(employee);
  });

  container.addEventListener("keydown", (event) => {
    if (event.key !== "Enter" && event.key !== " ") return;
    const card = event.target.closest(".result-card");
    if (!card) return;

    event.preventDefault();
    const employee = allEmployees.find((item) => getEmployeeLookupKey(item) === card.dataset.empKey);
    if (employee) openEmployeeModal(employee);
  });

  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape") {
      closeEmployeeModal();
    }
  });

  container.dataset.modalBound = "true";
}

function getOrbitPositions(count) {
  if (count <= 0) return [];

  const radiusX = count <= 6 ? 31 : count <= 9 ? 37 : 41;
  const radiusY = count <= 6 ? 24 : count <= 9 ? 30 : 34;
  const startAngle = -90;

  return Array.from({ length: count }, (_, index) => {
    const angle = ((startAngle + (360 / count) * index) * Math.PI) / 180;
    return {
      x: (50 + Math.cos(angle) * radiusX).toFixed(2),
      y: (50 + Math.sin(angle) * radiusY).toFixed(2)
    };
  });
}

async function loadDirectoryData() {
  try {
    const [empRes, funcRes, likesRes] = await Promise.all([
      fetch("/api/employees"),
      fetch("/api/functions"),
      fetch("/api/likes/summary")
    ]);

    allEmployees = await empRes.json();
    try { likeSummaries = await likesRes.json(); } catch(e) { likeSummaries = {}; }
    const functions = await funcRes.json();

    closeEmployeeModal();
    document.getElementById("empCount").textContent = `${allEmployees.length} employees`;
    document.getElementById("headerSubtitle").textContent = `${functions.length} departments - ${allEmployees.length} employees`;
    renderFunctions(functions);

    const activeQuery = document.getElementById("searchInput")?.value?.trim() || "";
    if (activeQuery.length >= 2) {
      handleSearch(activeQuery);
    } else {
      document.getElementById("functionsSection").style.display = "";
      document.getElementById("searchSection").style.display = "none";
      document.getElementById("searchClear").style.display = "none";
    }
  } catch (error) {
    document.getElementById("empCount").textContent = "Error";
    console.error(error);
  }
}

function renderFunctions(functions) {
  const orbit = document.getElementById("funcOrbit");
  const links = document.getElementById("orbitLinks");

  if (!functions.length) {
    orbit.innerHTML = `<div class="no-results">No functions found</div>`;
    links.innerHTML = "";
    return;
  }

  const positions = getOrbitPositions(functions.length);

  orbit.innerHTML = functions
    .map((cs, index) => {
      const count = allEmployees.filter((employee) => employee.cs === cs).length;
      const iconPath = FUNC_ICONS[cs] || FUNC_ICONS.Tenders;
      const { x, y } = positions[index];

      return `
        <a
          class="func-node"
          href="/function.html?cs=${encodeURIComponent(cs)}"
          style="--x:${x}%; --y:${y}%"
          aria-label="Open ${escapeHtml(cs)} function"
        >
          <div class="func-node-icon">${svgIcon(iconPath)}</div>
          <div class="func-node-body">
            <div class="func-node-name">${escapeHtml(cs)}</div>
            <div class="func-node-count">${count} employees</div>
          </div>
        </a>
      `;
    })
    .join("");

  links.innerHTML = positions
    .map(
      ({ x, y }) =>
        `<line class="orbit-link" x1="50" y1="50" x2="${x}" y2="${y}"></line>`
    )
    .join("");
}

function renderSearchResults(employees, query) {
  const container = document.getElementById("searchResults");
  const label = document.getElementById("searchResultLabel");

  label.textContent = `Results for "${query}" - ${employees.length} found`;

  if (employees.length === 0) {
    container.innerHTML = `<div class="no-results">No employees found for "${escapeHtml(query)}"</div>`;
    return;
  }

  const groups = {};

  employees.forEach((employee) => {
    const key = `${employee.cs}||${employee.group}||${employee.jobTitle}`;
    if (!groups[key]) {
      groups[key] = {
        cs: employee.cs,
        group: employee.group,
        jobTitle: employee.jobTitle,
        emps: []
      };
    }
    groups[key].emps.push(employee);
  });

  container.innerHTML = Object.values(groups)
    .map(({ cs, group, jobTitle, emps }) => {
      const groupLabel = group && group.toUpperCase() !== "HEAD" ? `${cs} - ${group}` : cs;

      const empCards = emps
        .map((emp) => {
          const initials = getInitials(emp.name);
          const emailHtml = emp.email
            ? `<span class="result-email">${escapeHtml(emp.email)}</span>`
            : "";

          return `
            <div class="result-card" data-emp-key="${escapeHtml(getEmployeeLookupKey(emp))}" tabindex="0" role="button" aria-label="Open details for ${escapeHtml(emp.name || "employee")}">
              <div class="result-avatar">${initials}</div>
              <div class="result-name">${escapeHtml(emp.name || "-")}</div>
              ${emailHtml}
            </div>
          `;
        })
        .join("");

      return `
        <div class="result-group-block">
          <div class="result-group-label">${escapeHtml(groupLabel || "-")}</div>
          <div class="result-job-title">${escapeHtml(jobTitle || "")}</div>
          ${empCards}
        </div>
      `;
    })
    .join("");

  bindSearchResultEvents();
}

function handleSearch(query) {
  const funcSection = document.getElementById("functionsSection");
  const searchSection = document.getElementById("searchSection");
  const clearBtn = document.getElementById("searchClear");

  if (!query || query.trim().length < 2) {
    funcSection.style.display = "";
    searchSection.style.display = "none";
    clearBtn.style.display = "none";
    return;
  }

  clearBtn.style.display = "";
  funcSection.style.display = "none";
  searchSection.style.display = "";

  const pmToggle = document.getElementById("searchPmToggle");
  const isPmSearch = pmToggle && pmToggle.checked;
  const apiQuery = isPmSearch ? `pm:${query.trim()}` : query.trim();

  fetch(`/api/search?q=${encodeURIComponent(apiQuery)}`)
    .then((response) => response.json())
    .then((data) => {
      renderSearchResults(data, query.trim());
      logSearchQuery(query.trim(), data.length);
    })
    .catch((error) => console.error(error));
}

function logSearchQuery(query, resultsCount) {
  if (!query || query.length < 2) return;
  fetch('/api/search-logs', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ query, resultsCount, visitorId: getVisitorId() })
  }).catch(e => console.error('Failed to log search', e));
}

function logInteraction(email, type) {
  if (!email || !type) return;
  fetch('/api/interactions', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ employeeEmail: email, interactionType: type, visitorId: getVisitorId() })
  }).catch(e => console.error('Failed to log interaction', e));
}

document.getElementById("searchInput").addEventListener("input", (event) => {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(() => handleSearch(event.target.value), 300);
});

document.getElementById("searchClear").addEventListener("click", () => {
  document.getElementById("searchInput").value = "";
  handleSearch("");
});

const pmToggle = document.getElementById("searchPmToggle");
const pmToggleLabel = document.getElementById("searchPmToggleLabel");
if (pmToggle && pmToggleLabel) {
  pmToggle.addEventListener("change", (e) => {
    if (e.target.checked) {
      pmToggleLabel.classList.add("is-active");
    } else {
      pmToggleLabel.classList.remove("is-active");
    }
    const searchInput = document.getElementById("searchInput");
    if (searchInput) {
      handleSearch(searchInput.value);
    }
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

window.refreshOrgChart = async () => {
  await loadDirectoryData();
};

loadDirectoryData();
logPageVisit('Main');
