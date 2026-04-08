// ==UserScript==
// @name        Google Ads WebView Blocker
// @match       *://*/*
// @run-at      document-end
// @version     1.2
// ==/UserScript==

// Phase 1: Remove existing ad elements + collapse parent containers
function removeAds() {
  document.querySelectorAll('[data-google-query-id]').forEach(function(el) {
    var parent = el.parentElement;
    el.remove();
    if (parent && parent.children.length === 0) parent.style.display = 'none';
  });
  document.querySelectorAll('iframe').forEach(function(f) {
    var src = f.src || '';
    if (/doubleclick|googlesyndication|googleads|adservice\.google/.test(src)) {
      var parent = f.parentElement;
      f.remove();
      if (parent && parent.children.length === 0) parent.style.display = 'none';
    }
  });
}
removeAds();

// Phase 2: MutationObserver for dynamically loaded ads
new MutationObserver(function(mutations) {
  var needsClean = false;
  mutations.forEach(function(m) {
    m.addedNodes.forEach(function(n) {
      if (n.nodeType === 1) {
        if (n.getAttribute && n.getAttribute('data-google-query-id')) needsClean = true;
        if (n.tagName === 'IFRAME' && /doubleclick|googlesyndication|googleads/.test(n.src || '')) needsClean = true;
      }
    });
  });
  if (needsClean) removeAds();
}).observe(document.body || document.documentElement, {childList: true, subtree: true});

// Phase 3: Intercept fetch ad requests
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
