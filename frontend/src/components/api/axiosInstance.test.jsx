jest.mock('axios', () => ({
  create: jest.fn(() => {
    const instance = jest.fn();
    instance.interceptors = {
      response: {
        use: jest.fn(),
      },
    };
    return instance;
  }),
}));

jest.mock('./refreshManager', () => ({
  safeRefreshToken: jest.fn(() => Promise.resolve()),
}));

import { safeRefreshToken } from './refreshManager';
import axiosInstance from './axiosInstance';

describe('axios 응답 인터셉터', () => {
  const onRejected = axiosInstance.interceptors.response.use.mock.calls[0][1];

  beforeEach(() => {
    safeRefreshToken.mockClear();
    safeRefreshToken.mockResolvedValue();
    axiosInstance.mockClear();
  });

  it('GitHub 권한 401은 access token refresh 없이 원래 오류를 전달한다', async () => {
    const error = {
      config: {},
      response: {
        status: 401,
        data: {
          code: 'GE-002',
          message: '해당 GitHub Repository에 권한이 없습니다.',
        },
      },
    };
    await expect(onRejected(error)).rejects.toBe(error);

    expect(safeRefreshToken).not.toHaveBeenCalled();
    expect(axiosInstance).not.toHaveBeenCalled();
  });

  it('401이 아닌 응답의 refresh marker는 access token refresh를 시작하지 않는다', async () => {
    const error = {
      config: {},
      response: {
        status: 400,
        data: {
          code: 'AUTH_REFRESH_REQUIRED',
          message: '잘못된 요청입니다.',
        },
      },
    };

    await expect(onRejected(error)).rejects.toBe(error);

    expect(safeRefreshToken).not.toHaveBeenCalled();
    expect(axiosInstance).not.toHaveBeenCalled();
  });

  it('refresh marker가 있는 401은 access token refresh 뒤 원 요청을 한 번 재시도한다', async () => {
    const originalRequest = { url: '/chat-rooms' };
    const response = { data: { id: 1 } };
    axiosInstance.mockResolvedValue(response);

    await expect(onRejected({
      config: originalRequest,
      response: {
        status: 401,
        data: {
          code: 'AUTH_REFRESH_REQUIRED',
          message: '토큰이 만료되었거나 유효하지 않습니다.',
        },
      },
    })).resolves.toBe(response);

    expect(safeRefreshToken).toHaveBeenCalledTimes(1);
    expect(axiosInstance).toHaveBeenCalledWith({ url: '/chat-rooms', _retry: true });
  });
});
