# access token refresh 대상 401 계약 분리

## 목적

GitHub 연동 오류의 401 응답이 프론트엔드 axios 인터셉터에서 앱 access token 만료로 처리되어, 불필요한 token refresh와 채팅방 생성 재시도가 발생하지 않게 한다.

## 변경 사항

- `JwtAuthenticationFilter`가 access token 없음 또는 검증 실패를 반환할 때, 기존 `message`와 함께 `code: AUTH_REFRESH_REQUIRED`를 반환한다.
- axios 응답 인터셉터는 HTTP 401이면서 `AUTH_REFRESH_REQUIRED` 코드가 있는 경우에만 refresh 후 원 요청을 한 번 재시도한다.
- GitHub 권한 오류 `GE-002`와 GitHub OAuth token 오류 `TE-002`는 refresh 없이 기존 생성 화면의 오류 메시지 처리로 전달한다.

## 영향 범위

- access token 문제의 기존 refresh와 1회 재시도 흐름은 유지한다.
- GitHub 연동 오류의 HTTP 상태와 `message` 필드는 바꾸지 않는다.
- `RestAuthenticationEntryPoint`나 일반 `AuthException`의 오류 계약은 변경하지 않는다.

## 검증

- `JwtAuthenticationFilterTest`에서 access token 없는 요청이 `AUTH_REFRESH_REQUIRED`를 포함한 401을 반환하는지 확인했다.
- `axiosInstance.test.jsx`에서 GitHub 401은 refresh하지 않고 전달되는지, 401이 아닌 marker 응답은 refresh하지 않는지, refresh marker 401은 한 번 재시도하는지 확인했다.
- frontend 전체 테스트와 production build를 실행했다.

## 남은 리스크

- GitHub OAuth token 오류 코드 `TE-002`는 access token 만료 오류 코드와 문자열이 중복된다. refresh 대상은 `AUTH_REFRESH_REQUIRED`로 구분하지만, 오류 코드 체계 정리는 별도 호환성 검토가 필요하다.
