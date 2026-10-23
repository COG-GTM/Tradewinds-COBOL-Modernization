# bank-application-frontend

Repo for using Carbon React Front-End Framework for CBSA and the code changes associated with it.

## Requirements

This project was generated with [React JS](https://react.dev/) version 16.0.0. You can install this package using NPM package manager. Ensure you use a npm version that compatible with NodeJS (i.e. NPM 6.14.18 for NodeJS 14.21.3.) The minimum required version of NodeJS is 4.9.1.

## Install Dependencies

Run npm install , or npm i , is used to install dependencies: It will install all the dependencies to the local node_modules folder based on the definition in package-json.

## Getting Started

Run ng serve for a dev server. Navigate to http://localhost:4200/. The application will automatically reload if you change any of the source files.

## Running against cbsa-core-service (Java 21 / Spring Boot 3)

The UI calls `REACT_APP_CUSTOMER_URL=/webui-1.0/banking/customer` and `REACT_APP_ACCOUNT_URL=/webui-1.0/banking/account` (see `.env`), served by `src/cbsa-core-service` (context path `/webui-1.0`, REST contract in `docs/migration/api-contract.md`). Yarn 4 is selected through corepack from the `packageManager` field (`corepack enable`, or prefix commands with `corepack`).

- Dev server: start the service (`src/cbsa-core-service/run-local.sh`), then `yarn install && yarn start`. The CRA `proxy` in `package.json` forwards the API calls to `http://localhost:8080`; open http://localhost:3000/webui-1.0/.
- Served by Spring Boot: `./updateCoreService.sh` (or `yarn build:core`) runs `yarn install && yarn build` and copies `build/` into `src/cbsa-core-service/src/main/resources/static/`. Rebuild the service and open http://localhost:8080/webui-1.0/. The UI uses `HashRouter`, so Spring Boot's welcome page (`static/index.html`) is all the routing it needs; no SPA-forwarding controller is required.
- `updateWebUI.sh` / `updateWebUI.bat` still copy the build into the legacy Liberty `src/webui` module.

## Important to note

The back-end implementation of Customer Name search is designed to drive java workload, so is MUCH slower to return results than customer number search.

## Known issues/Areas for improvement

Front-end:
- Account expansion rows when showing multiple customers (ie. by name search) is disabled due to an issue with the expansion row implementation (it cannot currently differentiate between different customers correctly when a row is expanded, so all customers will show the same set of accounts. The expanded row headers still appear but no data will appear.
- There is currently no error modal shown if there are no results to show, the table just remains empty.
- When creating or editing a customer/account, if there is an issue (i.e. missing D.O.B) the error modal is generic and doesn't pinpoint where the error is, it just states that there is an error somewhere in the user entry fields.
- Users are required to login before accessing the admin panel, but there are no actual login credentials required, users just need to press submit.
