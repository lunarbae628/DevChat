// axiosInstance.js
import axios from 'axios';
import { safeRefreshToken } from './refreshManager'; // 👈 중복 호출 방지용 유틸

const instance = axios.create({
  baseURL: process.env.REACT_APP_BASE_API_URL,
  withCredentials: true,
});

let refreshSubscribers = [];

const onRefreshed = () => {
  refreshSubscribers.forEach(callback => callback());
  refreshSubscribers = [];
};

const addRefreshSubscriber = (callback) => {
  refreshSubscribers.push(callback);
};

// ✅ 응답 인터셉터
instance.interceptors.response.use(
  (response) => response,
  async (error) => {
    const { config, response } = error;
    const originalRequest = config;

    if (response?.status === 401
      && response?.data?.code === 'AUTH_REFRESH_REQUIRED'
      && !originalRequest._retry) {
      originalRequest._retry = true;

      return new Promise((resolve, reject) => {
        addRefreshSubscriber(() => {
          resolve(instance(originalRequest)); // 재요청 실행
        });

        // ✅ 단 한 번만 refresh 실행
        safeRefreshToken()
          .then(() => {
            onRefreshed(); // 대기 중인 요청들 처리
          })
          .catch((err) => {
            window.location.replace('/login');
            reject(err);
          });
      });
    }

    return Promise.reject(error);
  }
);

export default instance;
