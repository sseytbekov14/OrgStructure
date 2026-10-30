const DEFAULT_FUNC_ICON = "M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8zM14 2v6h6M16 13H8M16 17H8M10 9H8";
const FUNC_ICONS = {
  People: "M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2M23 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75M9 7a4 4 0 1 0 0-8 4 4 0 0 0 0 8z",
  "F&O": "M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z",
  Finance: "M12 1v22M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6",
  Markets: "M18 20V10M12 20V4M6 20v-6",
  OGC: "M3 6l9-4 9 4v6c0 5.25-3.75 10.15-9 11.5C6.75 22.15 3 17.25 3 12V6z",
  QRM: "M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0zM12 9v4M12 17h.01",
  ITS: "M20 16V7a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v9m16 0H4m16 0 1.28 2.55a1 1 0 0 1-.9 1.45H3.62a1 1 0 0 1-.9-1.45L4 16"
};

let allEmployees = [];
let searchTimer = null;
let searchController = null;
let searchResults = [];
let searchQuery = "";
let functionFilter = "All";

function svgIcon(path) {
  return `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" width="22" height="22"><path d="${path}"></path></svg>`;
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

const MIN_QUERY_LENGTH = 2;
const MATCH_FIELDS = [
  ["Area of duties", "areaOfDuties"],
  ["Job description", "jobDescription"],
  ["Email", "email"]
];

const searchInput = document.getElementById("searchInput");
let spinnerTimer = null;
let openFirstWhenReady = false;

async function loadDirectoryData() {
  const count = document.getElementById("centralCount");
  const copy = document.getElementById("centralCopy");

  try {
    const [empRes, funcRes, likesRes] = await Promise.all([
      apiFetch("/api/employees"),
      apiFetch("/api/functions"),
      apiFetch("/api/likes/summary")
    ]);

    allEmployees = await empRes.json();
    setDirectory(allEmployees);
    try { setLikeSummaries(await likesRes.json()); } catch (e) { setLikeSummaries({}); }
    const functions = await funcRes.json();

    count.textContent = `${allEmployees.length} people`;
    renderFunctions(functions);

    const activeQuery = searchInput.value.trim();
    if (activeQuery.length >= MIN_QUERY_LENGTH) {
      handleSearch(activeQuery, { log: false });
    } else {
      showFunctions();
    }
  } catch (error) {
    count.textContent = "Couldn't load data";
    copy.textContent = "Refresh the page to try again.";
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
      const iconPath = FUNC_ICONS[cs] || DEFAULT_FUNC_ICON;
      const { x, y } = positions[index];

      return `
        <a
          class="func-node"
          href="/function.html?cs=${encodeURIComponent(cs)}"
          style="--x:${x}%; --y:${y}%"
          aria-label="Open ${escapeHtml(cs)} function, ${count} employees"
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
    .map(({ x, y }) => `<line class="orbit-link" x1="50" y1="50" x2="${x}" y2="${y}"></line>`)
    .join("");
}

/* ---------- Search ---------- */

function updateSearchChrome() {
  const value = searchInput.value;
  document.getElementById("searchClear").hidden = !value;
  document.getElementById("searchKbd").hidden = !!value;
  document.getElementById("searchHint").hidden = value.trim().length !== 1;
}

function syncQueryToUrl(query) {
  try {
    history.replaceState(null, "", query ? `/?q=${encodeURIComponent(query)}` : "/");
  } catch (e) { /* ignore */ }
}

function setSearchLoading(isLoading) {
  clearTimeout(spinnerTimer);
  const spinner = document.getElementById("searchSpinner");
  document.getElementById("searchSection").setAttribute("aria-busy", String(isLoading));
  if (isLoading) {
    spinnerTimer = setTimeout(() => { spinner.hidden = false; }, 150);
  } else {
    spinner.hidden = true;
  }
}

function showFunctions() {
  document.getElementById("functionsSection").hidden = false;
  document.getElementById("searchSection").hidden = true;
}

function queryWords(query) {
  return query.toLowerCase().replace(/[^\p{L}\p{N}\s]/gu, " ").split(/\s+/).filter(Boolean);
}

function snippetAround(text, word, radius = 48) {
  const clean = String(text).replace(/\s+/g, " ").trim();
  const index = clean.toLowerCase().indexOf(word);
  if (index < 0) return clean;
  const start = Math.max(0, index - radius);
  const end = Math.min(clean.length, index + word.length + radius);
  return `${start > 0 ? "…" : ""}${clean.slice(start, end)}${end < clean.length ? "…" : ""}`;
}

// Explains results that matched on a field the row does not show
function renderMatchContext(employee, query) {
  const shown = [employee.name, employee.jobTitle, employee.cs, employee.group].join(" ").toLowerCase();
  const hidden = queryWords(query).filter((word) => !shown.includes(word));
  if (!hidden.length) return "";

  for (const [label, key] of MATCH_FIELDS) {
    const value = employee[key];
    if (!value) continue;
    const word = hidden.find((item) => value.toLowerCase().includes(item));
    if (word) {
      return `<span class="result-match"><span class="result-match-label">${label}:</span> ${highlightText(snippetAround(value, word), query)}</span>`;
    }
  }
  return "";
}

function renderEmptyState() {
  return `
    <div class="search-empty">
      <span class="search-empty-icon" aria-hidden="true">
        <svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5M8.5 8.5l5 5M13.5 8.5l-5 5"/></svg>
      </span>
      <p class="search-empty-title">No one found for “${escapeHtml(searchQuery)}”</p>
      <p class="search-empty-text">Check the spelling, or try a job title, group or function.</p>
      <button type="button" class="tool-btn" data-clear-search>Clear search</button>
    </div>
  `;
}

function visibleResults() {
  return functionFilter === "All"
    ? searchResults
    : searchResults.filter((employee) => employee.cs === functionFilter);
}

function renderSearchResults() {
  const container = document.getElementById("searchResults");
  const meta = document.getElementById("searchResultsMeta");
  const filters = document.getElementById("searchFilters");

  const counts = { All: searchResults.length };
  searchResults.forEach((employee) => {
    counts[employee.cs] = (counts[employee.cs] || 0) + 1;
  });
  if (!counts[functionFilter]) functionFilter = "All";

  const functionNames = Object.keys(counts).filter((name) => name !== "All").sort();
  filters.innerHTML = searchResults.length
    ? ["All", ...functionNames].map((name) => `
        <button type="button" class="search-filter" data-filter="${escapeHtml(name)}" aria-pressed="${name === functionFilter}">
          ${escapeHtml(name)}<span class="search-filter-count">${counts[name]}</span>
        </button>`).join("")
    : "";

  const visible = visibleResults();
  meta.textContent = searchResults.length
    ? `${visible.length} ${visible.length === 1 ? "person" : "people"} for “${searchQuery}”`
    : "";

  if (visible.length === 0) {
    container.innerHTML = renderEmptyState();
    return;
  }

  container.innerHTML = visible
    .map((employee) => {
      const index = searchResults.indexOf(employee);
      return `
        <div class="result-row">
          <button type="button" class="result-main" data-result-index="${index}" aria-label="Open profile of ${escapeHtml(employee.name || "employee")}">
            ${renderAvatar(employee, "md")}
            <span class="result-body">
              <span class="result-name" title="${escapeHtml(employee.name || "")}">${highlightText(employee.name || "—", searchQuery)}</span>
              <span class="result-title" title="${escapeHtml(employee.jobTitle || "")}">${highlightText(employee.jobTitle || "", searchQuery)}</span>
              <span class="result-tags">
                ${employee.cs ? `<span class="result-tag result-tag-function">${highlightText(employee.cs, searchQuery)}</span>` : ""}
                ${employee.group ? `<span class="result-tag result-tag-group">${highlightText(employee.group, searchQuery)}</span>` : ""}
              </span>
              ${renderCardFacts(employee)}
              ${renderMatchContext(employee, searchQuery)}
            </span>
            <span class="result-pm">${employee.pm ? `PM <b>${escapeHtml(employee.pm)}</b>` : ""}</span>
          </button>
        </div>
      `;
    })
    .join("");
}

function openFirstResult() {
  const first = visibleResults()[0];
  if (first) openEmployeeProfile(first);
}

function handleSearch(query, { log = true } = {}) {
  const trimmed = (query || "").trim();
  syncQueryToUrl(trimmed.length >= MIN_QUERY_LENGTH ? trimmed : "");

  if (trimmed.length < MIN_QUERY_LENGTH) {
    searchController?.abort();
    setSearchLoading(false);
    openFirstWhenReady = false;
    showFunctions();
    return;
  }

  document.getElementById("functionsSection").hidden = true;
  document.getElementById("searchSection").hidden = false;

  searchController?.abort();
  searchController = new AbortController();
  setSearchLoading(true);

  apiFetch(`/api/search?q=${encodeURIComponent(trimmed)}`, { signal: searchController.signal })
    .then((response) => response.json())
    .then((data) => {
      searchResults = data;
      searchQuery = trimmed;
      setSearchLoading(false);
      renderSearchResults();
      if (log) logSearchQuery(trimmed, data.length);
      if (openFirstWhenReady) {
        openFirstWhenReady = false;
        openFirstResult();
      }
    })
    .catch((error) => {
      if (error.name === "AbortError") return;
      setSearchLoading(false);
      console.error(error);
    });
}

function clearSearch() {
  searchInput.value = "";
  updateSearchChrome();
  handleSearch("");
  searchInput.focus();
}

function focusResult(offset) {
  const buttons = Array.from(document.querySelectorAll("#searchResults .result-main"));
  if (!buttons.length) return;
  const current = buttons.indexOf(document.activeElement);
  const next = current + offset;
  if (next < 0) {
    searchInput.focus();
  } else if (next < buttons.length) {
    buttons[next].focus();
  }
}

searchInput.addEventListener("input", () => {
  updateSearchChrome();
  clearTimeout(searchTimer);
  searchTimer = setTimeout(() => handleSearch(searchInput.value), 300);
});

searchInput.addEventListener("keydown", (event) => {
  if (event.key === "Escape") {
    event.preventDefault();
    if (searchInput.value) clearSearch();
    else searchInput.blur();
  } else if (event.key === "Enter") {
    event.preventDefault();
    const trimmed = searchInput.value.trim();
    if (trimmed.length < MIN_QUERY_LENGTH) return;
    const resultsAreCurrent = trimmed === searchQuery && document.getElementById("searchSection").getAttribute("aria-busy") !== "true";
    if (resultsAreCurrent) {
      openFirstResult();
    } else {
      clearTimeout(searchTimer);
      openFirstWhenReady = true;
      handleSearch(trimmed);
    }
  } else if (event.key === "ArrowDown") {
    event.preventDefault();
    focusResult(1);
  }
});

document.addEventListener("keydown", (event) => {
  if (event.key !== "/" || event.ctrlKey || event.metaKey || event.altKey) return;
  const target = event.target;
  const isTyping = target instanceof HTMLElement && (target.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(target.tagName));
  if (isTyping || document.getElementById("profileDialog")?.open) return;
  event.preventDefault();
  searchInput.focus();
  searchInput.select();
});

document.getElementById("searchClear").addEventListener("click", clearSearch);

document.getElementById("searchResults").addEventListener("keydown", (event) => {
  if (!event.target.closest(".result-main")) return;
  if (event.key === "ArrowDown") {
    event.preventDefault();
    focusResult(1);
  } else if (event.key === "ArrowUp") {
    event.preventDefault();
    focusResult(-1);
  }
});

document.getElementById("searchSection").addEventListener("click", (event) => {
  const filter = event.target.closest("[data-filter]");
  if (filter) {
    functionFilter = filter.dataset.filter;
    renderSearchResults();
    return;
  }

  if (event.target.closest("[data-clear-search]")) {
    clearSearch();
    return;
  }

  const result = event.target.closest("[data-result-index]");
  if (result) {
    openEmployeeProfile(searchResults[Number(result.dataset.resultIndex)]);
  }
});

window.refreshOrgChart = async () => {
  await loadDirectoryData();
};

searchInput.value = new URLSearchParams(window.location.search).get("q") || "";
updateSearchChrome();
loadDirectoryData();
logPageVisit("Main");
