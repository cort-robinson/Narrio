package app.narrio.reader

/**
 * Scripts the reader evaluates in the current page. They only read the layout; they never alter content.
 * Positions are reported as the parser offset of the enclosing marked block (`data-narrio-o`) and the raw
 * `textContent` index inside it, which [CursorMapping] turns into a content offset.
 */
internal object ReaderScripts {
    private const val COMMON = """
var narrio = window.__narrio || (window.__narrio = (function () {
  function scrollMode() {
    return document.documentElement.style.getPropertyValue('--USER__view').trim() === 'readium-scroll-on';
  }
  function rtl() {
    var dir = (document.body && document.body.dir || document.documentElement.dir || '').toLowerCase();
    return dir === 'rtl';
  }
  // -1 before the viewport, 0 visible, 1 after it, null when the rect is empty.
  function state(rect) {
    if (rect.width === 0 && rect.height === 0) return null;
    if (scrollMode()) {
      if (rect.bottom <= 0) return -1;
      if (rect.top >= window.innerHeight) return 1;
      return 0;
    }
    if (rtl()) {
      if (rect.left >= window.innerWidth) return -1;
      if (rect.right <= 0) return 1;
      return 0;
    }
    if (rect.right <= 0) return -1;
    if (rect.left >= window.innerWidth) return 1;
    return 0;
  }
  function nodeState(node) {
    var range = document.createRange();
    range.selectNodeContents(node);
    var rects = range.getClientRects();
    var seen = null;
    for (var i = 0; i < rects.length; i++) {
      var s = state(rects[i]);
      if (s === null) continue;
      if (s === 0) return 0;
      if (seen === null) seen = s; else if (seen !== s) return 0;
    }
    return seen;
  }
  function charState(node, index) {
    var range = document.createRange();
    range.setStart(node, index);
    range.setEnd(node, Math.min(index + 1, node.data.length));
    var rects = range.getClientRects();
    for (var i = 0; i < rects.length; i++) { var s = state(rects[i]); if (s !== null) return s; }
    return null;
  }
  function textNodes() {
    var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_CDATA_SECTION);
    var nodes = [];
    while (walker.nextNode()) { if (/\S/.test(walker.currentNode.data)) nodes.push(walker.currentNode); }
    return nodes;
  }
  // First character index in node whose state is not "before" (or not "visible" when wantAfter).
  function boundary(node, wantAfter) {
    var low = 0, high = node.data.length;
    while (low < high) {
      var mid = (low + high) >> 1;
      var s = charState(node, mid);
      var past = wantAfter ? s === 1 : (s === 0 || s === 1);
      if (past) high = mid; else low = mid + 1;
    }
    return low;
  }
  function rawOffset(block, node, offset) {
    var walker = document.createTreeWalker(block, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_CDATA_SECTION);
    var count = 0;
    while (walker.nextNode()) {
      if (walker.currentNode === node) return count + offset;
      count += walker.currentNode.data.length;
    }
    return count;
  }
  function position(node, offset) {
    var element = node.nodeType === 1 ? node : node.parentElement;
    var block = element && element.closest('[data-narrio-o]');
    if (block) return { o: +block.getAttribute('data-narrio-o'), r: node.nodeType === 1 ? 0 : rawOffset(block, node, offset) };
    var blocks = document.querySelectorAll('[data-narrio-o]');
    for (var i = 0; i < blocks.length; i++) {
      if (node.compareDocumentPosition(blocks[i]) & Node.DOCUMENT_POSITION_FOLLOWING) return { o: +blocks[i].getAttribute('data-narrio-o'), r: 0 };
    }
    if (blocks.length) { var last = blocks[blocks.length - 1]; return { o: +last.getAttribute('data-narrio-o'), r: last.textContent.length }; }
    return { o: null, r: 0 };
  }
  function visible() {
    var nodes = textNodes();
    var first = null, last = null, i = 0;
    for (; i < nodes.length; i++) {
      var s = nodeState(nodes[i]);
      if (s === null || s === -1) continue;
      first = s === 1 ? position(nodes[i], 0) : position(nodes[i], boundary(nodes[i], false));
      break;
    }
    if (first === null) return { first: null, last: null };
    for (var j = i; j < nodes.length; j++) {
      var t = nodeState(nodes[j]);
      if (t === 1) {
        last = j === i ? position(nodes[j], 0) : position(nodes[j], 0);
        break;
      }
      if (t === 0) {
        var end = boundary(nodes[j], true);
        if (end < nodes[j].data.length) { last = position(nodes[j], end); break; }
      }
    }
    return { first: first, last: last };
  }
  function selection() {
    var selection = window.getSelection();
    if (!selection || selection.rangeCount === 0 || selection.isCollapsed) return null;
    var range = selection.getRangeAt(0);
    return { start: position(range.startContainer, range.startOffset), end: position(range.endContainer, range.endOffset), text: selection.toString() };
  }
  return { visible: visible, selection: selection };
})());
"""

    /** `{first: {o, r}, last: {o, r} | null}`: the first visible character and the first one past the page. */
    const val VISIBLE = "$COMMON\nnarrio.visible();"

    /** The current text selection as `{start, end, text}`, or null. */
    const val SELECTION = "$COMMON\nnarrio.selection();"
}
