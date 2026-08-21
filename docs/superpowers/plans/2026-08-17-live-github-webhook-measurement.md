# 실제 GitHub 웹훅 저부하 측정 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `lunarbae628/devchatGithubApiTest`에서 기존 GET→POST 경로와 개선 POST 경로를 각각 50회씩 실제 GitHub API에 순차 실행하고, 생성한 웹훅을 즉시 삭제한 원본 결과와 해석 범위를 남긴다.

**Architecture:** 측정 스크립트는 GitHub CLI의 인증 토큰을 인자로 받아 실제 REST 요청을 직접 보낸다. baseline은 기존 `validateAdminPermission`의 GET 뒤 웹훅 POST를, optimized는 웹훅 POST만 실행한다. 생성 시간만 결과에 기록하고, 성공한 POST의 webhook id는 즉시 DELETE로 정리한다. 스크립트는 지정된 테스트 레포와 명시적 실행 확인 값이 아니면 네트워크 요청 전에 종료한다.

**Tech Stack:** Bash, curl, Python 3 표준 라이브러리, GitHub REST API, GitHub CLI

## Global Constraints

- 대상 레포는 사용자가 지정한 `lunarbae628/devchatGithubApiTest`로 고정한다.
- 실행 전 `LIVE_GITHUB_PERF_CONFIRM=I_UNDERSTAND_THIS_CREATES_WEBHOOKS`를 요구한다.
- baseline과 optimized는 각각 50회, 순차 실행하며 웹훅 생성 사이 최소 3초를 둔다.
- 측정 시간은 GET·POST만 포함하고 cleanup DELETE와 대기 시간은 제외한다.
- 생성한 webhook id만 삭제하며, 기존 웹훅은 삭제하지 않는다. 측정 후 잔여 확인은 callback URL prefix로 한정한 read-only 조회만 허용한다.
- 토큰, Authorization 헤더, 응답 본문은 결과 JSON·로그·Git 추적 파일에 기록하지 않는다.
- 결과는 실제 GitHub 외부 호출의 저표본 비교로 해석하며 채팅방 API 전체 성능이나 운영 SLO로 일반화하지 않는다.
- commit과 push는 사용자 지시가 있을 때만 수행한다.

---

## Task 1: 실행 안전장치 계약

**Files:**
- Create: `backend/perf/github-webhook/tests/live_github_measure_guard_test.sh`
- Create: `backend/perf/github-webhook/scripts/measure_live_github.sh`

**Interfaces:**
- Consumes: `GITHUB_TOKEN`, `GITHUB_REPOSITORY`, `CALLBACK_URL`, `LIVE_GITHUB_PERF_CONFIRM`, scenario (`baseline` 또는 `optimized`)
- Produces: 확인 값 또는 대상 레포가 틀리면 HTTP 요청 없이 non-zero 종료

- [x] **Step 1: 실패하는 guard 테스트를 작성한다**

```bash
GITHUB_TOKEN=dummy \
GITHUB_REPOSITORY=someone/else \
CALLBACK_URL=https://example.com \
LIVE_GITHUB_PERF_CONFIRM=I_UNDERSTAND_THIS_CREATES_WEBHOOKS \
bash backend/perf/github-webhook/scripts/measure_live_github.sh optimized
```

테스트는 스크립트가 없어서 실패한 뒤, 구현 후에는 대상 레포 오류로 종료되고 HTTP를 시도하지 않는지 검증한다. 확인 값이 없는 경우도 같은 방식으로 검증한다.

- [x] **Step 2: guard 테스트의 실패를 확인한다**

Run: `bash backend/perf/github-webhook/tests/live_github_measure_guard_test.sh`

Expected: FAIL because `measure_live_github.sh` does not exist.

- [x] **Step 3: 최소 실행 안전장치를 구현한다**

`measure_live_github.sh`는 scenario를 검증하고, `GITHUB_TOKEN`, `CALLBACK_URL`, 고정 대상 레포, 명시적 확인 값을 검사한다. 모든 검사가 끝나기 전에는 `curl`을 호출하지 않는다.

- [x] **Step 4: guard 테스트를 통과시킨다**

Run: `bash backend/perf/github-webhook/tests/live_github_measure_guard_test.sh`

Expected: PASS.

## Task 2: 실제 GitHub 호출과 cleanup 측정

**Files:**
- Modify: `backend/perf/github-webhook/scripts/measure_live_github.sh`

**Interfaces:**
- Consumes: 검증된 환경변수와 `baseline` 또는 `optimized`
- Produces: Git 제외 `backend/perf/github-webhook/results/live-github-<scenario>.json`

- [x] **Step 1: baseline과 optimized 호출 순서를 구현한다**

`baseline`은 `GET /repos/lunarbae628/devchatGithubApiTest` 뒤 `POST /repos/lunarbae628/devchatGithubApiTest/hooks`를 실행한다. `optimized`는 POST만 실행한다. POST payload는 DevChat의 `name=web`, `active=true`, `issues`, `pull_request`, `pull_request_review`, `content_type=json`, `insecure_ssl=0`을 사용한다.

- [x] **Step 2: 생성된 webhook만 정리하도록 구현한다**

POST 응답의 `id`만 DELETE `/repos/lunarbae628/devchatGithubApiTest/hooks/{id}`로 정리한다. DELETE 실패 id는 결과 JSON의 `cleanup_failed_webhook_ids`에만 저장하고, 기존 웹훅은 삭제하지 않는다. 측정 뒤에는 callback URL prefix를 한정한 read-only 조회로 잔여 webhook만 확인한다. 인터럽트 시에도 현재 생성 id를 정리하도록 trap을 둔다.

- [x] **Step 3: 결과 JSON을 구현한다**

각 결과에는 scenario, repository, started_at_utc, planned_iterations=50, 실제 완료 수인 measured_iterations, interval_seconds=3, durations_ms, requests_per_iteration, cleanup_failed_webhook_ids를 기록한다. `durations_ms`에는 GET·POST의 curl 경과 시간 합만 넣고, DELETE와 sleep은 넣지 않는다.

- [x] **Step 4: 실제 GitHub baseline을 실행한다**

Run: `GITHUB_TOKEN="$(gh auth token)" GITHUB_REPOSITORY=lunarbae628/devchatGithubApiTest CALLBACK_URL=https://example.com/devchat-github-api-perf LIVE_GITHUB_PERF_CONFIRM=I_UNDERSTAND_THIS_CREATES_WEBHOOKS bash backend/perf/github-webhook/scripts/measure_live_github.sh baseline`

Expected: 50회, 각 반복 2회 요청, cleanup 실패 id 없음.

- [x] **Step 5: 실제 GitHub optimized를 실행한다**

Run: `GITHUB_TOKEN="$(gh auth token)" GITHUB_REPOSITORY=lunarbae628/devchatGithubApiTest CALLBACK_URL=https://example.com/devchat-github-api-perf LIVE_GITHUB_PERF_CONFIRM=I_UNDERSTAND_THIS_CREATES_WEBHOOKS bash backend/perf/github-webhook/scripts/measure_live_github.sh optimized`

Expected: 50회, 각 반복 1회 요청, cleanup 실패 id 없음.

## Task 3: 결과 검증과 문서화

**Files:**
- Modify: `backend/perf/github-webhook/README.md`
- Modify: `docs/knowledge/changes/2026-08-17-github-webhook-sync-optimization.md`

**Interfaces:**
- Consumes: `live-github-baseline.json`, `live-github-optimized.json`
- Produces: 실제 GitHub 측정 조건, p50·p95, cleanup 결과와 해석 제한

- [x] **Step 1: 원본 결과를 검사한다**

두 JSON의 measured_iterations가 50인지, `requests_per_iteration`이 baseline `{2}`, optimized `{1}`인지, cleanup 실패 id가 비어 있는지 확인한다. p50과 p95는 각 `durations_ms` 정렬 배열의 중앙값과 nearest-rank 95번째 백분위로 계산한다.

- [x] **Step 2: README에 실제 GitHub 측정의 실행 조건과 안전장치를 작성한다**

테스트 레포, 3초 간격, 생성·삭제 부작용, 결과 Git 제외, mock 측정과의 범위 차이를 명시한다. 토큰을 문서에 넣지 않는다.

- [x] **Step 3: 지식 기록에 실제 GitHub 관측값을 추가한다**

50회씩의 호출 수, p50, p95, 측정 시각, cleanup 결과를 기록한다. 이 결과가 DevChat 채팅방 API 전체 측정이 아니라 GitHub 외부 HTTP 경로의 실제 네트워크 관측이라는 제한을 반복한다.

- [x] **Step 4: 검토와 정적 검증을 수행한다**

Run: `bash backend/perf/github-webhook/tests/live_github_measure_guard_test.sh && git diff --check && git status --short`

Expected: guard 테스트와 whitespace 검증 통과, 측정 JSON은 Git 제외.

## Plan Self-Review

- 실제 GitHub의 외부 상태 변경은 고정 테스트 레포와 명시적 확인 값으로 제한한다.
- baseline·optimized 모두 같은 token, repository, callback URL, 반복 횟수와 간격을 사용한다.
- DELETE 실패를 숨기지 않고 결과에 남기며, 생성하지 않은 웹훅은 절대 정리하지 않는다.
- mock 결과와 실제 GitHub 결과의 측정 범위를 분리한다.
