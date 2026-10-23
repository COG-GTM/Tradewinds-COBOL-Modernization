/*
 *
 *    Copyright IBM Corp. 2023
 *
 */

import React from 'react';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axios from 'axios';
import CustomerDetailsPage from './CustomerDetailsPage';
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

function routeGets(routes) {
  axios.get.mockImplementation(url => {
    if (url in routes) {
      return routes[url]();
    }
    return httpError(404, `unexpected GET ${url}`);
  });
}

beforeEach(() => {
  jest.resetAllMocks();
});

it('looks a customer up by number and lists the customer and their accounts', async () => {
  routeGets({
    [`${CUSTOMER_URL}/1`]: () => ok(customer1),
    [`${ACCOUNT_URL}/retrieveByCustomerNumber/1`]: () => ok(accountsOfCustomer1),
  });
  render(<CustomerDetailsPage />);

  await userEvent.type(screen.getByLabelText(/customer's number/i), '1');
  await userEvent.click(screen.getByRole('button', { name: 'Submit' }));

  const [table, accountsTable] = await screen.findAllByRole('table');
  const customerRow = await within(table).findByText('Mr Ab Smith');
  const row = within(customerRow.closest('tr'));
  expect(row.getByText('1')).toBeInTheDocument();
  expect(row.getByText('987654')).toBeInTheDocument();
  expect(row.getByText('1 High St, Leeds')).toBeInTheDocument();
  expect(row.getByText('03-02-1980')).toBeInTheDocument();
  expect(row.getByText('500')).toBeInTheDocument();
  expect(row.getByText('01-02-2026')).toBeInTheDocument();

  expect(axios.get).toHaveBeenCalledWith(`${CUSTOMER_URL}/1`);
  await waitFor(() =>
    expect(axios.get).toHaveBeenCalledWith(`${ACCOUNT_URL}/retrieveByCustomerNumber/1`)
  );
  expect(await within(accountsTable).findByText('00000002')).toBeInTheDocument();
  expect(within(accountsTable).getByText('CURRENT')).toBeInTheDocument();
});

it('searches by name with limit=10 and renders every customer returned', async () => {
  const second = { ...customer1, id: '0000000002', customerName: 'Mrs Cd Smith' };
  routeGets({
    [`${CUSTOMER_URL}/name?name=Smith&limit=10`]: () =>
      ok({ numberOfCustomers: 2, customers: [customer1, second] }),
    [`${ACCOUNT_URL}/retrieveByCustomerNumber/1`]: () => ok(accountsOfCustomer1),
    [`${ACCOUNT_URL}/retrieveByCustomerNumber/2`]: () =>
      ok({ customerNumber: '0000000002', customerFound: true, numberOfAccounts: 0, accounts: [] }),
  });
  render(<CustomerDetailsPage />);

  await userEvent.type(screen.getByLabelText(/customer's name/i), 'Smith');
  await userEvent.click(screen.getByRole('button', { name: 'Submit' }));

  const [table] = await screen.findAllByRole('table');
  expect(await within(table).findByText('Mr Ab Smith')).toBeInTheDocument();
  expect(within(table).getByText('Mrs Cd Smith')).toBeInTheDocument();
  expect(axios.get).toHaveBeenCalledWith(`${CUSTOMER_URL}/name?name=Smith&limit=10`);
  await waitFor(() =>
    expect(axios.get).toHaveBeenCalledWith(`${ACCOUNT_URL}/retrieveByCustomerNumber/2`)
  );
});

it('opens the "No customers found!" modal on a 404 from the contract', async () => {
  routeGets({
    [`${CUSTOMER_URL}/42`]: () => httpError(404, 'Customer number 42 not found.'),
  });
  render(<CustomerDetailsPage />);
  expect(isModalOpen('No customers found!')).toBe(false);

  await userEvent.type(screen.getByLabelText(/customer's number/i), '42');
  await userEvent.click(screen.getByRole('button', { name: 'Submit' }));

  await waitFor(() => expect(isModalOpen('No customers found!')).toBe(true));
  expect(axios.get).not.toHaveBeenCalledWith(expect.stringContaining('retrieveByCustomerNumber'));
});

it('shows each customer only their own accounts even when account lookups resolve out of order', async () => {
  const customer20 = { ...customer1, id: '0000000020', customerName: 'Mr Ef Smith' };
  const account51 = { ...accountsOfCustomer1.accounts[0], id: '00000051', customerNumber: '0000000020', accountType: 'MORTGAGE' };
  let releaseCustomer20Accounts;
  routeGets({
    [`${CUSTOMER_URL}/name?name=Smith&limit=10`]: () =>
      ok({ numberOfCustomers: 2, customers: [customer1, customer20] }),
    [`${ACCOUNT_URL}/retrieveByCustomerNumber/1`]: () => ok(accountsOfCustomer1),
    [`${ACCOUNT_URL}/retrieveByCustomerNumber/20`]: () =>
      new Promise(resolve => {
        releaseCustomer20Accounts = () =>
          resolve({
            status: 200,
            data: { customerNumber: '0000000020', customerFound: true, numberOfAccounts: 1, accounts: [account51] },
          });
      }),
  });
  render(<CustomerDetailsPage />);

  await userEvent.type(screen.getByLabelText(/customer's name/i), 'Smith');
  await userEvent.click(screen.getByRole('button', { name: 'Submit' }));

  let tables = await screen.findAllByRole('table');
  expect(await within(tables[1]).findByText('00000002')).toBeInTheDocument();
  await waitFor(() => expect(releaseCustomer20Accounts).toBeDefined());
  releaseCustomer20Accounts();

  await waitFor(() => expect(within(screen.getAllByRole('table')[2]).getByText('00000051')).toBeInTheDocument());
  tables = screen.getAllByRole('table');
  expect(within(tables[1]).getByText('00000001')).toBeInTheDocument();
  expect(within(tables[1]).getByText('00000002')).toBeInTheDocument();
  expect(within(tables[1]).queryByText('00000051')).not.toBeInTheDocument();
  expect(within(tables[2]).queryByText('00000002')).not.toBeInTheDocument();
});
