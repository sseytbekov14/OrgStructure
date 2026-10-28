let orgEventSource = null;
// The shared toast of ui.js (every page that listens to the stream loads it). U7: the old fallback is gone; it
// declared a local "toast" that shadowed ui.js's toast(), so typeof toast threw and no refresh was announced.
function showOrgToast(message = "Data updated") {
  if (typeof toast === "function") toast(message, "info", { duration: 2600 });
}

async function handleOrgStreamRefresh(payload) {
  if (typeof window.refreshOrgChart !== "function") {
    return;
  }

  await window.refreshOrgChart(payload);
  showOrgToast("Data updated");
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
