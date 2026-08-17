# GitHub 웹훅 동기 외부 호출 최적화 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** GitHub 연동 채팅방 생성의 완료 계약을 유지하면서 외부 HTTP 왕복을 두 번에서 한 번으로 줄이고, 오류 계약과 외부 호출 경로의 측정 근거를 남긴다.

**Architecture:** 채팅방 생성은 GitHub 웹훅 등록이 성공한 뒤에만 `201 Created`를 반환한다. GitHub 전용 동기 `RestClient`가 웹훅 생성 요청과 오류 변환을 담당하고, 서비스 계층은 사전 권한 조회 없이 등록 결과의 `webhookId`만 영속 엔티티에 반영한다. 성능 결과는 실제 GitHub가 아닌 고정 지연의 로컬 HTTP 모사 서버에서 구한다.

**Tech Stack:** Java 21, Spring Boot 3.4.5, Spring `RestClient`, Spring Test `MockRestServiceServer`, JUnit 5, Mockito

## Global Constraints

- `POST /chat-rooms`의 성공은 GitHub 웹훅 등록과 `webhookId` 저장 완료를 뜻하며 응답 DTO와 HTTP 상태는 변경하지 않는다.
- GitHub URL이 `null`, 빈 문자열 또는 공백이면 외부 호출과 GitHub 봇 참가를 하지 않는다.
- GitHub 액세스 토큰, 웹훅 URL, GitHub 오류 본문은 로그와 Micrometer 태그에 넣지 않는다.
- 웹훅 생성 요청에는 자동 재시도를 추가하지 않는다.
- 새 테스트에는 검증 의도를 설명하는 한국어 `@DisplayName`을 작성한다.
- 성능 수치는 고정 지연, 반복 횟수, JVM, 실행 명령과 원본 결과를 함께 남기며 운영 성능으로 일반화하지 않는다.

---

## File Structure

- Modify: `backend/src/main/java/project/backend/domain/chat/github/GitHubClient.java` — `RestClient` 기반 GitHub 웹훅 생성, 상태별 오류 변환, 연결 및 읽기 시간 제한
- Modify: `backend/src/main/java/project/backend/domain/chat/github/app/GitMessageService.java` — 중복 권한 조회 제거, 등록 성공 뒤 `webhookId` 저장 유지
- Modify: `backend/src/main/java/project/backend/domain/chat/chatroom/app/ChatRoomService.java` — GitHub URL의 `null`과 공백을 같은 미연동 입력으로 처리
- Modify: `backend/src/test/java/project/backend/domain/chat/chatroom/app/ChatRoomServiceTest.java` — 미연동 URL 분기와 등록 성공 계약 회귀 테스트
- Modify: `.gitignore` — GitHub 외부 호출 측정의 로컬 JSON 결과 제외
- Create: `backend/src/test/java/project/backend/domain/chat/github/GitHubClientTest.java` — 실제 `RestClient` 요청 경로와 GitHub 상태 응답 변환 테스트
- Create: `backend/perf/github-webhook/README.md` — 고정 지연 모사 서버, 워밍업, 반복 조건, 결과 해석과 원본 결과 위치
- Create: `backend/perf/github-webhook/results/.gitkeep` — 결과 디렉터리 추적
- Create: `backend/perf/github-webhook/scripts/measure.sh` — 기준/개선 버전의 GitHub 호출 시퀀스를 같은 로컬 모사 서버에 반복 요청하고 원본 JSON을 생성
- Create: `backend/perf/github-webhook/scripts/mock_github_server.py` — `GET /repos/{owner}/{repo}`와 `POST /repos/{owner}/{repo}/hooks`에 같은 지연을 주는 로컬 전용 모사 서버
- Create: `backend/perf/github-webhook/tests/measure_contract_test.sh` — 측정 스크립트가 루프백 주소, 30회 반복, 워밍업, 두 JSON 결과를 강제하는 계약 테스트

## Task 1: 기준 측정 도구와 안전 경계

**Files:**
- Create: `backend/perf/github-webhook/scripts/mock_github_server.py`
- Create: `backend/perf/github-webhook/scripts/measure.sh`
- Create: `backend/perf/github-webhook/tests/measure_contract_test.sh`
- Create: `backend/perf/github-webhook/README.md`
- Modify: `.gitignore`
- Create: `backend/perf/github-webhook/results/.gitkeep`

**Interfaces:**
- Consumes: 기준 커밋 또는 작업 트리의 GitHub 클라이언트 실행 경로
- Produces: `backend/perf/github-webhook/results/baseline.json`, `backend/perf/github-webhook/results/optimized.json`의 반복별 외부 호출 duration과 HTTP 요청 수

- [x] **Step 1: 측정 계약 테스트를 작성한다**

`measure_contract_test.sh`는 모사 서버를 시작한 뒤 `measure.sh baseline`과 `measure.sh optimized`를 각각 실행한다. Python 표준 `json` 모듈로 두 결과 파일을 읽어 아래를 검사한다.

```bash
python3 - "$baseline" "$optimized" <<'PY'
import json, sys
baseline, optimized = (json.load(open(path)) for path in sys.argv[1:])
assert len(baseline["durations_ms"]) == 30
assert len(optimized["durations_ms"]) == 30
assert set(baseline["requests_per_iteration"]) == {2}
assert set(optimized["requests_per_iteration"]) == {1}
PY
```

이 테스트는 실제 실행 결과에서 왕복 수, 반복 횟수와 결과 JSON 계약이 깨지는 회귀를 잡는다.

- [x] **Step 2: 계약 테스트가 실패하는지 확인한다**

Run: `bash backend/perf/github-webhook/tests/measure_contract_test.sh`

Expected: FAIL because the measurement script does not exist.

- [x] **Step 3: 루프백 전용 모사 서버와 측정 스크립트를 작성한다**

`mock_github_server.py`는 `127.0.0.1`에서만 listen하고 환경변수 `MOCK_GITHUB_DELAY_MS`만큼 각 요청 전에 대기한다. `GET /repos/owner/repo`는 admin 권한 JSON을, `POST /repos/owner/repo/hooks`는 고정 webhook id JSON을 반환한다.

`measure.sh`는 `WARMUP=5`, `ITERATIONS=30`을 고정하고 두 시나리오를 같은 서버와 같은 지연으로 실행한다. 스크립트는 `curl --fail --silent --show-error`만 사용하며, base URL은 `http://127.0.0.1:<검증된 임의 포트>` 외 값을 허용하지 않는다.

```text
baseline  : GET /repos/owner/repo → POST /repos/owner/repo/hooks
optimized : POST /repos/owner/repo/hooks
```

각 반복의 경과 시간과 호출 수를 JSON 배열로 기록한다. `.gitignore`에는 `/backend/perf/github-webhook/results/*.json`을 추가하고, 결과 디렉터리는 기존 `perf/query-analysis/results/.gitkeep` 방식처럼 `.gitkeep`만 추적한다.

- [x] **Step 4: 측정 계약 테스트를 통과시킨다**

Run: `bash backend/perf/github-webhook/tests/measure_contract_test.sh`

Expected: PASS.

- [x] **Step 5: 기준 결과를 수집한다**

Run: `MOCK_GITHUB_DELAY_MS=100 bash backend/perf/github-webhook/scripts/measure.sh baseline`

Expected: `results/baseline.json` with 30 measured iterations, two requests per iteration, and no outbound request.

- [x] **Step 6: 기준 측정 결과를 검토한다**

`baseline.json`의 `requests_per_iteration`이 모두 `2`인지 확인하고, p50과 p95는 원본 JSON에서 계산해 작업 기록에만 보관한다. 이 수치는 채팅방 API 전체가 아니라 코드 변경 전의 **외부 호출 경로** 모사 환경 기준임을 명시한다.

## Task 2: GitHub 웹훅 생성 클라이언트 계약을 테스트로 고정

**Files:**
- Create: `backend/src/test/java/project/backend/domain/chat/github/GitHubClientTest.java`

**Interfaces:**
- Consumes: `GitHubClient.registerWebhook(String accessToken, String owner, String repo, String webhookUrl)`
- Produces: webhook id 또는 `GitHubException`의 `GitHubErrorCode`

- [x] **Step 1: 실패하는 성공 경로 테스트를 작성한다**

`MockRestServiceServer.bindTo(RestClient.builder())`로 만든 실제 `RestClient`를 `GitHubClient`에 주입한다. 다음 요청 하나만 기대한다.

```java
server.expect(requestTo("https://api.github.com/repos/team/repo/hooks"))
    .andExpect(method(HttpMethod.POST))
    .andExpect(header("Authorization", "Bearer token"))
    .andExpect(jsonPath("$.events[0]").value("issues"))
    .andRespond(withSuccess("{\"id\":42}", MediaType.APPLICATION_JSON));

assertThat(client.registerWebhook("token", "team", "repo", "https://devchat.test/github/7"))
    .isEqualTo(42L);
server.verify();
```

이 테스트는 사전 권한 `GET`이 다시 들어오거나 webhook id가 저장되지 않는 회귀를 잡는다.

- [x] **Step 2: 실패를 확인한다**

Run: `cd backend && ./gradlew test --tests project.backend.domain.chat.github.GitHubClientTest --console=plain`

Expected: FAIL because the current constructor requires `WebClient.Builder` and the `RestClient` path does not exist.

- [x] **Step 3: 실패하는 오류 변환 테스트를 작성한다**

각 테스트는 독립된 `MockRestServiceServer`와 완전한 응답을 사용한다.

```java
assertThatThrownBy(() -> client.registerWebhook("token", "team", "repo", webhookUrl))
    .isInstanceOf(GitHubException.class)
    .extracting(error -> ((GitHubException) error).getErrorCode())
    .isEqualTo(GitHubErrorCode.UNAUTHORIZED_REPO);
```

검증할 응답은 401, 404, `X-RateLimit-Remaining: 0`이 있는 403, 헤더 없는 403, 429, 500이다. 401은 `INVALID_TOKEN`, 404는 `REPO_NOT_FOUND`, rate limit 403과 429는 `CLIENT_ERROR`, 일반 403은 `UNAUTHORIZED_REPO`, 500은 `SERVER_ERROR`여야 한다.

- [x] **Step 4: 실제 연결 시간 초과 테스트를 작성한다**

테스트 전용 `HttpServer`가 `127.0.0.1`에서 요청 수신 후 6초 동안 응답하지 않게 한다. 5초 읽기 시간 제한을 갖는 `RestClient`로 호출해 `WEBHOOK_REGISTER_FAILED`가 되는지 검증한다. 서버와 executor 종료는 `@AfterEach`에서 수행한다.

- [x] **Step 5: 모든 새 테스트가 실패하는지 확인한다**

Run: `cd backend && ./gradlew test --tests project.backend.domain.chat.github.GitHubClientTest --console=plain`

Expected: FAIL due to missing `RestClient` construction and missing status mapping, not assertion setup errors.

## Task 3: 최소 동기 RestClient 구현

**Files:**
- Modify: `backend/src/main/java/project/backend/domain/chat/github/GitHubClient.java`

**Interfaces:**
- Consumes: access token, repository owner/name, webhook URL
- Produces: GitHub webhook id or documented `GitHubException`

- [x] **Step 1: GitHubClient를 RestClient 기반으로 바꾼다**

`WebClient.Builder`, Reactor import와 `validateAdminPermission` 메서드를 제거한다. 운영 생성자는 아래 전용 클라이언트를 한 번 만들고, package-private 생성자는 테스트에서 `RestClient`를 주입할 수 있게 한다.

```java
private static RestClient createRestClient() {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(Duration.ofSeconds(2));
    requestFactory.setReadTimeout(Duration.ofSeconds(5));
    return RestClient.builder().requestFactory(requestFactory).build();
}
```

`registerWebhook`은 `POST /repos/{owner}/{repo}/hooks`만 실행하고 `retrieve().onStatus(...)`에서 Task 2의 상태 매핑을 적용한다. 응답 body의 `id`가 없거나 숫자가 아니면 `WEBHOOK_REGISTER_FAILED`를 던진다. `GitHubException`은 다시 감싸지 않는다.

- [x] **Step 2: GitHub 클라이언트 테스트를 통과시킨다**

Run: `cd backend && ./gradlew test --tests project.backend.domain.chat.github.GitHubClientTest --console=plain`

Expected: PASS.

- [x] **Step 3: 삭제 호출의 동기 계약을 보존한다**

`deleteWebhook`은 같은 `RestClient`에서 `DELETE /repos/{owner}/{repo}/hooks/{id}`를 실행하고, 실패를 기존 `WEBHOOK_DELETE_FAILED`로 변환한다. 자동 재시도와 비동기 실행은 추가하지 않는다.

- [x] **Step 4: GitHub 클라이언트 테스트를 다시 실행한다**

Run: `cd backend && ./gradlew test --tests project.backend.domain.chat.github.GitHubClientTest --console=plain`

Expected: PASS.

## Task 4: 채팅방 생성 계약을 최소 변경으로 연결

**Files:**
- Modify: `backend/src/main/java/project/backend/domain/chat/github/app/GitMessageService.java`
- Modify: `backend/src/main/java/project/backend/domain/chat/chatroom/app/ChatRoomService.java`
- Modify: `backend/src/test/java/project/backend/domain/chat/chatroom/app/ChatRoomServiceTest.java`

**Interfaces:**
- Consumes: `ChatRoomRequest.repositoryUrl`, `GitMessageService.registerWebhook`
- Produces: GitHub URL이 있을 때만 동기 웹훅 등록 후 `201` 흐름, 미연동 입력이면 외부 호출 없는 생성 흐름

- [x] **Step 1: null URL 회귀 테스트를 작성한다**

기존 `createChatRoom_noRepository_success`와 별도로 `repositoryUrl`이 `null`일 때 `createChatRoom`이 성공하고 `gitMessageService.registerWebhook`과 `memberService.getMemberByUsername("github-bot")`를 호출하지 않는 테스트를 작성한다.

- [x] **Step 2: 실패를 확인한다**

Run: `cd backend && ./gradlew test --tests project.backend.domain.chat.chatroom.app.ChatRoomServiceTest --console=plain`

Expected: FAIL with `NullPointerException` from `repositoryUrl.isBlank()`.

- [x] **Step 3: 사전 권한 조회를 제거하고 null-safe 분기를 구현한다**

`GitMessageService.registerWebhook`에서 `gitHubClient.validateAdminPermission(...)` 호출을 제거한다. `ChatRoomService`에서는 `StringUtils.hasText(request.getRepositoryUrl())`로 GitHub 연동 여부를 판단한다. URL이 있으면 현재 순서대로 웹훅 등록 성공 뒤 GitHub 봇을 참가시킨다.

- [x] **Step 4: 채팅방 생성 테스트를 통과시킨다**

Run: `cd backend && ./gradlew test --tests project.backend.domain.chat.chatroom.app.ChatRoomServiceTest --console=plain`

Expected: PASS. URL 없는 입력은 외부 호출이 없고, URL 있는 입력은 웹훅 등록 성공 뒤에만 GitHub 봇을 참가시킨다.

- [x] **Step 5: 관련 전체 테스트를 실행한다**

Run: `cd backend && ./gradlew test --tests project.backend.domain.chat.chatroom.app.ChatRoomServiceTest --tests project.backend.domain.chat.github.GitHubClientTest --console=plain`

Expected: PASS.

## Task 5: 개선 후 동일 조건 측정과 기록

**Files:**
- Modify: `backend/perf/github-webhook/README.md`
- Modify: `docs/knowledge/changes/2026-08-17-github-webhook-sync-optimization.md`

**Interfaces:**
- Consumes: Task 1의 고정 지연 모사 서버와 원본 JSON
- Produces: 모사 환경의 외부 호출 수, p50, p95, 제한 사항이 포함된 사실 기반 지식 기록

- [x] **Step 1: 개선 후 측정을 실행한다**

Run: `MOCK_GITHUB_DELAY_MS=100 bash backend/perf/github-webhook/scripts/measure.sh optimized`

Expected: `results/optimized.json` with 30 measured iterations and one request per iteration.

- [x] **Step 2: 결과를 비교한다**

동일 `MOCK_GITHUB_DELAY_MS`, 워밍업 5회, 측정 30회인지 확인한다. 두 원본 JSON에서 p50과 p95를 계산하고, `baseline`의 호출 수가 2이고 `optimized`의 호출 수가 1인지 확인한다.

- [x] **Step 3: 지식 기록을 작성한다**

지식 기록에 `목적`, `변경 사항`, `영향 범위`, `검증`, `남은 리스크`를 쓴다. 검증에는 실제 명령, 고정 지연, 반복 횟수, 호출 수, p50, p95와 원본 JSON 경로를 기록한다. 이 측정은 채팅방 API 전체가 아닌 외부 호출 경로의 고정 지연 모사 결과임을 명시한다. `RestClient` 전환만으로 성능이 좋아졌다고 쓰지 않고 중복 HTTP 왕복 제거의 결과로 한정한다.

- [ ] **Step 4: 전체 백엔드 회귀 테스트를 실행한다**

Run: `cd backend && ./gradlew test --console=plain`

Expected: PASS.

- [ ] **Step 5: 문서와 변경 범위를 검토한다**

Run: `git diff --check && git status --short && git diff --stat`

Expected: GitHub 외부 호출 최적화, 테스트, 성능 측정 도구와 지식 기록만 변경되어야 한다.

## Plan Self-Review

- 설계의 동기 완료 계약, 미연동 URL, 오류 변환, 타임아웃, 호출 수와 측정 근거를 각각 Task 2~5에서 다룬다.
- 측정 계약 테스트는 스크립트를 실제로 실행하고 결과 JSON을 검증한다. 소스 텍스트만 검사하지 않는다.
- GitHub API 요청 payload와 오류 상태는 `MockRestServiceServer`의 실제 `RestClient` 요청으로 검증하며, timeout은 실제 루프백 HTTP 서버로 검증한다.
- API DTO, WebSocket destination, 재시도, 연동 상태 영속화, 자동 복구는 범위에서 제외한다.
