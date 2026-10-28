/* Shared UI components (U2), vanilla JS with DOM APIs only (never HTML strings): accessible dialogs, confirm
   dialogs, toasts and collapsible panels, plus the brand logo. Texts are always set with textContent. Styles:
   components.css. The browser's own dialogs are not used anywhere on the site: use confirmDialog() and toast(). */

/** An element with attributes, a text and children; never HTML */
function uiEl(tag, attributes = {}, ...children) {
  const node = document.createElement(tag);
  Object.entries(attributes).forEach(([key, value]) => {
    if (value == null || value === false) return;
    if (key === "class") node.className = value;
    else if (key === "text") node.textContent = value;
    else if (key.startsWith("on") && typeof value === "function") node.addEventListener(key.slice(2), value);
    else node.setAttribute(key, value === true ? "" : value);
  });
  children.flat().filter((child) => child != null && child !== false).forEach((child) => node.append(child));
  return node;
}

let uiIdCounter = 0;
function uiId(prefix) {
  uiIdCounter += 1;
  return `${prefix}-${uiIdCounter}`;
}

const UI_FOCUSABLE = "a[href], button, input, select, textarea, [tabindex]";

/** The elements of root that take keyboard focus, in DOM order */
function uiFocusable(root) {
  return [...root.querySelectorAll(UI_FOCUSABLE)].filter((el) =>
    !el.disabled && el.tabIndex >= 0 && !el.closest("[hidden]") && el.getAttribute("type") !== "hidden");
}

/* ---------- Dialogs ---------- */

const uiDialogStack = [];

/**
 * Opens a modal dialog: role=dialog, aria-modal, labelled by its title, focus trapped inside, ESC and the close
 * button close it, the backdrop closes it unless closeOnBackdrop is false, focus returns to the element that had it.
 *
 * options: title (text), body (node, text or array of them), actions ([{label, variant, value, onClick, autofocus}]:
 * onClick(event, handle) may return false to keep the dialog open), wide, closeOnBackdrop (default true),
 * beforeClose(reason) (may return false or a Promise of false to keep it open: dirty forms), onClose(value),
 * initialFocus (element), describedBy (id), className.
 * Returns {element, body, footer, close(value), closed (Promise of the close value), setBusy(flag)}.
 */
function openDialog(options = {}) {
  const titleId = uiId("ui-dialog-title");
  const returnFocus = document.activeElement;
  let resolveClosed;
  const closed = new Promise((resolve) => { resolveClosed = resolve; });
  let isClosed = false;

  const body = uiEl("div", { class: "ui-dialog-body" });
  [].concat(options.body ?? []).forEach((part) => {
    body.append(typeof part === "string" ? uiEl("p", { text: part }) : part);
  });
  const footer = uiEl("div", { class: "ui-dialog-footer" });
  const closeButton = uiEl("button", { type: "button", class: "ui-dialog-close", "aria-label": "Close", text: "×" });
  const dialog = uiEl("div", {
    class: `ui-dialog${options.wide ? " ui-dialog--wide" : ""}${options.className ? ` ${options.className}` : ""}`,
    role: "dialog", "aria-modal": "true", "aria-labelledby": titleId,
    "aria-describedby": options.describedBy || null, tabindex: "-1"
  },
  uiEl("div", { class: "ui-dialog-header" },
    uiEl("h2", { class: "ui-dialog-title", id: titleId, text: options.title || "" }), closeButton),
  body, footer);
  // A native modal <dialog> as the backdrop: it joins the top layer, so it also opens above another modal
  // dialog (the profile card) and makes the rest of the page inert
  const backdrop = uiEl("dialog", { class: "ui-dialog-backdrop" }, dialog);

  const handle = { element: dialog, body, footer, closed };

  async function requestClose(reason, value = null) {
    if (isClosed) return false;
    if (typeof options.beforeClose === "function") {
      const allowed = await options.beforeClose(reason);
      if (allowed === false) return false;
    }
    close(value);
    return true;
  }

  function close(value = null) {
    if (isClosed) return;
    isClosed = true;
    if (backdrop.open && typeof backdrop.close === "function") backdrop.close();
    backdrop.remove();
    const index = uiDialogStack.indexOf(handle);
    if (index >= 0) uiDialogStack.splice(index, 1);
    if (returnFocus && typeof returnFocus.focus === "function" && document.contains(returnFocus)) returnFocus.focus();
    if (typeof options.onClose === "function") options.onClose(value);
    resolveClosed(value);
  }

  handle.close = close;
  handle.requestClose = requestClose;
  handle.setBusy = (busy) => {
    dialog.setAttribute("aria-busy", busy ? "true" : "false");
    footer.querySelectorAll("button").forEach((button) => { button.disabled = !!busy; });
  };

  (options.actions || []).forEach((action) => {
    const button = uiEl("button", {
      type: "button", class: `btn btn-${action.variant || "secondary"}`, text: action.label,
      "data-value": action.value != null ? String(action.value) : null
    });
    button.addEventListener("click", async (event) => {
      if (typeof action.onClick === "function") {
        const keepOpen = (await action.onClick(event, handle)) === false;
        if (keepOpen) return;
      }
      if (action.close !== false) close(action.value ?? null);
    });
    if (action.autofocus) button.setAttribute("data-autofocus", "");
    footer.append(button);
  });
  if (!footer.childNodes.length) footer.hidden = true;

  closeButton.addEventListener("click", () => requestClose("close"));
  backdrop.addEventListener("click", (event) => {
    if (event.target === backdrop && options.closeOnBackdrop !== false) requestClose("backdrop");
  });
  // The browser's own ESC handling is replaced by requestClose (beforeClose may keep the dialog open)
  backdrop.addEventListener("cancel", (event) => event.preventDefault());
  backdrop.addEventListener("keydown", (event) => {
    if (uiDialogStack[uiDialogStack.length - 1] !== handle) return;
    if (event.key === "Escape") {
      event.preventDefault();
      event.stopPropagation();
      requestClose("escape");
      return;
    }
    if (event.key !== "Tab") return;
    const focusable = uiFocusable(dialog);
    if (!focusable.length) { event.preventDefault(); dialog.focus(); return; }
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    const active = document.activeElement;
    if (event.shiftKey && (active === first || !dialog.contains(active))) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && (active === last || !dialog.contains(active))) {
      event.preventDefault();
      first.focus();
    }
  });

  document.body.append(backdrop);
  if (typeof backdrop.showModal === "function") backdrop.showModal();
  uiDialogStack.push(handle);
  const target = options.initialFocus || dialog.querySelector("[data-autofocus]")
    || uiFocusable(body)[0] || footer.querySelector("button") || dialog;
  target.focus();
  return handle;
}

/**
 * A confirmation dialog. Resolves true when the confirm button is used, false on cancel, ESC, close or backdrop.
 * options: title, message (text or array of paragraphs), confirmLabel (default "OK"), cancelLabel ("Cancel"),
 * danger (red confirm button).
 */
function confirmDialog(options = {}) {
  const handle = openDialog({
    title: options.title || "Are you sure?",
    body: options.message,
    actions: [
      { label: options.cancelLabel || "Cancel", variant: "secondary", value: false },
      { label: options.confirmLabel || "OK", variant: options.danger ? "danger" : "primary", value: true,
        autofocus: !options.danger }
    ],
    className: "ui-confirm"
  });
  if (options.danger) {
    // Destructive: focus starts on Cancel
    handle.footer.querySelector("button").focus();
  }
  return handle.closed.then((value) => value === true);
}

/* ---------- Toasts ---------- */

function uiToastHost() {
  let host = document.querySelector(".ui-toasts");
  if (!host) {
    // A manual popover: in the top layer, so a toast also shows above an open modal dialog
    host = uiEl("div", { class: "ui-toasts", role: "status", "aria-live": "polite", popover: "manual" });
    document.body.append(host);
  }
  return host;
}

/** Brings the toasts above the newest modal dialog (the top layer is ordered by showing time) */
function uiRaiseToasts(host) {
  if (typeof host.showPopover !== "function") return;
  try {
    if (host.matches(":popover-open")) host.hidePopover();
    host.showPopover();
  } catch (e) { /* popover not supported: the toasts stay in the page */ }
}

/**
 * A short message at the bottom right. type: info | success | error (errors use role=alert).
 * options: undo (function: shows an Undo button; when used, the toast closes and undo() runs), actionLabel,
 * duration in ms (default 5000; 8000 with undo). Returns {element, dismiss}.
 */
function toast(message, type = "info", options = {}) {
  const host = uiToastHost();
  const element = uiEl("div", { class: `ui-toast ui-toast--${type}`, role: type === "error" ? "alert" : null },
    uiEl("span", { class: "ui-toast-message", text: String(message ?? "") }));
  let timer = null;
  const dismiss = () => {
    clearTimeout(timer);
    element.remove();
  };
  if (typeof options.undo === "function") {
    element.append(uiEl("button", {
      type: "button", class: "ui-toast-action", text: options.actionLabel || "Undo",
      onclick: () => { dismiss(); options.undo(); }
    }));
  }
  element.append(uiEl("button", { type: "button", class: "ui-toast-close", "aria-label": "Dismiss", text: "×",
    onclick: dismiss }));
  host.append(element);
  uiRaiseToasts(host);
  timer = setTimeout(dismiss, options.duration ?? (options.undo ? 8000 : 5000));
  return { element, dismiss };
}

/* ---------- Collapsible panel ---------- */

/**
 * A panel whose header button toggles the body (aria-expanded / aria-controls). options: title, content (node or
 * array), expanded (default false), storageKey (state kept in sessionStorage), headerExtra (node shown in the
 * header), className, onToggle(expanded). Returns {element, header, body, setExpanded, isExpanded}.
 */
function createCollapsible(options = {}) {
  const bodyId = uiId("ui-collapsible");
  let expanded = !!options.expanded;
  if (options.storageKey) {
    try {
      const stored = sessionStorage.getItem(options.storageKey);
      if (stored === "true" || stored === "false") expanded = stored === "true";
    } catch (e) { /* storage unavailable */ }
  }
  const header = uiEl("button", { type: "button", class: "collapsible-header", "aria-controls": bodyId },
    uiEl("span", { class: "collapsible-title", text: options.title || "" }), options.headerExtra || null);
  const body = uiEl("div", { class: "collapsible-body", id: bodyId }, [].concat(options.content ?? []));
  const element = uiEl("section", { class: `collapsible${options.className ? ` ${options.className}` : ""}` },
    header, body);

  function setExpanded(value, { persist = true } = {}) {
    expanded = !!value;
    header.setAttribute("aria-expanded", expanded ? "true" : "false");
    body.hidden = !expanded;
    element.classList.toggle("is-expanded", expanded);
    if (persist && options.storageKey) {
      try { sessionStorage.setItem(options.storageKey, String(expanded)); } catch (e) { /* storage unavailable */ }
    }
    if (typeof options.onToggle === "function") options.onToggle(expanded);
  }

  header.addEventListener("click", () => setExpanded(!expanded));
  setExpanded(expanded, { persist: false });
  return { element, header, body, setExpanded, isExpanded: () => expanded };
}

/* ---------- Brand logo ---------- */

const BRAND_LOGO_FILES = ["/images/kpmg-logo.svg", "/images/kpmg-logo.png"];

/**
 * Every .brand-logo--placeholder shows the official logo file once the owner has placed it in /images/ (svg, else
 * png). The file is shown as it is (height from CSS, width auto: aspect ratio kept); while it is missing the
 * clearly marked placeholder stays.
 */
function initBrandLogo() {
  const placeholders = [...document.querySelectorAll(".brand-logo--placeholder")];
  if (!placeholders.length || typeof Image !== "function") return;
  const tryFile = (index) => {
    if (index >= BRAND_LOGO_FILES.length) return;
    const probe = new Image();
    probe.onload = () => {
      placeholders.forEach((holder) => {
        holder.classList.remove("brand-logo--placeholder");
        holder.replaceChildren(uiEl("img", { src: BRAND_LOGO_FILES[index], alt: "KPMG" }));
      });
      window.BRAND_FAVICON = BRAND_LOGO_FILES[index];
      if (typeof window.recreateFaviconLink === "function") window.recreateFaviconLink();
    };
    probe.onerror = () => tryFile(index + 1);
    probe.src = BRAND_LOGO_FILES[index];
  };
  tryFile(0);
}

initBrandLogo();
