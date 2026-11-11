package com.mazharansari.axion.browser

/**
 * The JavaScript Axion's own browser runs inside pages (evaluateJavascript). Original code, written for Axion:
 * the general agent-browser pattern — walk the DOM, tag every interactive element with a stable number
 * (data-axion-idx), hand the model the page text plus the numbered elements, then click/type by number.
 * Placeholders (__IDX__, __TEXT_JSON__, __VALUE_JSON__, __SUBMIT__, __DY__) are filled in by BrowserController
 * with JSON-escaped values, never with raw user text.
 */
object BrowserScripts {

    /** Page snapshot: title, URL, readable text and the numbered interactive elements. */
    const val EXTRACT = """
(function(){
function clean(s){ return (s||'').replace(/\s+/g,' ').trim(); }
function visible(el){
  var r = el.getBoundingClientRect();
  if (r.width < 1 || r.height < 1) return false;
  var s = getComputedStyle(el);
  return s.display !== 'none' && s.visibility !== 'hidden';
}
function label(el){
  var t = clean(el.innerText || el.textContent || '');
  if (!t) t = clean(el.getAttribute('aria-label') || el.getAttribute('title') || el.getAttribute('value') || '');
  return t.slice(0, 120);
}
var out = { title: document.title || '', url: location.href, text: '', elements: [] };
var nodes = document.querySelectorAll('a[href], button, input, select, textarea, [role="button"], [role="link"], [role="checkbox"], [role="tab"], [role="menuitem"]');
var idx = 0;
for (var i = 0; i < nodes.length && idx < 150; i++) {
  var el = nodes[i];
  if (!visible(el)) continue;
  var tag = el.tagName.toLowerCase();
  var role = el.getAttribute('role');
  var e = { i: idx, kind: role || tag };
  if (tag === 'a') e.href = el.href || '';
  if (tag === 'input' || tag === 'textarea' || tag === 'select') {
    e.type = (el.getAttribute('type') || 'text').toLowerCase();
    e.name = el.getAttribute('name') || '';
    e.ph = el.getAttribute('placeholder') || '';
    e.val = (e.type === 'password' || e.type === 'hidden') ? '' : (el.value || '').slice(0, 60);
    e.text = label(el) || e.ph || e.name;
  } else {
    e.text = label(el);
  }
  if (!e.text && !e.href) continue;
  el.setAttribute('data-axion-idx', '' + idx);
  out.elements.push(e);
  idx++;
}
try { out.text = clean(document.body ? (document.body.innerText || document.body.textContent || '') : ''); }
catch (err) { out.text = ''; }
return JSON.stringify(out);
})()
"""

    /** Click the element with the given number, or the first clickable whose visible text contains the query. */
    const val CLICK = """
(function(){
var idx = __IDX__;
var query = __TEXT_JSON__;
function clean(s){ return (s||'').replace(/\s+/g,' ').trim().toLowerCase(); }
var el = null;
if (idx >= 0) el = document.querySelector('[data-axion-idx="' + idx + '"]');
if (!el && query) {
  var nodes = document.querySelectorAll('a[href], button, [role="button"], [role="link"], input[type="submit"], input[type="button"]');
  for (var i = 0; i < nodes.length; i++) {
    var t = clean(nodes[i].innerText || nodes[i].textContent || nodes[i].getAttribute('value') || '');
    if (t && t.indexOf(query) >= 0) { el = nodes[i]; break; }
  }
}
if (!el) return JSON.stringify({ ok: false, error: 'element not found — call browser_read again for fresh numbers' });
el.scrollIntoView({ block: 'center' });
try { el.focus(); } catch (e) {}
try {
  el.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
  el.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));
} catch (e) {}
el.click();
return JSON.stringify({ ok: true, text: clean(el.innerText || el.textContent || '').slice(0, 80), url: location.href });
})()
"""

    /** Type into the numbered field the way real sites expect: native value setter + input/change events (React/Vue safe). */
    const val TYPE = """
(function(){
var idx = __IDX__;
var value = __VALUE_JSON__;
var submit = __SUBMIT__;
var el = document.querySelector('[data-axion-idx="' + idx + '"]');
if (!el) return JSON.stringify({ ok: false, error: 'field not found — call browser_read again for fresh numbers' });
el.scrollIntoView({ block: 'center' });
el.focus();
var tag = el.tagName.toLowerCase();
if (tag === 'select') {
  var done = false;
  for (var i = 0; i < el.options.length; i++) {
    if (el.options[i].value === value || el.options[i].text === value) { el.selectedIndex = i; done = true; break; }
  }
  if (!done) return JSON.stringify({ ok: false, error: 'no such option in the dropdown' });
  el.dispatchEvent(new Event('change', { bubbles: true }));
} else if (el.isContentEditable) {
  document.execCommand('selectAll', false, null);
  document.execCommand('insertText', false, value);
  el.dispatchEvent(new Event('input', { bubbles: true }));
} else {
  var proto = tag === 'textarea' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
  var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
  setter.call(el, value);
  el.dispatchEvent(new Event('input', { bubbles: true }));
  el.dispatchEvent(new Event('change', { bubbles: true }));
}
if (submit) {
  var form = el.form;
  if (form) { if (form.requestSubmit) form.requestSubmit(); else form.submit(); }
  else {
    el.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', bubbles: true }));
    el.dispatchEvent(new KeyboardEvent('keypress', { key: 'Enter', code: 'Enter', bubbles: true }));
    el.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', code: 'Enter', bubbles: true }));
  }
}
return JSON.stringify({ ok: true });
})()
"""

    /** Scroll the page; returns the new position so the model knows if there is more below. */
    const val SCROLL = """
(function(){
window.scrollBy(0, __DY__);
return JSON.stringify({ y: Math.round(window.scrollY), max: Math.max(0, document.body.scrollHeight - window.innerHeight) });
})()
"""
}
