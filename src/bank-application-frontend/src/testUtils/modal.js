/*
 *
 *    Copyright IBM Corp. 2023
 *
 */

import { screen } from '@testing-library/react';

// Carbon keeps closed modals in the DOM; "open" is the is-visible class on the .cds--modal container.
export function isModalOpen(text) {
  const container = screen.getByText(text).closest('.cds--modal');
  if (!container) {
    throw new Error(`"${text}" is not inside a Carbon modal`);
  }
  return container.classList.contains('is-visible');
}
