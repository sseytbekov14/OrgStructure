// A minimal DOM for the node tests of the page scripts (no browser, no dependencies): elements with attributes,
// children, classList, dataset, textContent, events with bubbling, focus, and querySelector(All) for simple
// selectors (tag, #id, .class, [attr], [attr=value], comma lists and descendant combinators).
// innerHTML is not parsed: setting it stores the string (tests that need it check the string).

class Event {
  constructor(type, init = {}) {
    this.type = type;
    this.bubbles = init.bubbles !== false;
    this.key = init.key;
    this.shiftKey = !!init.shiftKey;
    this.target = null;
    this.currentTarget = null;
    this.defaultPrevented = false;
    this.propagationStopped = false;
  }
  preventDefault() { this.defaultPrevented = true; }
  stopPropagation() { this.propagationStopped = true; }
}

function matchesCompound(el, compound) {
  if (!el || el.nodeType !== 1) return false;
  // Attribute values may be quoted and contain ] (e.g. [data-path="sections[0].title"])
  const re = /([#.]?[\w-]+|\[[\w-]+(?:=(?:"[^"]*"|'[^']*'|[^\]]*))?\]|\*)/g;
  let m;
  while ((m = re.exec(compound))) {
    const part = m[1];
    if (part === '*') continue;
    if (part.startsWith('#')) { if (el.id !== part.slice(1)) return false; }
    else if (part.startsWith('.')) { if (!el.classList.contains(part.slice(1))) return false; }
    else if (part.startsWith('[')) {
      const inner = part.slice(1, -1);
      const eq = inner.indexOf('=');
      if (eq < 0) { if (!el.hasAttribute(inner.trim())) return false; }
      else {
        const name = inner.slice(0, eq).trim();
        const value = inner.slice(eq + 1).trim().replace(/^["']|["']$/g, '');
        if (el.getAttribute(name) !== value) return false;
      }
    } else if (el.tagName.toLowerCase() !== part.toLowerCase()) return false;
  }
  return true;
}

function matchesSelector(el, selector) {
  return selector.split(',').some((single) => {
    const parts = single.trim().split(/\s+/);
    if (!matchesCompound(el, parts[parts.length - 1])) return false;
    let current = el.parentNode;
    for (let i = parts.length - 2; i >= 0; i--) {
      while (current && !matchesCompound(current, parts[i])) current = current.parentNode;
      if (!current) return false;
      current = current.parentNode;
    }
    return true;
  });
}

class Node {
  constructor(document) {
    this.ownerDocument = document;
    this.parentNode = null;
    this.childNodes = [];
    this.listeners = {};
  }
  get children() { return this.childNodes.filter((n) => n.nodeType === 1); }
  get firstElementChild() { return this.children[0] || null; }
  get lastElementChild() { const c = this.children; return c[c.length - 1] || null; }
  get parentElement() { return this.parentNode && this.parentNode.nodeType === 1 ? this.parentNode : null; }
  append(...nodes) {
    nodes.forEach((n) => {
      // Like the browser: anything that is not a node (also null / undefined) becomes text
      const node = n instanceof Node ? n : this.ownerDocument.createTextNode(String(n));
      if (node.parentNode) node.parentNode.removeChild(node);
      node.parentNode = this;
      this.childNodes.push(node);
    });
  }
  appendChild(node) { this.append(node); return node; }
  prepend(...nodes) {
    const existing = this.childNodes.splice(0);
    this.append(...nodes);
    existing.forEach((n) => { n.parentNode = this; this.childNodes.push(n); });
  }
  insertBefore(node, reference) {
    if (!reference) return this.appendChild(node);
    if (node.parentNode) node.parentNode.removeChild(node);
    const index = this.childNodes.indexOf(reference);
    node.parentNode = this;
    this.childNodes.splice(index < 0 ? this.childNodes.length : index, 0, node);
    return node;
  }
  before(...nodes) { if (this.parentNode) nodes.forEach((n) => this.parentNode.insertBefore(n, this)); }
  after(...nodes) {
    if (!this.parentNode) return;
    const siblings = this.parentNode.childNodes;
    const next = siblings[siblings.indexOf(this) + 1] || null;
    nodes.forEach((n) => this.parentNode.insertBefore(n, next));
  }
  removeChild(node) {
    const i = this.childNodes.indexOf(node);
    if (i >= 0) this.childNodes.splice(i, 1);
    node.parentNode = null;
    return node;
  }
  remove() { if (this.parentNode) this.parentNode.removeChild(this); }
  replaceChildren(...nodes) {
    this.childNodes.splice(0).forEach((n) => { n.parentNode = null; });
    this.append(...nodes);
  }
  replaceWith(node) { if (this.parentNode) { this.parentNode.insertBefore(node, this); this.remove(); } }
  contains(node) {
    for (let n = node; n; n = n.parentNode) if (n === this) return true;
    return false;
  }
  get textContent() { return this.childNodes.map((n) => n.textContent).join(''); }
  set textContent(value) {
    this.childNodes.splice(0).forEach((n) => { n.parentNode = null; });
    if (value !== '' && value != null) this.append(this.ownerDocument.createTextNode(String(value)));
  }
  addEventListener(type, fn) { (this.listeners[type] = this.listeners[type] || []).push(fn); }
  removeEventListener(type, fn) {
    this.listeners[type] = (this.listeners[type] || []).filter((f) => f !== fn);
  }
  dispatchEvent(event) {
    event.target = event.target || this;
    // Bubbles up to the document (html's parent)
    for (let node = this; node; node = node.parentNode) {
      event.currentTarget = node;
      (node.listeners[event.type] || []).slice().forEach((fn) => fn.call(node, event));
      if (!event.bubbles || event.propagationStopped) break;
    }
    return !event.defaultPrevented;
  }
  descendants() {
    const out = [];
    const walk = (n) => n.childNodes.forEach((c) => { if (c.nodeType === 1) { out.push(c); walk(c); } });
    walk(this);
    return out;
  }
  querySelectorAll(selector) { return this.descendants().filter((el) => matchesSelector(el, selector)); }
  querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
  getElementsByClassName(name) { return this.querySelectorAll(`.${name}`); }
}

class Text extends Node {
  constructor(document, text) { super(document); this.nodeType = 3; this.data = text; }
  get textContent() { return this.data; }
  set textContent(value) { this.data = String(value); }
}

class ClassList {
  constructor(el) { this.el = el; }
  get list() { return (this.el.getAttribute('class') || '').split(/\s+/).filter(Boolean); }
  set list(values) { this.el.setAttribute('class', values.join(' ')); }
  contains(c) { return this.list.includes(c); }
  add(...cs) { const l = this.list; cs.forEach((c) => { if (!l.includes(c)) l.push(c); }); this.list = l; }
  remove(...cs) { this.list = this.list.filter((c) => !cs.includes(c)); }
  toggle(c, force) {
    const on = force === undefined ? !this.contains(c) : !!force;
    if (on) this.add(c); else this.remove(c);
    return on;
  }
}

const BOOLEAN_PROPS = ['disabled', 'hidden', 'checked', 'required', 'readOnly', 'open', 'multiple'];
const STRING_PROPS = ['id', 'type', 'name', 'href', 'src', 'title', 'placeholder', 'role', 'htmlFor', 'rel', 'target', 'lang'];
const ATTR_OF = { htmlFor: 'for', readOnly: 'readonly' };

class Element extends Node {
  constructor(document, tag) {
    super(document);
    this.nodeType = 1;
    this.tagName = tag.toUpperCase();
    this.attributes = {};
    this.style = {};
    this.classList = new ClassList(this);
    this._innerHTML = null;
    const self = this;
    this.dataset = new Proxy({}, {
      get: (t, key) => self.getAttribute(`data-${String(key).replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`)}`) ?? undefined,
      set: (t, key, value) => { self.setAttribute(`data-${String(key).replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`)}`, value); return true; },
      deleteProperty: (t, key) => { self.removeAttribute(`data-${String(key)}`); return true; }
    });
  }
  // Like the browser: an input's value starts as its value attribute; a select's value is the chosen option's
  get value() {
    if (this._value !== undefined) return this._value;
    if (this.tagName === 'SELECT') {
      const options = this.querySelectorAll('option');
      const chosen = options.find((o) => o.hasAttribute('selected')) || options[0];
      return chosen ? (chosen.getAttribute('value') ?? chosen.textContent) : '';
    }
    return this.getAttribute('value') ?? '';
  }
  set value(v) { this._value = String(v); }
  get nodeName() { return this.tagName; }
  get localName() { return this.tagName.toLowerCase(); }
  get className() { return this.getAttribute('class') || ''; }
  set className(v) { this.setAttribute('class', v); }
  get isConnected() { return this.ownerDocument.documentElement.contains(this); }
  setAttribute(name, value) { this.attributes[name] = String(value); }
  getAttribute(name) { return Object.prototype.hasOwnProperty.call(this.attributes, name) ? this.attributes[name] : null; }
  hasAttribute(name) { return Object.prototype.hasOwnProperty.call(this.attributes, name); }
  removeAttribute(name) { delete this.attributes[name]; }
  toggleAttribute(name, force) {
    const on = force === undefined ? !this.hasAttribute(name) : !!force;
    if (on) this.setAttribute(name, ''); else this.removeAttribute(name);
    return on;
  }
  matches(selector) { return matchesSelector(this, selector); }
  closest(selector) {
    for (let n = this; n && n.nodeType === 1; n = n.parentNode) if (n.matches(selector)) return n;
    return null;
  }
  get innerHTML() { return this._innerHTML ?? this.textContent; }
  set innerHTML(value) { this.replaceChildren(); this._innerHTML = String(value); }
  focus() {
    const doc = this.ownerDocument;
    const previous = doc.activeElement;
    doc.activeElement = this;
    if (previous && previous !== this) previous.dispatchEvent(new Event('blur', { bubbles: false }));
    this.dispatchEvent(new Event('focus', { bubbles: false }));
    this.dispatchEvent(new Event('focusin'));
  }
  blur() { if (this.ownerDocument.activeElement === this) this.ownerDocument.activeElement = this.ownerDocument.body; }
  click() { if (!this.disabled) this.dispatchEvent(new Event('click')); }
  showModal() { this.open = true; this.setAttribute('open', ''); }
  show() { this.showModal(); }
  close(returnValue) {
    if (!this.open) return;
    this.open = false;
    this.removeAttribute('open');
    if (returnValue !== undefined) this.returnValue = returnValue;
    this.dispatchEvent(new Event('close', { bubbles: false }));
  }
  getBoundingClientRect() { return { top: 0, left: 0, width: 100, height: 20, right: 100, bottom: 20 }; }
  scrollIntoView() {}
  setSelectionRange() {}
  select() {}
  get offsetParent() { return this.hidden ? null : this.parentNode; }
}
BOOLEAN_PROPS.forEach((prop) => {
  const attr = ATTR_OF[prop] || prop.toLowerCase();
  Object.defineProperty(Element.prototype, prop, {
    get() { return this.hasAttribute(attr); },
    set(v) { if (v) this.setAttribute(attr, ''); else this.removeAttribute(attr); }
  });
});
STRING_PROPS.forEach((prop) => {
  const attr = ATTR_OF[prop] || prop;
  Object.defineProperty(Element.prototype, prop, {
    get() { return this.getAttribute(attr) ?? ''; },
    set(v) { this.setAttribute(attr, v); }
  });
});
Object.defineProperty(Element.prototype, 'tabIndex', {
  get() {
    const t = this.getAttribute('tabindex');
    if (t != null) return Number(t);
    return ['BUTTON', 'INPUT', 'SELECT', 'TEXTAREA', 'A'].includes(this.tagName) ? 0 : -1;
  },
  set(v) { this.setAttribute('tabindex', v); }
});

class Document extends Node {
  constructor() {
    super(null);
    this.ownerDocument = this;
    this.nodeType = 9;
    this.documentElement = new Element(this, 'html');
    this.head = new Element(this, 'head');
    this.body = new Element(this, 'body');
    this.documentElement.append(this.head, this.body);
    this.documentElement.parentNode = this;
    this.activeElement = this.body;
    this.cookie = '';
    this.title = '';
  }
  createElement(tag) { return new Element(this, tag); }
  createElementNS(ns, tag) { return new Element(this, tag); }
  createTextNode(text) { return new Text(this, String(text)); }
  createDocumentFragment() { const f = new Element(this, '#fragment'); return f; }
  getElementById(id) { return this.documentElement.querySelector(`#${id}`); }
  querySelectorAll(selector) { return this.documentElement.querySelectorAll(selector); }
  querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
  contains(node) { return this.documentElement.contains(node); }
}

/** Fake storage (sessionStorage / localStorage) */
function storage() {
  const values = new Map();
  return {
    getItem: (k) => (values.has(k) ? values.get(k) : null),
    setItem: (k, v) => values.set(k, String(v)),
    removeItem: (k) => values.delete(k),
    clear: () => values.clear()
  };
}

/** Globals for context(): a fresh document, events, storage, timers that run when flushed */
function domGlobals(extra = {}) {
  const document = new Document();
  const timers = [];
  const globals = {
    document, Event, KeyboardEvent: Event, MouseEvent: Event, CustomEvent: Event,
    sessionStorage: storage(), localStorage: storage(),
    setTimeout: (fn) => { timers.push(fn); return timers.length; },
    clearTimeout: () => {},
    requestAnimationFrame: (fn) => { timers.push(fn); return timers.length; },
    matchMedia: () => ({ matches: false, addEventListener() {}, removeEventListener() {} }),
    getComputedStyle: () => ({ getPropertyValue: () => '' }),
    flushTimers: () => { while (timers.length) timers.shift()(); },
    ...extra
  };
  return globals;
}

function key(el, name, init = {}) {
  const event = new Event('keydown', { key: name, ...init });
  el.dispatchEvent(event);
  return event;
}

module.exports = { Document, Element, Event, domGlobals, key, matchesSelector };
