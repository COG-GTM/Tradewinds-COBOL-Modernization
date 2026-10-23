/*
 *
 *    Copyright IBM Corp. 2023
 *
 */

import React from 'react';
import { render, screen } from '@testing-library/react';
import App from './App';

jest.mock('axios');

function renderAt(hash) {
  window.location.hash = hash;
  return render(<App />);
}

afterEach(() => {
  window.location.hash = '';
});

it('renders the home page with the bank header', () => {
  renderAt('#/');
  expect(screen.getAllByText(/CICS Banking Sample Application/i).length).toBeGreaterThan(0);
});

it('routes #/profile/Admin to the control panel with links to every admin page', () => {
  renderAt('#/profile/Admin');
  expect(screen.getByLabelText('Page navigation')).toHaveTextContent('Control Panel');
  [
    'Create a new customer',
    'Delete existing customer',
    'View/Update customer details (and see their accounts)',
    'Create a New Account',
    'Delete existing account',
    'View/Update account details',
  ].forEach(text => expect(screen.getByText(text)).toBeInTheDocument());
});

it.each([
  ['#/Admin/customer_details', 'View Customer Details'],
  ['#/Admin/account_details', 'View Account Details'],
  ['#/Admin/customer_creation', 'Create new customer'],
  ['#/Admin/account_creation', 'Create new account'],
  ['#/Admin/customer_deletion', 'Customer Deletion'],
  ['#/Admin/account_deletion', 'Account Deletion'],
])('routes %s to its page', (hash, heading) => {
  renderAt(hash);
  expect(screen.getByRole('heading', { level: 1, name: heading })).toBeInTheDocument();
});
