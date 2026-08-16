# DevChat Notification Delivery Observability Design

> **상태: draft.** 검토용 설계 문서이며 현재 운영 기준이나 RAG 검색 대상이 아니다.

## 현재 문제

알림 흐름마다 DB 저장과 WebSocket 전송 경계가 다르다.

- 친구 알림은 DB 저장 후 `AFTER_COMMIT` 비동기 WebSocket 전송을 사용한다.
- 커뮤니티 지원 알림은 DB 저장과 실시간 전송이 같은 listener 흐름에 있다.
- DM은 메시지는 저장하지만 실시간 알림 DTO 자체는 영속 알림함에 저장하지 않는다.

`SimpMessagingTemplate.convertAndSend`가 예외 없이 끝났다는 사실은 서버의 전송 시도 성공만 뜻하며 브라우저 수신을 보장하지 않는다.

## 1차 권장안

Outbox를 바로 도입하지 않고 다음 경계를 통일한다.

```text
알림 DB 저장 → transaction commit → 비동기 실시간 전송
                                      ├─ 성공: 성공 counter
                                      └─ 예외: 구조화 로그 + 실패 counter + Slack
```

- Slack은 자동 복구가 아니라 운영자 탐지 수단이다.
- 알림 본문, 토큰과 개인정보는 Slack payload에 넣지 않는다.
- event type, 내부 trace/event ID, 익명화된 receiver 식별자, 예외 종류와 발생 시각만 보낸다.
- 같은 실패가 폭주할 때 Slack 자체가 장애를 키우지 않도록 집계 또는 rate limit을 둔다.
- Slack webhook 실패는 애플리케이션 트랜잭션과 별도로 기록한다.

DB 알림함이 source of truth이면 사용자는 재접속 후 알림을 조회할 수 있다. DM 실시간 알림도 유실 시 복구가 필요하다면 먼저 영속 알림함에 포함할지 결정한다.

## Outbox 보류 근거

다음 요구가 실제로 확인되면 Outbox를 재검토한다.

- 서버 재시작 후에도 미전송 이벤트를 자동 복구해야 함
- 반복 실패에 자동 재시도와 backoff가 필요함
- 처리 상태, 재시도 횟수와 멱등성을 영속 관리해야 함
- 운영 데이터에서 Slack 탐지만으로 복구 비용이 큼

Outbox 도입 시 DB row와 relay의 성공을 브라우저 exactly-once 수신으로 표현하지 않는다.

## 검증

- 커밋 성공 후 WebSocket 전송 예외를 강제로 발생시켜 DB 알림이 남는지 확인
- 비동기 예외가 구조화 로그, counter와 Slack adapter까지 전달되는지 확인
- Slack adapter 실패가 사용자 트랜잭션을 되돌리지 않는지 확인
- 알림 내용과 개인정보가 운영 메시지에 포함되지 않는지 확인
- 동일 실패 폭주 시 알림 제한이 동작하는지 확인

이 설계 승인 후 현재 알림 흐름을 하나씩 추적하고 별도 구현 계획을 작성한다.
