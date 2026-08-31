const FAVICON_BASE_HREF = "/favicon.svg?v=1.0";

function recreateFaviconLink() {
  const href = `${FAVICON_BASE_HREF}&nav=${Date.now()}`;

  document
    .querySelectorAll('link[rel="icon"], link[rel="shortcut icon"]')
    .forEach((link) => link.remove());

  [
    { rel: "icon", type: "image/svg+xml" },
    { rel: "shortcut icon" }
  ].forEach(({ rel, type }) => {
    const link = document.createElement("link");
    link.rel = rel;
    if (type) {
      link.type = type;
    }
    link.href = href;
    document.head.appendChild(link);
  });
}

function isPageNavigationLink(anchor) {
  if (!anchor || !anchor.href) return false;
  if (anchor.target && anchor.target !== "_self") return false;
  if (anchor.hasAttribute("download")) return false;

  const url = new URL(anchor.href, window.location.origin);
  if (url.origin !== window.location.origin) return false;

  return url.pathname === "/" || url.pathname.endsWith(".html");
}

document.addEventListener("DOMContentLoaded", recreateFaviconLink);
window.addEventListener("pageshow", recreateFaviconLink);

document.addEventListener("click", (event) => {
  const anchor = event.target.closest("a[href]");
  if (!isPageNavigationLink(anchor)) return;

  recreateFaviconLink();
});

window.recreateFaviconLink = recreateFaviconLink;
