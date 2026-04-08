// ==UserScript==
// @name        Google Ads CSS Hider
// @match       *://*/*
// @run-at      document-start
// @version     1.0
// ==/UserScript==

// Inject CSS immediately before page renders — prevents ad flicker
GM_addStyle([
  // Google Ad Manager / GPT standard containers
  '[data-google-query-id] { display:none !important; height:0 !important; overflow:hidden !important }',
  'div[id^="div-gpt-ad"] { display:none !important; height:0 !important; overflow:hidden !important }',
  'div[id^="google_ads"] { display:none !important }',
  'iframe[id*="google_ads"] { display:none !important }',
  'iframe[src*="doubleclick"] { display:none !important }',
  'iframe[src*="googlesyndication"] { display:none !important }',
  'div[class*="adsbygoogle"] { display:none !important }',
  // Common ad container patterns
  'div[class*="ad-container"] { display:none !important; height:0 !important }',
  'div[class*="ad-slot"] { display:none !important; height:0 !important }',
  'div[class*="ad-wrapper"] { display:none !important; height:0 !important }',
  'div[class*="ad-unit"] { display:none !important; height:0 !important }',
  'div[class*="native-ad"] { display:none !important }',
  // "Remove Ads" links
  'a[href*="remove-ads"] { display:none !important }',
  'div[class*="remove-ads"] { display:none !important }',
  // GPT ad label / badge
  'div[class*="ad-label"] { display:none !important }',
  'div[class*="advertisement"] { display:none !important }',
].join('\n'));
