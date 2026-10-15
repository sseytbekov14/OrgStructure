let orgEventSource = null;
let orgToastTimer = null;

function ensureOrgToastHost() {
  let host = document.getElementById("orgToastHost");
  if (host) return host;

  host = document.createElement("div");
  host.id = "orgToastHost";
  host.className = "org-toast-host";
  host.innerHTML = '<div class="org-toast" id="orgToast">Данные обновлены</div>';
  document.body.appendChild(host);
  return host;
}

function showOrgToast(message = "Данные обновлены") {
  const host = ensureOrgToastHost();
  const toast = host.querySelector(".org-toast");
  if (!toast) return;

  toast.textContent = message;
  toast.classList.add("is-visible");

  clearTimeout(orgToastTimer);
  orgToastTimer = setTimeout(() => {
    toast.classList.remove("is-visible");
  }, 2600);
}

async function handleOrgStreamRefresh(payload) {
  if (typeof window.refreshOrgChart !== "function") {
    return;
  }

  await window.refreshOrgChart(payload);
  showOrgToast("Данные обновлены");
}

function connectOrgStream() {
  if (!window.EventSource || orgEventSource) {
    return;
  }

  orgEventSource = new EventSource("/api/org/stream");

  orgEventSource.addEventListener("org-updated", async (event) => {
    try {
      const payload = JSON.parse(event.data);
      await handleOrgStreamRefresh(payload);
    } catch (error) {
      console.error("Failed to process org update event", error);
    }
  });

  orgEventSource.onerror = () => {
    if (orgEventSource?.readyState !== EventSource.CLOSED) {
      console.warn("SSE connection interrupted, waiting for reconnect");
      return;
    }
    // The server refused the stream (401 when the session ended): EventSource does not say why and does not
    // retry, so ask the API - apiFetch reloads the page on 401 - and otherwise reconnect a little later.
    orgEventSource.close();
    orgEventSource = null;
    apiFetch("/api/functions")
      .then((res) => { if (res.status !== 401) setTimeout(connectOrgStream, 5000); })
      .catch(() => setTimeout(connectOrgStream, 5000));
  };

  window.addEventListener("beforeunload", () => {
    orgEventSource?.close();
    orgEventSource = null;
  }, { once: true });
}

document.addEventListener("DOMContentLoaded", connectOrgStream);
