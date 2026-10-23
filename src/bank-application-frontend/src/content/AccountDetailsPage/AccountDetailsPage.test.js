/*
 *
 *    Copyright IBM Corp. 2023
 *
 */

import React from 'react';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axios from 'axios';
import AccountDetailsPage from './AccountDetailsPage';
import { ACCOUNT_URL, account1, httpError, ok } from '../../testUtils/apiFixtures';
import { isModalOpen } from '../../testUtils/modal';

jest.mock('axios');

beforeEach(() => {
  jest.resetAllMocks();
});

it('looks an account up by number and renders the contract fields', async () => {
  axios.get.mockImplementation(url =>
    url === `${ACCOUNT_URL}/1` ? ok(account1) : httpError(404, `unexpected GET ${url}`)
  );
  render(<AccountDetailsPage />);

  await userEvent.type(screen.getByLabelText(/account number/i), '1');
  await userEvent.click(screen.getByRole('button', { name: 'Submit' }));

  const table = await screen.findByRole('table');
  const typeCell = await within(table).findByText('ISA');
  const row = within(typeCell.closest('tr'));
  expect(row.getByText('00000001')).toBeInTheDocument();
  expect(row.getByText('987654')).toBeInTheDocument();
  expect(row.getByText('2.1')).toBeInTheDocument();
  expect(row.getByText('01-01-2020')).toBeInTheDocument();
  expect(row.getByText('01-12-2025')).toBeInTheDocument();
  expect(row.getByText('01-01-2026')).toBeInTheDocument();
  expect(axios.get).toHaveBeenCalledWith(`${ACCOUNT_URL}/1`);
});

it('opens the "No accounts found!" modal on a 404', async () => {
  axios.get.mockImplementation(() => httpError(404, 'Account number 99 not found.'));
  render(<AccountDetailsPage />);

  await userEvent.type(screen.getByLabelText(/account number/i), '99');
  await userEvent.click(screen.getByRole('button', { name: 'Submit' }));

  await waitFor(() => expect(isModalOpen('No accounts found!')).toBe(true));
  expect(axios.get).toHaveBeenCalledWith(`${ACCOUNT_URL}/99`);
});

it('opens the "No accounts found!" modal without calling the API when no number is entered', async () => {
  render(<AccountDetailsPage />);
  await userEvent.click(screen.getByRole('button', { name: 'Submit' }));
  expect(isModalOpen('No accounts found!')).toBe(true);
  expect(axios.get).not.toHaveBeenCalled();
});
