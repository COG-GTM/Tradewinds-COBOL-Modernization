/*
 *
 *    Copyright IBM Corp. 2023
 *
 */

// Response bodies shaped exactly as docs/migration/api-contract.md defines them.

export const CUSTOMER_URL = '/webui-1.0/banking/customer';
export const ACCOUNT_URL = '/webui-1.0/banking/account';

export const customer1 = {
  id: '0000000001',
  sortCode: '987654',
  customerName: 'Mr Ab Smith',
  customerAddress: '1 High St, Leeds',
  dateOfBirth: '1980-02-03',
  customerCreditScore: '500',
  customerCreditScoreReviewDate: '2026-02-01',
};

export const account1 = {
  id: '00000001',
  sortCode: '987654',
  customerNumber: '0000000001',
  accountType: 'ISA',
  interestRate: 2.1,
  overdraft: 0,
  availableBalance: 100.0,
  actualBalance: 100.0,
  dateOpened: '2020-01-01',
  lastStatementDate: '2025-12-01',
  nextStatementDate: '2026-01-01',
};

export const account2 = {
  ...account1,
  id: '00000002',
  accountType: 'CURRENT',
  interestRate: 0,
  overdraft: 250,
  availableBalance: 42.5,
  actualBalance: 42.5,
};

export const accountsOfCustomer1 = {
  customerNumber: '0000000001',
  customerFound: true,
  numberOfAccounts: 2,
  accounts: [account1, account2],
};

export function ok(data, status = 200) {
  return Promise.resolve({ status, data });
}

export function httpError(status, errorMessage) {
  const error = new Error(`Request failed with status code ${status}`);
  error.response = { status, data: { errorMessage } };
  return Promise.reject(error);
}

export function networkError() {
  const error = new Error('Network Error');
  error.request = {};
  return Promise.reject(error);
}
