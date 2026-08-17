# GitHub 웹훅 동기 외부 호출 최적화 설계

> **상태: 구현 반영 설계 기록.** 현재 동작과 측정 결과는 `docs/knowledge/changes/2026-08-17-github-webhook-sync-optimization.md`를 기준으로 하며, 이 문서는 RAG 검색 대상이 아니다.

## 목적

GitHub 레포지토리 URL을 포함한 채팅방 생성에서 웹훅 등록 완료를 `201 Created`의 성공 조건으로 유지하면서, GitHub API 왕복을 두 번에서 한 번으로 줄인다.

GitHub URL이 없는 채팅방 생성은 GitHub API를 호출하지 않으며, 이번 변경 뒤에도 동일하게 동작해야 한다.

## 변경 전 흐름과 문제

`ChatRoomService.createChatRoom`은 트랜잭션 안에서 채팅방을 저장한 후 `GitMessageService.registerWebhook`을 호출한다. 이 서비스는 다음 두 GitHub HTTP 요청을 순서대로 실행하고 각각 `.block()`으로 완료를 기다린다.

```text
POST /chat-rooms
  → 채팅방 저장
  → GET /repos/{owner}/{repo}: permissions.admin 확인
  → POST /repos/{owner}/{repo}/hooks: 웹훅 등록
  → webhookId 저장, GitHub 봇 참가
  → 201 Created
```

두 번째 요청은 GitHub가 관리 권한을 직접 검사한다. 따라서 첫 번째 요청은 성공 경로에서 추가 지연만 만들며, 웹훅을 만들 수 있는지 결정하기 위한 필수 선행 조건은 아니다.

## 대안과 선택

### 1. 커밋 후 비동기 웹훅 등록

응답 시간은 줄일 수 있지만, 생성 성공 뒤 웹훅 등록 실패를 관리하기 위한 연동 상태, 재시도, 중복 등록 방지, 운영 복구와 사용자 안내가 필요하다. `201 Created`의 의미도 "연동 완료"에서 "등록 요청 수락"으로 바뀐다.

이번 범위에서는 채택하지 않는다.

### 2. 동기 완료 계약을 유지하고 사전 권한 조회 제거 — 채택

웹훅 생성 요청 하나의 성공을 채팅방 생성 성공 조건으로 둔다. 응답 지연은 GitHub 왕복 한 번만큼 줄며, 연동 완료 보장과 기존 API 응답 형태를 유지한다.

### 3. 권한 결과 캐시

반복 생성에서 호출을 줄일 수 있지만, 권한 변경·토큰 변경·캐시 무효화 정책을 추가해야 한다. 현재 단일 생성 요청의 중복 호출을 제거하는 것보다 복잡도가 크므로 보류한다.

## 설계

### 채팅방 생성 계약

```text
GitHub URL 없음 또는 공백 → GitHub 호출 없이 채팅방 생성 후 201
GitHub URL 있음          → GitHub 웹훅 생성 성공 후 webhookId 저장, GitHub 봇 참가, 201
GitHub 웹훅 생성 실패    → 예외를 반환하고 채팅방 생성 트랜잭션 롤백
```

`repositoryUrl`은 요청 JSON에서 생략될 수 있으므로, 조건식은 `null`과 공백 문자열을 모두 GitHub 미연동으로 처리한다. 기존 프런트엔드가 보내는 빈 문자열도 그대로 허용한다.

`GitMessageService.registerWebhook`에서 `GitHubClient.validateAdminPermission` 호출을 제거한다. URL 형식 검증, OAuth 토큰 조회, 웹훅 URL 생성과 `webhookId` 저장 순서는 유지한다.

### GitHub 오류 계약

웹훅 생성 요청에서 아래 오류를 기존 사용자용 오류 코드로 변환한다.

| GitHub 응답 | 반환 오류 코드 | 의미 |
| --- | --- | --- |
| 401 | `INVALID_TOKEN` | OAuth 토큰이 유효하지 않음 |
| 404 | `REPO_NOT_FOUND` | 레포지토리를 찾을 수 없거나 접근할 수 없음 |
| `X-RateLimit-Remaining: 0`, `Retry-After`, secondary rate limit 본문이 있는 403 또는 429 | `CLIENT_ERROR` | 요청 한도 초과 |
| rate-limit이 아닌 403 | `UNAUTHORIZED_REPO` | 웹훅 등록 관리자 권한 없음 |
| 그 밖의 4xx | `CLIENT_ERROR` | GitHub이 요청을 거절함 |
| 5xx, `Retry-After`가 있는 5xx | `SERVER_ERROR` | GitHub 또는 중간 서버 오류 |
| 연결·응답 시간 초과, 전송 실패, 본문 처리 실패 | `WEBHOOK_REGISTER_FAILED` | 웹훅 등록을 완료하지 못함 |

GitHub 응답 본문, OAuth access token, 웹훅 URL은 로그나 오류 메시지에 기록하지 않는다.

`Retry-After`만으로 rate limit을 단정하지 않는다. GitHub의 rate limit은 403·429로 처리하고, `503 + Retry-After`는 일시적 서버 오류로 분류한다.

### 클라이언트 시간 제한

GitHub 전용 `RestClient`에 연결 제한 2초와 응답 제한 5초를 둔다. 타임아웃은 GitHub 지연에서 요청 스레드와 생성 트랜잭션의 점유 시간을 제한하기 위한 것으로, 재시도는 추가하지 않는다. 생성 웹훅 요청은 멱등 키를 사용하지 않으므로 전송 성공 여부가 불명확한 경우 자동 재시도하면 중복 웹훅을 만들 수 있다.

웹훅 삭제도 같은 GitHub 클라이언트를 사용하므로 시간 제한은 적용하지만, 삭제 흐름의 호출 수와 동기 완료 계약은 변경하지 않는다.

### 관측과 성능 검증

이번 변경에는 Micrometer timer를 추가하지 않았다. 운영 메트릭 설계는 후속 범위이며, 레포지토리·사용자·토큰·웹훅 URL을 태그에 넣지 않는 원칙만 유지한다.

개선 전과 후에는 동일한 로컬 모사 서버에서 외부 HTTP 경로를 각 30회 비교했다. 실제 GitHub 저부하 확인은 고정 테스트 레포에서 각 50회 순차 실행했다. 기록 항목은 다음과 같다.

- 외부 HTTP 경로의 p50·p95 지연시간
- 생성 요청당 GitHub HTTP 요청 수
- 모사 응답 지연, 반복 횟수, 실행 명령과 원본 결과

모사 서버는 권한 조회와 웹훅 등록에 각각 동일한 고정 지연을 주며, 실제 GitHub API에 반복 부하를 주지 않는다. 실제 GitHub 확인은 생성한 webhook id만 즉시 삭제하고, callback URL prefix를 read-only 조회해 잔여 webhook을 확인한다. 두 측정 모두 채팅방 API 전체 지연시간이나 운영 성능을 주장하지 않는다.

## 테스트와 완료 조건

- GitHub URL이 `null`, 빈 문자열 또는 공백이면 웹훅 등록 호출 없이 `201` 흐름이 완료된다.
- 유효한 GitHub URL은 권한 조회 없이 웹훅 생성 요청 정확히 한 번을 실행하고, 응답의 `id`를 채팅방에 저장한다.
- 401, 403(권한), 403/429(rate limit), 404, 5xx, 타임아웃이 표의 오류 코드로 변환된다.
- 웹훅 등록 실패 시 GitHub 봇을 참가시키지 않고 채팅방 생성 트랜잭션이 성공으로 끝나지 않는다.
- 채팅방 삭제는 기존처럼 웹훅 삭제를 동기 호출한다.
- 기존 `ChatRoomServiceTest`와 GitHub 클라이언트 단위 테스트, 관련 백엔드 회귀 테스트가 통과한다. 추가·수정 테스트에는 한국어 `@DisplayName`을 작성한다.

## 영향 범위와 남은 위험

- HTTP 응답 DTO와 WebSocket destination은 바꾸지 않는다.
- 이 변경은 GitHub가 응답하는 시간 자체를 줄이지 않는다. 성공 경로의 불필요한 HTTP 왕복 한 번을 제거한다.
- 생성 트랜잭션은 웹훅 등록이 끝날 때까지 열려 있다. 비동기 등록, 재시도, 연동 상태 영속화와 고아 웹훅 정리는 이번 범위에서 다루지 않는다.
- GitHub의 403은 권한 부족과 rate limit에 모두 사용될 수 있으므로, `X-RateLimit-Remaining`, `Retry-After`, secondary rate limit 본문을 기준으로 구분하는 테스트가 필요하다.
