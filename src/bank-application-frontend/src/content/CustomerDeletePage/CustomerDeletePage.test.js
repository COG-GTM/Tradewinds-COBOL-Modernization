/*
 *
 *    Copyright IBM Corp. 2023
 *
 */

import React from 'react';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axios from 'axios';
import CustomerDeletePage from './CustomerDeletePage';
import {
  ACCOUNT_URL,
  CUSTOMER_URL,
  accountsOfCustomer1,
  customer1,
  httpError,
  ok,
} from '../../testUtils/apiFixtures';
import { isModalOpen } from '../../testUtils/modal';

jest.mock('axios');

const noAccounts = { customerNumber: '0000000001', customerFound: true, numberOfAccounts: 0, accounts: [] };

function routeGets(accountsBody) {
  axios.get.mockImplementation(url => {
    if (url === `${CUSTOMER_URL}/1`) return ok(customer1);
    if (url === `${ACCOUNT_URL}/retrieveByCustomerNumber/1`) return ok(accountsBody);
    return httpError(404, `unexpected GET ${url}`);
  });
}

async function lookUpAndConfirmDelete() {
  render(<CustomerDeletePage />);
  // NumberInput has no id here, so its label is not associated; it is the page's only number field.
  await userEvent.type(screen.getByRole('spinbutton'), '1');
  await userEvent.click(screen.getByRole('button', { name: 'Submit' }));
  const [table] = await screen.findAllByRole('table');
  const nameCell = await within(table).findByText('Mr Ab Smith');
  // Carbon danger buttons prefix a visually-hidden "danger" span, and the row also holds the (CSS-hidden) modals.
  await userEvent.click(nameCell.closest('tr').querySelector('button.displayModal'));
  const confirm = screen.getByText('Are you sure you want to delete this customer?').closest('.cds--modal');
  expect(confirm).toHaveClass('is-visible');
  expect(within(confirm).getByText(/delete Mr Ab Smith\?/)).toBeInTheDocument();
  await userEvent.click(within(confirm).getByRole('button', { name: /Delete/ }));
}

beforeEach(() => {
  jest.resetAllMocks();
});

it('deletes a customer that has no accounts via DELETE /banking/customer/{id}', async () => {
  routeGets(noAccounts);
  axios.delete.mockImplementation(() => ok(customer1));
  await lookUpAndConfirmDelete();

  await waitFor(() => expect(isModalOpen('Customer deleted successfully')).toBe(true));
  expect(axios.delete).toHaveBeenCalledTimes(1);
  expect(axios.delete).toHaveBeenCalledWith(`${CUSTOMER_URL}/1`);
});

it('refuses to delete a customer whose numberOfAccounts is not 0', async () => {
  routeGets(accountsOfCustomer1);
  await lookUpAndConfirmDelete();

  await waitFor(() => expect(isModalOpen('Unable to delete the customer!')).toBe(true));
  expect(axios.delete).not.toHaveBeenCalled();
});
