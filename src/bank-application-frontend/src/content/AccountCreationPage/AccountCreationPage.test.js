/*
 *
 *    Copyright IBM Corp. 2023
 *
 */

import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { HashRouter } from 'react-router-dom';
import axios from 'axios';
import AccountCreationPage from './AccountCreationPage';
import { ACCOUNT_URL, account1, httpError, networkError, ok } from '../../testUtils/apiFixtures';
import { isModalOpen } from '../../testUtils/modal';

jest.mock('axios');

beforeEach(() => {
  jest.resetAllMocks();
});

async function fillAndSubmit() {
  render(
    <HashRouter>
      <AccountCreationPage />
    </HashRouter>
  );
  await userEvent.type(screen.getByLabelText('Customer number'), '1');
  await userEvent.click(screen.getByRole('combobox', { name: /account type/i }));
  await userEvent.click(await screen.findByText('SAVING'));
  await userEvent.type(document.getElementById('carbon-number'), '100');
  await userEvent.type(document.getElementById('interestRate'), '1.5');
  await userEvent.click(screen.getByRole('button', { name: 'Submit' }));
}

it('POSTs the contract body and shows the new account number', async () => {
  axios.post.mockImplementation(() =>
    ok({ ...account1, id: '00000042', accountType: 'SAVING', interestRate: 1.5, overdraft: 100 }, 201)
  );
  await fillAndSubmit();

  await waitFor(() => expect(isModalOpen('Customer account created successfully')).toBe(true));
  expect(screen.getByText('Account Number: 00000042')).toBeInTheDocument();
  expect(axios.post).toHaveBeenCalledTimes(1);
  const [url, body] = axios.post.mock.calls[0];
  expect(url).toBe(ACCOUNT_URL);
  expect(body).toEqual({
    customerNumber: '1',
    accountType: 'SAVING',
    overdraft: '100',
    interestRate: '1.5',
    sortCode: '987654',
    dateOpened: expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/),
  });
});

it('shows the failure modal when the service rejects the account (e.g. failCode 8)', async () => {
  axios.post.mockImplementation(() =>
    httpError(400, 'Customer number 1 cannot have more than ten accounts.')
  );
  await fillAndSubmit();
  await waitFor(() => expect(isModalOpen('Customer account failed to create')).toBe(true));
  expect(isModalOpen('Customer account created successfully')).toBe(false);
});

it('shows the network-error modal when the service is unreachable', async () => {
  axios.post.mockImplementation(() => networkError());
  await fillAndSubmit();
  await waitFor(() =>
    expect(isModalOpen('Account failed to create due to a network error')).toBe(true)
  );
});
