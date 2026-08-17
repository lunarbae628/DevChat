# GitHub 웹훅 동기 외부 호출 최적화

## 목적

GitHub 레포지토리를 연결한 채팅방 생성에서 웹훅 등록 완료를 성공 조건으로 유지하면서, 외부 HTTP 왕복을 줄였다.

## 문제와 설계 판단

기존 흐름은 채팅방 생성 중 GitHub에 관리자 권한을 조회한 뒤 웹훅을 생성했다.

```text
기존: GitHub URL 입력 → GET /repos/{owner}/{repo} → POST /repos/{owner}/{repo}/hooks → webhookId 저장 → 201
변경: GitHub URL 입력 → POST /repos/{owner}/{repo}/hooks → webhookId 저장 → 201
```

웹훅을 생성하는 `POST`가 GitHub 권한 검증과 생성 결과를 함께 제공하므로, 사전 `GET`은 같은 완료 계약에 필요한 별도 왕복이 아니었다. 따라서 권한 조회를 제거하고 웹훅 생성 성공 뒤에만 `webhookId`를 저장하는 순서는 유지했다.

비동기 전환은 이번 범위에서 채택하지 않았다. 현재 `POST /chat-rooms`의 `201 Created`는 GitHub 웹훅 등록과 `webhookId` 저장까지 완료됐다는 의미다. 비동기로 전환하면 201 이후 실패를 표현할 연동 상태, 재시도, 보상 처리와 사용자 노출 계약이 필요해 현재 API 계약이 달라진다.

`WebClient.block()`도 동기 호출로 동작할 수 있으나, 이 경로는 단일 요청을 완료까지 기다리는 명령형 흐름이다. Reactor 의존 없이 시간 제한과 상태 변환을 명시할 수 있는 `RestClient`를 사용했다.

## 사용자 오류 계약

`registerWebhook`은 GitHub 응답을 기존 `GitHubErrorCode`로 변환한다. 일반 권한 오류와 rate limit을 구분하는 이유는, 같은 GitHub 403이라도 사용자가 권한을 수정해야 하는 경우와 대기 후 재시도해야 하는 경우가 다르기 때문이다.

| GitHub 응답 또는 예외 | DevChat 오류 코드 | HTTP 상태 | 처리 의미 |
| --- | --- | --- | --- |
| 401 | `INVALID_TOKEN` | 401 | 액세스 토큰이 유효하지 않음 |
| 404 | `REPO_NOT_FOUND` | 404 | 레포지토리를 찾을 수 없거나 접근할 수 없음 |
| 일반 403 | `UNAUTHORIZED_REPO` | 401 | 웹훅 생성 권한 없음 |
| 403 + `X-RateLimit-Remaining: 0` | `CLIENT_ERROR` | 400 | primary rate limit |
| 403 + `Retry-After` 또는 `secondary rate limit` 본문 | `CLIENT_ERROR` | 400 | secondary rate limit |
| 429 | `CLIENT_ERROR` | 400 | rate limit |
| 5xx, `Retry-After` 포함 5xx | `SERVER_ERROR` | 500 | GitHub 또는 중간 서버 오류 |
| 연결 또는 읽기 시간 초과 | `WEBHOOK_REGISTER_FAILED` | 500 | 등록 성공 여부를 확정할 수 없는 전송 실패 |

연결 시간 제한은 2초, 읽기 시간 제한은 5초다. 전송 성공 여부가 불명확한 timeout에는 자동 재시도를 추가하지 않았다. 재시도하면 같은 채팅방에 중복 웹훅을 만들 수 있고, 이를 안전하게 처리할 idempotency 또는 보상 계약은 이번 변경 범위에 없다.

### 외부 응답 분류 참조

- [GitHub REST API rate limits](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api): primary rate limit과 secondary rate limit은 403 또는 429로 올 수 있으며, secondary rate limit에는 `Retry-After` 또는 오류 본문이 사용될 수 있음
- [GitHub Create a repository webhook](https://docs.github.com/en/rest/repos/webhooks#create-a-repository-webhook): 웹훅 생성 endpoint, 권한 요구사항과 201, 403, 404, 422 응답
- [RFC 9110, Retry-After](https://www.rfc-editor.org/rfc/rfc9110.html#section-10.2.3): `Retry-After`는 503에도 사용할 수 있는 HTTP 재시도 시점 힌트

따라서 `Retry-After`만으로 rate limit을 단정하지 않는다. GitHub 문서의 rate-limit 응답은 403·429로 처리하고, `503 + Retry-After`는 HTTP 표준에 따라 일시적 서버 오류로 분류한다.

## 변경 사항

- GitHub 웹훅 클라이언트를 `.block()` 기반 `WebClient`에서 동기 `RestClient`로 전환했다.
- 사전 관리자 권한 조회를 제거하고 웹훅 생성 `POST` 한 번으로 등록을 수행한다.
- 웹훅 생성 응답을 401, 403 권한 오류, rate limit 403과 429, 404, 5xx, 연결과 읽기 시간 초과로 구분해 기존 GitHub 오류 코드로 변환한다.
- GitHub 클라이언트에 연결 2초, 읽기 5초 제한을 적용했다.
- GitHub URL이 `null`, 빈 문자열 또는 공백인 채팅방 생성은 GitHub 호출과 GitHub 봇 참가 없이 완료되게 했다.
- 고정 지연의 루프백 GitHub 모사 서버와 외부 호출 경로 측정 스크립트를 추가했다.

## 영향 범위

- GitHub URL이 있는 채팅방 생성은 웹훅 등록에 성공한 뒤에만 기존과 같이 성공 응답을 반환한다.
- URL이 `null`, 빈 문자열 또는 공백인 일반 채팅방 생성은 외부 호출과 GitHub 봇 참가 없이 완료된다.
- 응답 DTO, WebSocket destination, 자동 재시도와 연동 상태 저장은 변경하지 않았다.
- 채팅방 삭제는 같은 동기 클라이언트와 시간 제한을 사용하며 기존 웹훅 삭제 오류 계약을 유지한다.

## 성능 측정 방법과 결과

실제 GitHub, 데이터베이스, 인증, Controller 직렬화는 측정하지 않았다. `127.0.0.1`에만 바인딩한 모사 서버가 각 GitHub 요청 전 100ms를 대기하도록 하고, 외부 HTTP 구간만 동일 조건으로 비교했다.

```text
baseline  : GET /repos/owner/repo → POST /repos/owner/repo/hooks
optimized : POST /repos/owner/repo/hooks
```

- 워밍업: 각 시나리오 5회
- 측정: 각 시나리오 30회
- 원본 결과: `backend/perf/github-webhook/results/baseline.json`, `backend/perf/github-webhook/results/optimized.json` (로컬 Git 제외 파일)
- 실행 도구와 재현 방법: `backend/perf/github-webhook/README.md`

| 시나리오 | 반복당 요청 수 | p50 | p95 |
| --- | ---: | ---: | ---: |
| baseline | 2 | 215.899ms | 222.338ms |
| optimized | 1 | 110.786ms | 110.875ms |

동일한 모사 지연 조건에서 호출 수가 2회에서 1회로 줄었고, 외부 호출 경로의 p50과 p95도 함께 감소했다. 이 결과는 중복 HTTP 왕복 제거의 재현 근거이며, 채팅방 API 전체 지연시간, 실제 GitHub 지연시간, 운영 SLO 또는 처리량 개선을 뜻하지 않는다.

## 실제 GitHub 저부하 관측

사용자가 지정한 `lunarbae628/devchatGithubApiTest`에서 실제 GitHub REST API를 대상으로 baseline과 optimized를 각각 50회 순차 실행했다. 두 시나리오는 같은 GitHub CLI 인증 토큰, 레포, callback URL, 3초 간격을 사용했다. baseline은 기존 호출 순서인 GET 뒤 POST를, optimized는 POST만 실행했다.

각 POST로 생성한 webhook id만 즉시 DELETE했다. DELETE 실패 시 다음 POST 없이 중단하도록 스크립트를 제한했으며, 최종 결과의 `cleanup_failed_webhook_ids`는 두 시나리오 모두 비어 있었다. GitHub API로 callback URL prefix를 조회해 테스트용 webhook이 0개 남은 것도 확인했다.

최종 결과 전에 실행한 baseline 50회는 생성·삭제 자체는 완료됐지만, 빈 cleanup 목록을 JSON으로 기록하는 셸 직렬화 결함으로 원본 결과를 저장하지 못해 측정값에서 제외했다. 해당 실행 뒤에도 callback URL prefix의 webhook은 0개였고, 빈 cleanup 목록의 결과 JSON을 만드는 회귀 테스트를 추가한 후 baseline을 처음부터 다시 측정했다.

| 시나리오 | 측정 시작(UTC) | 반복당 요청 수 | p50 | p95 |
| --- | --- | ---: | ---: | ---: |
| baseline | 2026-08-17T09:36:18Z | 2 | 807.195ms | 884.977ms |
| optimized | 2026-08-17T09:40:09Z | 1 | 396.403ms | 548.605ms |

원본 결과는 `backend/perf/github-webhook/results/live-github-baseline.json`, `backend/perf/github-webhook/results/live-github-optimized.json`에 로컬로만 남고 Git에서 제외된다. p95는 각 50개 관측값의 nearest-rank 95번째 백분위로 계산했다.

이 비교는 실제 GitHub 네트워크와 API 처리 시간을 포함하지만, DevChat 채팅방 생성 API 전체 측정은 아니다. 로컬 DevChat 실행 환경, OAuth 로그인, Controller, 데이터베이스, GitHub webhook 수신은 포함하지 않았다. 따라서 실제 GitHub 외부 HTTP 경로의 저부하 관측 근거로만 사용한다.

## 검증

- `bash backend/perf/github-webhook/tests/measure_contract_test.sh` 통과
- `./gradlew test --tests project.backend.domain.chat.chatroom.app.ChatRoomServiceTest --tests project.backend.domain.chat.github.GitHubClientTest --console=plain` 통과
- `GitHubClientTest`는 단일 POST, 401, 404, 일반 403, primary·secondary rate limit 403, 429, 5xx, `Retry-After`가 있는 5xx, timeout을 검증한다.
- `ChatRoomServiceTest`는 GitHub URL이 없는 `null` 입력에서 외부 등록과 GitHub 봇 참가를 호출하지 않는지 검증한다.
- `live_github_measure_guard_test.sh`는 명시적 실행 확인과 대상 레포 guard가 HTTP 전에 중단되는지, DELETE 실패 시 다음 POST 없이 부분 결과에 실패 id를 기록하는지, 빈 cleanup 목록에서도 50회 결과 JSON을 작성하는지 검증한다.
- 독립 코드 리뷰에서 secondary rate limit 403 오분류와 `Retry-After`가 있는 5xx 오분류를 발견했고, 각각 오류 본문 분기와 5xx 우선 분기 및 회귀 테스트로 보완했다.
- 전체 `./gradlew test --console=plain`은 Docker Desktop 소켓이 없어 Testcontainers 통합 테스트 6건이 초기화 단계에서 실패했다. 변경 대상 단위 테스트 실패는 없었다.

## 남은 리스크

- 측정값은 외부 HTTP 경로만 대상으로 하며 채팅방 API 전체 지연시간이나 운영 SLO를 의미하지 않는다.
- 채팅방 생성 트랜잭션은 웹훅 등록 완료까지 열린다.
- 전송 성공 여부가 불명확한 경우 중복 웹훅 생성 위험이 있어 자동 재시도를 추가하지 않았다.
