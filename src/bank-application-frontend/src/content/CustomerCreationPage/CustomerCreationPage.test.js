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
import CustomerCreationPage from './CustomerCreationPage';
import { CUSTOMER_URL, customer1, httpError, ok } from '../../testUtils/apiFixtures';
import { isModalOpen } from '../../testUtils/modal';

jest.mock('axios');

beforeEach(() => {
  jest.resetAllMocks();
  window.alert = jest.fn();
});

function renderPage() {
  render(
    <HashRouter>
      <CustomerCreationPage />
    </HashRouter>
  );
}

async function fillForm({ dob = '03-02-1980' } = {}) {
  await userEvent.click(screen.getByRole('combobox', { name: 'Title' }));
  await userEvent.click(await screen.findByText('Mr'));
  await userEvent.type(screen.getByLabelText('Full name'), 'Ab Smith');
  if (dob) {
    await userEvent.type(screen.getByLabelText('Date of Birth'), dob);
  }
  await userEvent.type(screen.getByLabelText('Address line 1'), '1 High St');
  await userEvent.type(screen.getByLabelText('City'), 'Leeds');
}

it('POSTs the contract body (ISO date of birth, sort code 987654) and shows the new customer id', async () => {
  axios.post.mockImplementation(() => ok({ ...customer1, id: '0000000042' }, 201));
  renderPage();
  await fillForm();
  await userEvent.click(screen.getByRole('button', { name: 'Submit' }));

  await waitFor(() => expect(isModalOpen('Customer created successfully')).toBe(true));
  expect(screen.getByText('42')).toBeInTheDocument();
  expect(axios.post).toHaveBeenCalledWith(CUSTOMER_URL, {
    customerName: 'Mr Ab Smith',
    customerAddress: '1 High St, Leeds',
    dateOfBirth: '1980-02-03',
    sortCode: '987654',
  });
  expect(window.alert).not.toHaveBeenCalled();
});

it('shows the failure modal when the service rejects the customer (e.g. failCode T)', async () => {
  axios.post.mockImplementation(() => httpError(400, 'Customer title X is not valid'));
  renderPage();
  await fillForm();
  await userEvent.click(screen.getByRole('button', { name: 'Submit' }));
  await waitFor(() => expect(isModalOpen('Customer creation unsuccessful')).toBe(true));
});

it('alerts and does not call the API when the date of birth is missing', async () => {
  renderPage();
  await fillForm({ dob: null });
  await userEvent.click(screen.getByRole('button', { name: 'Submit' }));
  expect(window.alert).toHaveBeenCalledWith('Date of Birth is not 10 characters. dd-mm-yyyy please');
  expect(axios.post).not.toHaveBeenCalled();
});
