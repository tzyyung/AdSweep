// ==UserScript==
// @name        Google Ads CSS Hider
// @match       *://*/*
// @run-at      document-start
// @version     1.0
// ==/UserScript==

// Inject CSS immediately before page renders — prevents ad flicker
GM_addStyle([
  '[data-google-query-id] { display:none !important; height:0 !important; overflow:hidden !important }',
  'iframe[id*="google_ads"] { display:none !important }',
  'iframe[src*="doubleclick"] { display:none !important }',
  'iframe[src*="googlesyndication"] { display:none !important }',
  'div[class*="adsbygoogle"] { display:none !important }',
  'div[class*="ad-container"] { display:none !important }',
  'div[class*="ad-slot"] { display:none !important }',
  'div[class*="ad-wrapper"] { display:none !important }',
  'div[class*="native-ad"] { display:none !important }',
  'a[href*="remove-ads"] { display:none !important }',
  'div[class*="remove-ads"] { display:none !important }',
].join('\n'));
