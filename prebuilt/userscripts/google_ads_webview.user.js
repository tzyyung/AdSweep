// ==UserScript==
// @name        Google Ads WebView Blocker
// @match       *://*/*
// @run-at      document-end
// @version     1.1
// ==/UserScript==

// Phase 1: CSS — hide known ad containers immediately
GM_addStyle([
  // Google Ad Manager / DFP
  '[data-google-query-id] { display:none !important; height:0 !important; overflow:hidden !important }',
  'iframe[id*="google_ads"] { display:none !important }',
  'iframe[src*="doubleclick"] { display:none !important }',
  'iframe[src*="googlesyndication"] { display:none !important }',
  'div[class*="adsbygoogle"] { display:none !important }',
  // Common ad containers
  'div[class*="ad-container"] { display:none !important }',
  'div[class*="ad-slot"] { display:none !important }',
  'div[class*="ad-wrapper"] { display:none !important }',
  'div[class*="native-ad"] { display:none !important }',
  // "Remove Ads" links
  'a[href*="remove-ads"] { display:none !important }',
  'div[class*="remove-ads"] { display:none !important }',
].join('\n'));

// Phase 2: Immediately remove existing ad elements + their parent containers
function removeAds() {
  // Remove elements with Google ad attributes
  document.querySelectorAll('[data-google-query-id]').forEach(function(el) {
    // Also hide the parent container to collapse the empty space
    var parent = el.parentElement;
    el.remove();
    if (parent && parent.children.length === 0) {
      parent.style.display = 'none';
    }
  });

  // Remove ad iframes
  document.querySelectorAll('iframe').forEach(function(f) {
    var src = f.src || '';
    if (/doubleclick|googlesyndication|googleads|adservice\.google/.test(src)) {
      var parent = f.parentElement;
      f.remove();
      if (parent && parent.children.length === 0) {
        parent.style.display = 'none';
      }
    }
  });
}

removeAds();

// Phase 3: MutationObserver for dynamically loaded ads
new MutationObserver(function(mutations) {
  var needsClean = false;
  mutations.forEach(function(m) {
    m.addedNodes.forEach(function(n) {
      if (n.nodeType === 1) {
        if (n.getAttribute && n.getAttribute('data-google-query-id')) {
          needsClean = true;
        }
        if (n.tagName === 'IFRAME') {
          var src = n.src || '';
          if (/doubleclick|googlesyndication|googleads/.test(src)) {
            needsClean = true;
          }
        }
      }
    });
  });
  if (needsClean) removeAds();
}).observe(document.body || document.documentElement, {childList: true, subtree: true});

// Phase 4: Intercept fetch ad requests
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
