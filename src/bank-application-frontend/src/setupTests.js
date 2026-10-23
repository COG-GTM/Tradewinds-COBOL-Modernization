/*
 *
 *    Copyright IBM Corp. 2023
 *
 */

import '@testing-library/jest-dom';
import { TextDecoder, TextEncoder } from 'util';

// jsdom (Jest 27) lacks these Web APIs that libraries in the dependency tree expect.
if (typeof global.TextDecoder === 'undefined') {
  global.TextDecoder = TextDecoder;
}
if (typeof global.TextEncoder === 'undefined') {
  global.TextEncoder = TextEncoder;
}

if (typeof window !== 'undefined') {
  if (!window.matchMedia) {
    window.matchMedia = query => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    });
  }
  if (!window.ResizeObserver) {
    window.ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    };
  }
  if (!window.HTMLElement.prototype.scrollIntoView) {
    window.HTMLElement.prototype.scrollIntoView = () => {};
  }
}
