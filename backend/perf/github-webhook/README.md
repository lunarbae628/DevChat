# GitHub 웹훅 외부 호출 측정

이 디렉터리의 로컬 모사 측정은 GitHub 연동 채팅방 생성의 외부 호출 경로를 같은 조건에서 비교한다. 모사 측정은 실제 GitHub API, 운영 DB, 홈서버에 연결하지 않는다. 실제 GitHub 저부하 측정은 아래 별도 절의 고정 테스트 레포에서만 실행한다.

## 측정 범위

고정 지연의 루프백 모사 서버에서 다음 두 HTTP 시퀀스의 경과 시간을 기록한다.

```text
baseline  : GET /repos/owner/repo → POST /repos/owner/repo/hooks
optimized : POST /repos/owner/repo/hooks
```

이는 채팅방 API 전체의 p50, p95가 아니다. 채팅방 생성 안에서 동기로 기다리는 외부 HTTP 경로의 왕복 수와 지연만 비교한다.

## 실행

터미널 하나에서 모사 서버를 시작한다.

```bash
cd backend/perf/github-webhook
MOCK_GITHUB_DELAY_MS=100 python3 scripts/mock_github_server.py
```

다른 터미널에서 기준과 개선 시나리오를 실행한다.

```bash
cd backend/perf/github-webhook
MOCK_GITHUB_DELAY_MS=100 bash scripts/measure.sh baseline
MOCK_GITHUB_DELAY_MS=100 bash scripts/measure.sh optimized
```

두 시나리오는 워밍업 5회 뒤 30회를 측정한다. 결과 JSON은 `results/`에 생성되며 Git에서 제외된다. 각 결과의 `durations_ms`로 p50과 p95를 계산하고, `requests_per_iteration`이 기준 2회와 개선 1회인지 함께 확인한다.

## 검증

```bash
cd backend/perf/github-webhook
bash tests/measure_contract_test.sh
```

계약 테스트는 임의의 루프백 포트에서 모사 서버를 띄우고 두 시나리오를 실제 실행한다. baseline과 optimized 결과가 각각 30회이고 호출 수가 2회와 1회인지 검증한다.

## 실제 GitHub 저부하 측정

로컬 모사 측정과 별도로, 사용자가 지정한 `lunarbae628/devchatGithubApiTest`에서 실제 GitHub REST API를 저부하로 측정할 수 있다. 이 스크립트는 실제로 webhook을 생성하고 삭제한다. 명시적 확인 값, 고정 대상 레포, GitHub 토큰과 callback URL이 모두 있어야 실행되며, 다른 레포에서는 네트워크 요청 전에 종료한다.

```bash
GITHUB_TOKEN="$(gh auth token)" \
GITHUB_REPOSITORY=lunarbae628/devchatGithubApiTest \
CALLBACK_URL=https://example.com/devchat-github-api-perf \
LIVE_GITHUB_PERF_CONFIRM=I_UNDERSTAND_THIS_CREATES_WEBHOOKS \
bash backend/perf/github-webhook/scripts/measure_live_github.sh baseline

GITHUB_TOKEN="$(gh auth token)" \
GITHUB_REPOSITORY=lunarbae628/devchatGithubApiTest \
CALLBACK_URL=https://example.com/devchat-github-api-perf \
LIVE_GITHUB_PERF_CONFIRM=I_UNDERSTAND_THIS_CREATES_WEBHOOKS \
bash backend/perf/github-webhook/scripts/measure_live_github.sh optimized
```

- 각 시나리오는 50회를 순차 실행하며 생성 사이에 3초를 둔다.
- baseline은 `GET /repos/{owner}/{repo}` 뒤 webhook `POST`를, optimized는 webhook `POST`만 측정한다.
- 측정 시간에는 GET·POST의 `curl` 경과 시간만 포함하고, webhook DELETE와 3초 대기는 포함하지 않는다.
- 각 POST 응답의 id만 즉시 DELETE한다. DELETE가 실패하면 다음 webhook을 만들지 않고 중단하며, 실패 id와 부분 결과를 JSON에 남긴다.
- 결과 JSON에는 토큰, Authorization 헤더, GitHub 응답 본문을 기록하지 않는다.

2026-08-17 실제 GitHub 관측 결과는 다음과 같다. baseline과 optimized 모두 50회를 완료했고, cleanup 실패 id는 없었으며, 측정용 callback URL의 webhook이 테스트 레포에 남지 않았음을 API 조회로 확인했다.

| 시나리오 | 반복당 요청 수 | p50 | p95 |
| --- | ---: | ---: | ---: |
| baseline | 2 | 807.195ms | 884.977ms |
| optimized | 1 | 396.403ms | 548.605ms |

이 결과는 실제 GitHub 네트워크를 포함한 외부 HTTP 경로의 관측값이다. DevChat 인증, Controller, 데이터베이스, 채팅방 생성과 GitHub webhook 수신 처리를 포함하지 않으므로 채팅방 API 전체 성능이나 운영 SLO를 뜻하지 않는다. 원본 `live-github-*.json`은 `results/`에 로컬로만 남고 Git에서 제외된다.

### GitHub API 참조

- [GitHub REST API rate limits](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api): primary·secondary rate limit, 403·429, `Retry-After`
- [GitHub Create a repository webhook](https://docs.github.com/en/rest/repos/webhooks#create-a-repository-webhook): webhook 생성 요청과 응답 상태

## 해석 제한

- 지연은 `MOCK_GITHUB_DELAY_MS`로 고정한 모사값이다.
- 실제 GitHub 응답 시간, 네트워크 상태, DB 저장, 인증, Controller 직렬화와 동시 처리량은 포함하지 않는다.
- 이 결과는 중복 HTTP 왕복 제거 효과의 재현 근거일 뿐 운영 SLO나 전체 채팅방 생성 성능을 뜻하지 않는다.
