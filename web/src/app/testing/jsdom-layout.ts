/**
 * Test setup (angular.json `test.options.setupFiles`). jsdom has no layout, and its `Range` has no
 * `getClientRects` or `getBoundingClientRect`. ProseMirror calls them to scroll the selection into
 * view, which Tiptap's `focus()` does on a later animation frame: under load that frame lands
 * after the test and fails the run with an unhandled error. Empty rectangles are enough here.
 */
const emptyRect = (): DOMRect =>
  ({
    x: 0,
    y: 0,
    top: 0,
    right: 0,
    bottom: 0,
    left: 0,
    width: 0,
    height: 0,
    toJSON: () => ({}),
  }) as DOMRect;

if (typeof Range !== 'undefined') {
  Range.prototype.getBoundingClientRect ??= emptyRect;
  Range.prototype.getClientRects ??= () =>
    Object.assign([] as DOMRect[], { item: () => null }) as unknown as DOMRectList;
}
