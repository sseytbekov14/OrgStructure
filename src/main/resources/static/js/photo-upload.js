/* Change photo: every person their own photo, admins anybody's (EMPLOYEE_SOURCE=db). A camera button sits on the avatar
   of the profile card; the dialog previews the photo in the circle the site shows it in. The browser turns the photo
   upright (EXIF) and scales it down before sending; the server checks and re-encodes it anyway. Published at once.
   DOM APIs only (no HTML strings). Needs ui.js and common.js. */

// The longest side sent to the server, in px (it stores at most 320 px; the rest is room for its own scaling)
const PHOTO_UPLOAD_SIDE = 640;
// The chosen file before scaling: camera photos of phones are a few MB
const PHOTO_UPLOAD_MAX_FILE = 20 * 1024 * 1024;
const PHOTO_UPLOAD_TYPES = ["image/jpeg", "image/png"];
const PHOTO_UPLOAD_ERRORS = {
  INVALID_PHOTO: "This file cannot be used as a photo. Choose a JPEG or PNG photo.",
  TOO_LARGE: "The photo is too large. Choose a smaller one.",
  NOT_AN_EMPLOYEE: "Your sign-in is not one of the people on this site.",
  PHOTOS_DISABLED: "Photos cannot be changed at the moment.",
  NO_UPLOADED_PHOTO: "There is no uploaded photo to remove.",
  NOT_FOUND: "This person is no longer on the site."
};

/** A stroked SVG icon from path data */
function photoUploadIcon(paths, size = 16) {
  const ns = "http://www.w3.org/2000/svg";
  const svg = document.createElementNS(ns, "svg");
  [["viewBox", "0 0 24 24"], ["width", size], ["height", size], ["fill", "none"], ["stroke", "currentColor"],
    ["stroke-width", "2"], ["stroke-linecap", "round"], ["stroke-linejoin", "round"], ["aria-hidden", "true"]]
    .forEach(([key, value]) => svg.setAttribute(key, String(value)));
  paths.forEach((d) => {
    const path = document.createElementNS(ns, "path");
    path.setAttribute("d", d);
    svg.append(path);
  });
  return svg;
}

const PHOTO_UPLOAD_CAMERA = ["M14.5 4h-5L7 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3z",
  "M12 16a3 3 0 1 0 0-6 3 3 0 0 0 0 6z"];

/** The URL of the photo of this person: their own, or an admin's call for anybody */
function photoUploadUrl(employee, own) {
  return own ? "/api/me/photo" : `/api/manage/employees/${encodeURIComponent(employee.id)}/photo`;
}

/** The text of a refused request (the codes of the server, else its message) */
async function photoUploadRefusal(res) {
  let body = null;
  try { body = await res.json(); } catch (e) { /* no JSON body */ }
  if (body && PHOTO_UPLOAD_ERRORS[body.code]) return PHOTO_UPLOAD_ERRORS[body.code];
  if (res.status === 403) return "You are not allowed to change this photo.";
  if (res.status === 415) return PHOTO_UPLOAD_ERRORS.INVALID_PHOTO;
  return (body && body.message) || "The photo could not be saved. Try again.";
}

/**
 * The chosen file upright (EXIF), on white, at most PHOTO_UPLOAD_SIDE px: {blob} to send and {preview}, the same JPEG
 * as a data: URL (the Content-Security-Policy allows data: images, not blob:)
 */
async function photoUploadScale(file) {
  const bitmap = await createImageBitmap(file, { imageOrientation: "from-image" });
  try {
    const scale = Math.min(1, PHOTO_UPLOAD_SIDE / Math.max(bitmap.width, bitmap.height));
    const width = Math.max(1, Math.round(bitmap.width * scale));
    const height = Math.max(1, Math.round(bitmap.height * scale));
    const canvas = document.createElement("canvas");
    canvas.width = width;
    canvas.height = height;
    const context = canvas.getContext("2d");
    context.fillStyle = "#ffffff";
    context.fillRect(0, 0, width, height);
    context.imageSmoothingQuality = "high";
    context.drawImage(bitmap, 0, 0, width, height);
    const blob = await new Promise((resolve, reject) => {
      canvas.toBlob((result) => (result ? resolve(result) : reject(new Error("not encoded"))), "image/jpeg", 0.9);
    });
    return { blob, preview: canvas.toDataURL("image/jpeg", 0.9) };
  } finally {
    if (typeof bitmap.close === "function") bitmap.close();
  }
}

/** The avatar of the open card shows this photo URL, or the initials when url is null */
function photoUploadShowOnCard(employee, url) {
  if (typeof profileEmployee === "undefined" || profileEmployee !== employee) return;
  const avatar = document.querySelector("#profileBody .profile-avatar-wrap .avatar");
  if (!avatar) return;
  if (url) {
    avatar.replaceChildren(uiEl("img", { src: url, alt: "", width: AVATAR_SIZES.xl, height: AVATAR_SIZES.xl }));
  } else {
    avatar.replaceChildren(document.createTextNode(getInitials(employee.name || "")));
  }
}

/** The preview circle: the photo, else the initials */
function photoUploadPreview(holder, url, name) {
  holder.replaceChildren(url ? uiEl("img", { src: url, alt: "" }) : document.createTextNode(getInitials(name || "")));
}

/** The Change photo dialog of a person (own: the signed-in person's own card) */
function openPhotoUploadDialog(employee, own) {
  const name = employee.name || "this person";
  const current = employee.photoUrl && employee.photoUrl !== DEFAULT_PHOTO ? employee.photoUrl : null;
  const preview = uiEl("div", { class: "photo-upload-preview", role: "img",
    "aria-label": own ? "Preview of your photo" : `Preview of the photo of ${name}` });
  photoUploadPreview(preview, current, employee.name);
  const errorId = uiId("photoUploadError");
  const error = uiEl("p", { class: "field-error", id: errorId, "aria-live": "polite" });
  const input = uiEl("input", { type: "file", accept: PHOTO_UPLOAD_TYPES.join(","), class: "visually-hidden",
    tabindex: "-1", "aria-hidden": "true" });
  const choose = uiEl("button", { type: "button", class: "btn btn-secondary", "aria-describedby": errorId },
    photoUploadIcon(PHOTO_UPLOAD_CAMERA, 16), uiEl("span", { text: current ? "Choose another photo" : "Choose a photo" }));
  choose.addEventListener("click", () => input.click());

  let blob = null;

  const actions = [];
  if (employee.photoUploaded) {
    actions.push({ label: "Remove photo", variant: "danger", onClick: async (event, handle) => {
      const sure = await confirmDialog({
        title: own ? "Remove your photo?" : `Remove the photo of ${name}?`,
        message: "The photo from Active Directory (or the initials) is shown again.",
        confirmLabel: "Remove", danger: true });
      if (!sure) return false;
      handle.setBusy(true);
      const res = await apiFetch(photoUploadUrl(employee, own), { method: "DELETE" });
      handle.setBusy(false);
      if (!res.ok) {
        error.textContent = await photoUploadRefusal(res);
        return false;
      }
      employee.photoUploaded = false;
      employee.photoUrl = DEFAULT_PHOTO;
      photoUploadShowOnCard(employee, null);
      toast(own ? "Your photo is removed." : `The photo of ${name} is removed.`, "success");
      return true;
    } });
  }
  actions.push({ label: "Cancel", variant: "secondary" });
  actions.push({ label: "Save", variant: "primary", onClick: async (event, handle) => {
    if (!blob) {
      error.textContent = "Choose a photo first.";
      return false;
    }
    handle.setBusy(true);
    let res;
    try {
      res = await apiFetch(photoUploadUrl(employee, own), {
        method: "PUT", headers: { "Content-Type": "image/jpeg" }, body: blob });
    } catch (e) {
      handle.setBusy(false);
      error.textContent = "The photo could not be saved. Check the connection and try again.";
      return false;
    }
    handle.setBusy(false);
    if (!res.ok) {
      error.textContent = await photoUploadRefusal(res);
      return false;
    }
    const saved = await res.json();
    employee.photoUploaded = true;
    employee.photoUrl = saved.photoUrl;
    photoUploadShowOnCard(employee, saved.photoUrl);
    toast(own ? "Your photo is updated." : `The photo of ${name} is updated.`, "success");
    return true;
  } });

  const handle = openDialog({
    title: own ? "Your photo" : `Photo of ${name}`,
    body: uiEl("div", { class: "photo-upload" },
      preview,
      uiEl("p", { class: "photo-upload-hint",
        text: "JPEG or PNG. It is shown in a circle on the site: keep the face in the middle." }),
      choose, input, error),
    actions,
    className: "photo-upload-dialog"
  });
  const save = [...handle.footer.querySelectorAll("button")].find((b) => b.textContent === "Save");
  if (save) save.disabled = true;

  input.addEventListener("change", async () => {
    const file = input.files && input.files[0];
    input.value = "";
    if (!file) return;
    error.textContent = "";
    if (!PHOTO_UPLOAD_TYPES.includes(file.type)) {
      error.textContent = "Choose a JPEG or PNG photo.";
      return;
    }
    if (file.size > PHOTO_UPLOAD_MAX_FILE) {
      error.textContent = "The file is larger than 20 MB. Choose a smaller photo.";
      return;
    }
    let scaled;
    try {
      scaled = await photoUploadScale(file);
    } catch (e) {
      blob = null;
      error.textContent = "This file cannot be read as a photo. Choose a JPEG or PNG photo.";
      if (save) save.disabled = true;
      return;
    }
    blob = scaled.blob;
    photoUploadPreview(preview, scaled.preview, employee.name);
    choose.lastElementChild.textContent = "Choose another photo";
    if (save) {
      save.disabled = false;
      save.focus();
    }
  });
  return handle;
}

/**
 * The camera button on the avatar of an open profile card: on the signed-in person's own card, and on every card of
 * the site for admins. Hiding it is cosmetic: the server checks every upload.
 */
async function addPhotoUploadButton(employee, body) {
  if (employee?.id == null || !body) return;
  const me = await loadMe();
  if (!me || !me.photoUpload) return;
  const own = me.employeeId != null && me.employeeId === employee.id;
  if (!own && !me.admin) return;
  // The card may have changed meanwhile (Back, another person)
  if (typeof profileEmployee !== "undefined" && profileEmployee !== employee) return;
  const avatar = body.querySelector(".profile-band .avatar");
  if (!avatar || body.querySelector("[data-photo-upload]")) return;
  const label = own ? "Change your photo" : `Change the photo of ${employee.name || "this person"}`;
  const button = uiEl("button", { type: "button", class: "profile-photo-btn", "data-photo-upload": true,
    "aria-label": label, title: label, onclick: () => openPhotoUploadDialog(employee, own) },
  photoUploadIcon(PHOTO_UPLOAD_CAMERA, 16));
  const wrap = uiEl("span", { class: "profile-avatar-wrap" });
  avatar.replaceWith(wrap);
  wrap.append(avatar, button);
}

window.addPhotoUploadButton = addPhotoUploadButton;
