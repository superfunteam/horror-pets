// Point every download button at the newest release's APK, and show its version and size. The page ships with the
// current release's link, so it still works when GitHub's API is unreachable or rate-limited.
(function () {
  var host = location.host;
  if (host) document.querySelectorAll("[data-host]").forEach(function (el) { el.textContent = host; });

  fetch("https://api.github.com/repos/superfunteam/horror-pets/releases/latest", { headers: { Accept: "application/vnd.github+json" } })
    .then(function (r) { return r.ok ? r.json() : Promise.reject(r.status); })
    .then(function (release) {
      var apk = (release.assets || []).filter(function (a) { return /\.apk$/.test(a.name); })[0];
      if (!apk) return;
      document.querySelectorAll("[data-apk]").forEach(function (a) { a.href = apk.browser_download_url; });
      var version = String(release.tag_name || "").replace(/^v/, "");
      var mb = Math.round(apk.size / 1048576);
      document.querySelectorAll("[data-release]").forEach(function (el) { el.textContent = "Version " + version + ", " + mb + " MB"; });
    })
    .catch(function () {});

  // The cast leans with the pointer, the farther ones less, so the hero has some depth. Never with reduced motion,
  // and not on touch screens, where there is no pointer to follow.
  var cast = document.querySelector(".cast");
  var still = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  var fine = window.matchMedia("(pointer: fine)").matches;
  if (!cast || still || !fine) return;
  var frame = 0;
  window.addEventListener("pointermove", function (e) {
    if (frame) return;
    frame = requestAnimationFrame(function () {
      frame = 0;
      var x = e.clientX / window.innerWidth - 0.5;
      var y = e.clientY / window.innerHeight - 0.5;
      cast.style.setProperty("--px", x.toFixed(3));
      cast.style.setProperty("--py", y.toFixed(3));
    });
  });
})();
