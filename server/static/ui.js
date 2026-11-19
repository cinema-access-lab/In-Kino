// SPDX-License-Identifier: Apache-2.0
"use strict";
const byId = (id) => document.getElementById(id);
let draft = null;
const mode = () => document.querySelector('input[name="mode"]:checked').value;
const status = (message) => {
  byId("status").textContent = message;
};
function changeMode() {
  const local = mode() === "local";
  byId("localInput").hidden = !local;
  byId("onlineInput").hidden = local;
}
document
  .querySelectorAll('input[name="mode"]')
  .forEach((el) => el.addEventListener("change", changeMode));
function bearer() {
  return { Authorization: "Bearer " + byId("token").value.trim() };
}
async function parse(response) {
  const json = await response.json();
  if (!response.ok)
    throw new Error(
      typeof json.detail === "string" ? json.detail : JSON.stringify(json.detail || json),
    );
  return json;
}
byId("publishForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  draft = null;
  byId("publish").disabled = true;
  byId("outcome").hidden = true;
  if (!byId("token").value.trim()) return status("Enter your publishing token first.");
  if (!byId("title").value.trim() || !byId("version").value.trim())
    return status("Film title and presentation label are required.");
  if (!byId("ad").files.length && !byId("sdh").files.length)
    return status("Choose AD or SDH, or both.");
  if (mode() === "local" && !byId("fp").files.length) return status("Choose a .csref fingerprint.");
  if (mode() === "online" && !byId("reference").files.length)
    return status("Choose an original reference WAV.");
  const data = new FormData(e.target);
  data.set("mode", mode());
  const chosen = mode() === "local" ? byId("fp").files[0] : byId("reference").files[0];
  data.delete(mode() === "local" ? "reference_audio" : "fingerprint");
  if (!byId("ad").files.length) data.delete("ad");
  if (!byId("sdh").files.length) data.delete("sdh");
  if (!byId("existing").value.trim()) data.delete("film_id");
  byId("upload").disabled = true;
  status(
    "Uploading and validating " +
      chosen.name +
      ". Keep this page open. Long films may take some time to fingerprint.",
  );
  try {
    const result = await parse(
      await fetch("/api/admin/ingestions", { method: "POST", headers: bearer(), body: data }),
    );
    draft = result.version_id;
    byId("filmId").textContent = result.film_id;
    byId("versionId").textContent = result.version_id;
    byId("duration").textContent = result.duration_seconds.toFixed(2) + " seconds";
    byId("outcome").hidden = false;
    byId("publish").disabled = false;
    status(
      "Draft created and validated. Reference soundtrack persisted: NO. Review identifiers before publishing.",
    );
  } catch (err) {
    status("Upload failed: " + err.message);
  } finally {
    byId("upload").disabled = false;
  }
});
byId("publish").addEventListener("click", async () => {
  if (!draft) return;
  byId("publish").disabled = true;
  status("Publishing…");
  try {
    await parse(
      await fetch("/api/admin/versions/" + encodeURIComponent(draft) + "/publish", {
        method: "POST",
        headers: bearer(),
      }),
    );
    status("Published successfully. Visible to Android catalogue clients.");
    await refresh();
  } catch (err) {
    status("Publish failed: " + err.message);
    byId("publish").disabled = false;
  }
});
async function refresh() {
  const box = byId("catalog");
  box.textContent = "Loading catalogue…";
  try {
    const payload = await parse(await fetch("/api/catalog"));
    box.replaceChildren();
    if (!payload.films.length) {
      box.textContent = "No published films yet.";
      return;
    }
    payload.films.forEach((film) => {
      const section = document.createElement("section");
      section.className = "film";
      const h = document.createElement("h3");
      h.textContent = film.title;
      section.append(h);
      film.versions.forEach((v) => {
        const p = document.createElement("p");
        p.textContent =
          v.label +
          " · " +
          v.fps +
          " fps · " +
          v.assets.map((a) => a.kind.toUpperCase() + " " + a.language).join(" / ");
        section.append(p);
      });
      box.append(section);
    });
  } catch (err) {
    box.textContent = "Catalogue unavailable: " + err.message;
  }
}
byId("refresh").addEventListener("click", refresh);
refresh();
changeMode();
