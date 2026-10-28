/* The FAQ editor of the function page (U5, admins): "Edit" turns the FAQ panel into a structured editor. Sections
   (guidelines, mailboxes, questions) and their items can be added, removed and reordered; every field has a label,
   a counter and its error (the rules of validation.js, the same as the server's); Preview shows the page as it will
   look; Save / Cancel; leaving with changes asks; a version conflict keeps the typed text. DOM APIs only. */

const FAQ_KIND_LABELS = { GUIDELINES: "Guidelines", MAILBOXES: "Mailboxes", QUESTIONS: "Questions" };
const FAQ_ITEM_FIELDS = {
  GUIDELINES: [{ name: "text", label: "Guideline", max: FAQ_LIMITS.text }],
  MAILBOXES: [{ name: "label", label: "Mailbox name", max: FAQ_LIMITS.label },
    { name: "email", label: "E-mail", max: FAQ_LIMITS.email, help: "Leave empty to show \"Email to be added\"." }],
  QUESTIONS: [{ name: "question", label: "Question", max: FAQ_LIMITS.question },
    { name: "answer", label: "Answer", max: FAQ_LIMITS.answer, multiline: true, help: "Plain text. Links: https:// or mailto: only." }]
};
const FAQ_ADD_LABELS = { GUIDELINES: "Add guideline", MAILBOXES: "Add mailbox", QUESTIONS: "Add question" };

const FaqEditor = { cs: null, draft: null, version: null, saved: "", preview: false, errors: {}, saving: false,
  savedView: null, root: null };

window.onFaqRendered = async (cs, view, card) => {
  if (!(await loadAdminSession())) return;
  if (card.querySelector("[data-faq-edit]")) return;
  const bar = uiEl("div", { class: "faq-admin-bar" },
    uiEl("span", { class: "badge", text: "Admin" }),
    uiEl("button", { type: "button", class: "btn btn-secondary btn-sm", "data-faq-edit": true, text: "Edit FAQ",
      "aria-label": `Edit the FAQ of ${cs}`, onclick: () => openFaqEditor(cs, view) }));
  card.insertBefore(bar, card.querySelector(".faq-body"));
};

function faqClone(value) {
  return JSON.parse(JSON.stringify(value || { sections: [] }));
}

function faqDirty() {
  return JSON.stringify(validateFaqDocument(FaqEditor.draft).document) !== FaqEditor.saved;
}

function openFaqEditor(cs, view) {
  const card = document.getElementById("faqCard");
  if (!card) return;
  FaqState.editing = true;
  Object.assign(FaqEditor, {
    cs, draft: faqClone(view.content), version: view.version, preview: false, errors: {}, saving: false, savedView: null,
    saved: JSON.stringify(validateFaqDocument(view.content).document)
  });
  card.classList.remove("is-collapsed");
  card.querySelector(".faq-toggle")?.setAttribute("aria-expanded", "true");
  const body = card.querySelector(".faq-body-inner");
  FaqEditor.root = uiEl("div", { class: "faq-editor", role: "region", "aria-label": `FAQ editor of ${cs}` });
  body.replaceChildren(FaqEditor.root);
  card.querySelector("[data-faq-edit]")?.setAttribute("disabled", "");
  renderFaqEditor();
  FaqEditor.root.querySelector("input, textarea")?.focus();
  window.addEventListener("beforeunload", faqBeforeUnload);
}

function faqBeforeUnload(event) {
  if (FaqState.editing && faqDirty()) { event.preventDefault(); event.returnValue = ""; }
}

/** Leaves the editor (after asking when there are changes) and shows the FAQ as saved */
async function closeFaqEditor(force = false) {
  if (!force && faqDirty()) {
    const discard = await confirmDialog({ title: "Discard your changes?", message: "Your changes to the FAQ are not saved.",
      confirmLabel: "Discard", danger: true });
    if (!discard) return false;
  }
  FaqState.editing = false;
  window.removeEventListener("beforeunload", faqBeforeUnload);
  await renderFaqBanner(FaqEditor.cs, FaqState.view);
  document.querySelector("[data-faq-edit]")?.focus();
  return true;
}

/* ---------- Rendering ---------- */

function faqFieldId(path) {
  return `faq-${path.replace(/[^a-zA-Z0-9]+/g, "-")}`;
}

/** One labelled field bound to the draft (typing updates the draft and the checks, without re-rendering) */
function faqField(path, field, value, onInput) {
  const id = faqFieldId(path);
  const input = field.multiline
    ? uiEl("textarea", { class: "textarea", id, rows: 3, "data-path": path })
    : uiEl("input", { class: "input", id, type: field.name === "email" ? "email" : "text", autocomplete: "off", "data-path": path });
  input.value = value || "";
  const counter = uiEl("span", { class: "field-counter", "aria-hidden": "true" });
  const error = uiEl("p", { class: "field-error", id: `${id}-error`, "aria-live": "polite", "data-error-for": path });
  const help = field.help ? uiEl("p", { class: "field-help", id: `${id}-help`, text: field.help }) : null;
  input.setAttribute("aria-describedby", [help ? `${id}-help` : null, `${id}-error`].filter(Boolean).join(" "));
  const update = () => {
    const length = [...(input.value || "")].length;
    counter.textContent = `${length} / ${field.max}`;
    counter.classList.toggle("is-over", length > field.max);
  };
  input.addEventListener("input", () => { onInput(input.value); update(); faqCheck(); });
  update();
  return uiEl("div", { class: "field" }, uiEl("label", { class: "field-label", for: id, text: field.label }), input,
    uiEl("div", { class: "admin-field-meta" }, help || uiEl("span"), counter), error);
}

function faqMoveButtons(list, index, label, onChange) {
  return [
    uiEl("button", { type: "button", class: "btn btn-ghost btn-sm", text: "↑", "aria-label": `Move ${label} up`,
      disabled: index === 0, onclick: () => { [list[index - 1], list[index]] = [list[index], list[index - 1]]; onChange(); } }),
    uiEl("button", { type: "button", class: "btn btn-ghost btn-sm", text: "↓", "aria-label": `Move ${label} down`,
      disabled: index === list.length - 1, onclick: () => { [list[index + 1], list[index]] = [list[index], list[index + 1]]; onChange(); } })
  ];
}

function renderFaqEditor() {
  const root = FaqEditor.root;
  if (!root) return;
  const sections = FaqEditor.draft.sections;
  const rerender = () => { renderFaqEditor(); faqCheck(); };

  const toolbar = uiEl("div", { class: "faq-editor-toolbar" },
    uiEl("button", { type: "button", class: "btn btn-secondary btn-sm", "aria-pressed": FaqEditor.preview ? "true" : "false",
      text: FaqEditor.preview ? "Back to editing" : "Preview",
      onclick: () => { FaqEditor.preview = !FaqEditor.preview; renderFaqEditor(); } }),
    uiEl("span", { class: "faq-editor-spacer" }),
    uiEl("button", { type: "button", class: "btn btn-secondary btn-sm", text: "Cancel", onclick: () => closeFaqEditor() }),
    uiEl("button", { type: "button", class: "btn btn-primary btn-sm", text: "Save", "data-faq-save": true,
      onclick: () => saveFaq() }));
  const alert = uiEl("div", { class: "admin-form-alert faq-editor-alert", role: "alert", hidden: true });

  if (FaqEditor.preview) {
    root.replaceChildren(toolbar, alert, uiEl("div", { class: "faq-preview", "aria-label": "Preview" },
      renderFaqSections(validateFaqDocument(FaqEditor.draft).document)));
    faqCheck();
    return;
  }

  const blocks = sections.map((section, s) => {
    const path = `sections[${s}]`;
    const kind = String(section.kind || "").toUpperCase();
    const items = section.items || (section.items = []);
    const fields = FAQ_ITEM_FIELDS[kind] || [];
    const itemRows = items.map((item, i) => uiEl("div", { class: "faq-editor-item" },
      uiEl("div", { class: "faq-editor-item-fields" },
        fields.map((field) => faqField(`${path}.items[${i}].${field.name}`, field, item[field.name],
          (value) => { item[field.name] = value; }))),
      uiEl("div", { class: "admin-heads-buttons" },
        faqMoveButtons(items, i, `item ${i + 1}`, rerender),
        uiEl("button", { type: "button", class: "btn btn-ghost btn-sm", text: "✕", "aria-label": `Remove item ${i + 1}`,
          onclick: () => { items.splice(i, 1); rerender(); } }))));
    return uiEl("fieldset", { class: "faq-editor-section", "data-section": String(s) },
      uiEl("legend", { class: "faq-editor-legend" },
        uiEl("span", { class: "badge badge-neutral", text: FAQ_KIND_LABELS[kind] || kind }),
        uiEl("span", { class: "visually-hidden", text: ` section ${s + 1}` })),
      uiEl("div", { class: "faq-editor-section-head" },
        faqField(`${path}.title`, { name: "title", label: "Section title", max: FAQ_LIMITS.title }, section.title,
          (value) => { section.title = value; }),
        uiEl("div", { class: "admin-heads-buttons" },
          faqMoveButtons(sections, s, `section ${section.title || s + 1}`, rerender),
          uiEl("button", { type: "button", class: "btn btn-ghost btn-sm", text: "Remove section",
            onclick: async () => {
              if (items.length && !(await confirmDialog({ title: `Remove the section ${section.title || ""}?`,
                message: `Its ${items.length} item(s) are removed too (only when you save).`, confirmLabel: "Remove", danger: true }))) return;
              sections.splice(s, 1);
              rerender();
            } }))),
      uiEl("p", { class: "field-error", "data-error-for": `${path}.items` }),
      itemRows,
      uiEl("button", { type: "button", class: "btn btn-secondary btn-sm", text: FAQ_ADD_LABELS[kind] || "Add item",
        disabled: items.length >= FAQ_LIMITS.items,
        onclick: () => { items.push({}); rerender(); focusLastItem(s); } }));
  });

  const kindSelect = uiEl("select", { class: "select faq-editor-kind", "aria-label": "Kind of the new section" },
    Object.entries(FAQ_KIND_LABELS).map(([value, label]) => uiEl("option", { value, text: label })));
  const addSection = uiEl("div", { class: "faq-editor-add" }, kindSelect,
    uiEl("button", { type: "button", class: "btn btn-secondary btn-sm", text: "Add section",
      disabled: sections.length >= FAQ_LIMITS.sections,
      onclick: () => { sections.push({ kind: kindSelect.value, title: "", items: [{}] }); rerender(); focusLastSection(); } }));

  root.replaceChildren(toolbar, alert, uiEl("p", { class: "field-error", "data-error-for": "sections" }), ...blocks, addSection);
  // Save stays disabled until something changed (also right after opening)
  faqCheck();
}

function focusLastItem(s) {
  const section = FaqEditor.root.querySelector(`[data-section="${s}"]`);
  const fields = section ? section.querySelectorAll(".faq-editor-item input, .faq-editor-item textarea") : [];
  const last = fields[fields.length - 1];
  if (last) last.focus();
}

function focusLastSection() {
  const sections = FaqEditor.root.querySelectorAll(".faq-editor-section");
  sections[sections.length - 1]?.querySelector("input")?.focus();
}

/* ---------- Checks, save, conflicts ---------- */

/** Errors of the draft, shown only for fields the admin has filled or after a Save attempt */
function faqCheck(all = false) {
  const { errors } = validateFaqDocument(FaqEditor.draft);
  FaqEditor.errors = errors;
  faqShowErrors(all);
  const save = FaqEditor.root?.querySelector("[data-faq-save]");
  if (save) save.disabled = FaqEditor.saving || !faqDirty();
  return errors;
}

function faqShowErrors(all = false) {
  if (!FaqEditor.root) return;
  FaqEditor.root.querySelectorAll("[data-error-for]").forEach((node) => {
    const path = node.getAttribute("data-error-for");
    const input = FaqEditor.root.querySelector(`[data-path="${path}"]`);
    const shown = all || FaqEditor.shownAll || (input && input.value !== "") || !input;
    const problem = FaqEditor.errors[path];
    node.textContent = problem && shown ? problem.message : "";
    if (input) input.setAttribute("aria-invalid", problem && shown ? "true" : "false");
  });
}

function faqAlert(message, action) {
  const alert = FaqEditor.root.querySelector(".faq-editor-alert");
  alert.replaceChildren(uiEl("p", { text: message }), action || "");
  alert.hidden = false;
}

async function saveFaq() {
  if (FaqEditor.saving) return;
  if (!faqDirty()) { toast("No changes to save.", "info"); return; }
  FaqEditor.shownAll = true;
  const errors = faqCheck(true);
  if (Object.keys(errors).length) {
    if (FaqEditor.preview) { FaqEditor.preview = false; renderFaqEditor(); faqCheck(true); }
    faqAlert(`Please correct ${Object.keys(errors).length} field(s).`);
    FaqEditor.root.querySelector("[aria-invalid=true]")?.focus();
    return;
  }
  FaqEditor.saving = true;
  const save = FaqEditor.root.querySelector("[data-faq-save]");
  save.disabled = true;
  save.setAttribute("aria-busy", "true");
  const res = await adminJson(`/api/manage/functions/${encodeURIComponent(FaqEditor.cs)}/faq`, "PUT",
    { content: validateFaqDocument(FaqEditor.draft).document, version: FaqEditor.version });
  FaqEditor.saving = false;
  save.removeAttribute("aria-busy");
  if (res.ok) {
    FaqState.view = await res.json();
    toast(`FAQ of ${FaqEditor.cs} saved`, "success");
    await closeFaqEditor(true);
    return;
  }
  const refusal = await adminRefusal(res);
  if (res.status === 400 && refusal.errors) {
    FaqEditor.errors = Object.fromEntries(Object.entries(refusal.errors));
    faqShowErrors(true);
    faqAlert(`Please correct ${Object.keys(refusal.errors).length} field(s).`);
  } else if (res.status === 409 && refusal.code === "CHANGED") {
    faqAlert("Changed by someone else. Reload the saved version; your text stays in the editor.",
      uiEl("button", { type: "button", class: "btn btn-secondary btn-sm", text: "Reload values", onclick: reloadFaq }));
  } else {
    faqAlert(refusal.message);
  }
  save.disabled = !faqDirty();
}

/** 409: the saved version is shown below the alert and its version used for the next save; the draft stays */
async function reloadFaq() {
  const view = await loadFaq(FaqEditor.cs);
  if (!view) { faqAlert("The FAQ could not be loaded."); return; }
  FaqEditor.version = view.version;
  FaqEditor.savedView = view;
  FaqEditor.saved = JSON.stringify(validateFaqDocument(view.content).document);
  FaqState.view = view;
  const alert = FaqEditor.root.querySelector(".faq-editor-alert");
  alert.replaceChildren(uiEl("p", { text: "The saved version (below) is loaded. Your text is kept: review it and save again." }),
    uiEl("div", { class: "faq-preview faq-saved-version", "aria-label": "Saved version" }, renderFaqSections(view.content)));
  alert.hidden = false;
  const save = FaqEditor.root.querySelector("[data-faq-save]");
  if (save) save.disabled = !faqDirty();
}

// Loaded after the FAQ may already have been rendered
if (typeof FaqState !== "undefined" && FaqState.view) {
  const card = document.getElementById("faqCard");
  if (card) window.onFaqRendered(FaqState.cs, FaqState.view, card);
}
