// ==UserScript==
// @name        Google Ads WebView Blocker
// @match       *://*/*
// @run-at      document-end
// @version     1.4
// ==/UserScript==

function removeAds() {
  // Google Ad Manager containers
  document.querySelectorAll('[data-google-query-id]').forEach(function(el) {
    collapseWithParent(el);
  });
  // Generic ad containers: div[ad-type] (AccuWeather etc.), div.ad
  document.querySelectorAll('div[ad-type], div.ad').forEach(function(el) {
    el.style.cssText = 'display:none!important;height:0!important;overflow:hidden!important';
  });
  // Ad iframes
  document.querySelectorAll('iframe').forEach(function(f) {
    var src = f.src || '';
    if (/doubleclick|googlesyndication|googleads|adservice\.google/.test(src)) {
      collapseWithParent(f);
    }
  });
}
removeAds();

// MutationObserver: re-run on every DOM change (SPA re-renders)
new MutationObserver(function() {
  removeAds();
}).observe(document.body || document.documentElement, {childList: true, subtree: true});

// Intercept fetch ad requests
(function() {
  var origFetch = window.fetch;
  if (origFetch) {
    window.fetch = function(url) {
      if (typeof url === 'string' && /doubleclick|googlesyndication|adservice\.google/.test(url))
        return Promise.resolve(new Response('', {status: 200}));
      return origFetch.apply(this, arguments);
    };
  }
})();

function collapseWithParent(el) {
  var parent = el.parentElement;
  el.remove();
  if (parent && parent.children.length === 0) {
    parent.style.cssText = 'display:none!important;height:0!important';
  }
}
