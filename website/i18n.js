/* LibreLookAI — tiny i18n engine.
   Reads data-i18n keys, swaps in translations[lang], persists the choice,
   and auto-detects the visitor's language on first visit. */
(function () {
  "use strict";

  var SUPPORTED = ["en", "de", "fr", "it", "es", "pt", "nl", "tr", "ru", "ja", "ko", "zh"];
  var STORE_KEY = "lll_lang";

  function pickInitial() {
    var saved = null;
    try { saved = localStorage.getItem(STORE_KEY); } catch (e) {}
    if (saved && SUPPORTED.indexOf(saved) !== -1) return saved;

    var cands = (navigator.languages && navigator.languages.length)
      ? navigator.languages : [navigator.language || "en"];
    for (var i = 0; i < cands.length; i++) {
      var base = String(cands[i] || "").toLowerCase().split("-")[0];
      if (base === "pt") return "pt";            // map any Portuguese to pt-BR copy
      if (SUPPORTED.indexOf(base) !== -1) return base;
    }
    return "en";
  }

  function apply(lang) {
    var dict = (window.translations && window.translations[lang]) || window.translations.en;
    var fallback = window.translations.en;

    document.querySelectorAll("[data-i18n]").forEach(function (el) {
      var key = el.getAttribute("data-i18n");
      var val = (dict && dict[key] != null) ? dict[key] : fallback[key];
      if (val == null) return;
      var attr = el.getAttribute("data-i18n-attr");
      if (attr) {
        el.setAttribute(attr, val);
      } else {
        el.innerHTML = val; // values may contain trusted inline markup (<em>, <br>, <strong>, <small>, <span>)
      }
    });

    document.documentElement.setAttribute("lang", lang);
    try { localStorage.setItem(STORE_KEY, lang); } catch (e) {}

    var sel = document.getElementById("langSelect");
    if (sel && sel.value !== lang) sel.value = lang;
  }

  function init() {
    var lang = pickInitial();
    apply(lang);
    var sel = document.getElementById("langSelect");
    if (sel) sel.addEventListener("change", function () { apply(sel.value); });
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
