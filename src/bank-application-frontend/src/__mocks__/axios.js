/*
 *
 *    Copyright IBM Corp. 2023
 *
 */

// Manual Jest mock: tests never hit the network (axios 1.x ships ESM that CRA's Jest does not transform).
const axios = {
  get: jest.fn(),
  post: jest.fn(),
  put: jest.fn(),
  delete: jest.fn(),
  interceptors: {
    request: { use: jest.fn(), eject: jest.fn() },
    response: { use: jest.fn(), eject: jest.fn() },
  },
  create: jest.fn(),
};
axios.create.mockReturnValue(axios);
axios.default = axios;

module.exports = axios;
