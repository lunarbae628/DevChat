# Notification Delivery Failure Handling Implementation Plan

> **상태:** 구현 완료. 이 문서는 실제 구현과 검증 범위를 반영한 계획 기록이다. 사실 기반 결과는 `docs/knowledge/changes/2026-08-14-notification-delivery-failure-handling.md`를 참고한다.

**Goal:** 친구·스터디의 영속 알림은 업무 트랜잭션이 커밋된 뒤에만 WebSocket으로 전달한다. 전달·큐 거절 실패는 Micrometer와 Discord webhook으로 관측하되, 사용자 업무를 롤백하지 않는다.

**Architecture:** 친구·스터디 흐름은 트랜잭션 안에서 `Notification`을 저장하고 `NotificationDto` 이벤트를 발행한다. `AFTER_COMMIT` listener가 전달 작업을 전용 bounded executor에 등록한다. 전송 성공·실패·큐 거절은 metric으로 기록하고, 실패 경보는 별도 executor가 Discord webhook으로 보낸다.

**Tech Stack:** Spring Boot 3.4, Spring transactions/events, `ThreadPoolTaskExecutor`, Micrometer/Prometheus, Discord webhook, JUnit 5, Mockito.

## Global Constraints

- 친구·스터디 알림만 변경한다. DM, Outbox, Netty·JVM 튜닝은 범위 밖이다.
- `Notification`은 원래 업무 트랜잭션 안에서 저장한다. `AFTER_COMMIT` listener에서 알림 데이터를 저장하지 않는다.
- WebSocket 전달을 자동 재시도하지 않는다. 영속 알림함을 사용자 복구 경로로 둔다.
- metric 태그, 로그, Discord payload에 알림 본문·사용자명·토큰·알림 ID를 넣지 않는다.
- executor 크기·큐 용량·Discord 쿨다운의 기본값은 `application.yml`에만 둔다. webhook은 기본적으로 비활성화한다.
- 새 테스트와 수정 테스트에는 검증 의도를 나타내는 한국어 `@DisplayName`을 작성한다.

---

### Task 1: 전달·경보 executor와 설정 검증

**Files:**

- Create: `backend/src/main/java/project/backend/global/config/async/NotificationDeliveryProperties.java`
- Modify: `backend/src/main/java/project/backend/global/config/async/AsyncConfig.java`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/project/backend/global/config/async/NotificationDeliveryPropertiesTest.java`

**Interfaces:**

- `NotificationDeliveryProperties`는 executor 값과 Discord webhook·쿨다운을 불변 record로 바인딩한다.
- `notificationDeliveryExecutor`는 전달 작업을 처리하고, 포화 시 호출자 실행 대신 거절한다.
- `notificationAlertExecutor`는 Discord HTTP 요청만 처리한다.

- [x] properties 바인딩·누락값·1 미만 크기·`max-pool-size < core-pool-size` 검증 테스트를 추가한다.
- [x] 전달 executor와 alert executor를 추가한다.
- [x] `application.yml`에 기본값과 `NOTIFICATION_DELIVERY_DISCORD_WEBHOOK_URL` 바인딩을 추가한다.

### Task 2: 커밋 후 전달과 실패 관측

**Files:**

- Create: `backend/src/main/java/project/backend/domain/notification/listener/DiscordNotificationDeliveryAlert.java`
- Modify: `backend/src/main/java/project/backend/domain/notification/listener/NotificationEventListener.java`
- Test: `backend/src/test/java/project/backend/domain/notification/listener/NotificationEventListenerTest.java`
- Test: `backend/src/test/java/project/backend/domain/notification/listener/DiscordNotificationDeliveryAlertTest.java`

**Interfaces:**

- `NotificationEventListener`는 `NotificationDto`를 `AFTER_COMMIT` 뒤 전달 executor에 등록한다.
- `notification_delivery_total`은 `result`(`success`, `failure`, `rejected`)와 `type` 태그를 기록한다.
- Discord 경보는 알림 종류·실패 단계·예외 클래스·시각만 포함하고, 인스턴스별 5분 쿨다운을 적용한다.

- [x] WebSocket 전송 성공, 전송 예외, 전달 executor 거절, alert executor 거절을 검증한다.
- [x] Discord HTTP 호출의 2초 연결·응답 타임아웃과 자체 실패 격리를 구현한다.
- [x] Discord 경보를 별도 executor에서 실행해 전달 경로와 분리한다.

### Task 3: 스터디 알림을 공통 전달 경로로 통일

**Files:**

- Modify: `backend/src/main/java/project/backend/domain/community/listener/ApplicantEventListener.java`
- Test: `backend/src/test/java/project/backend/domain/community/listener/ApplicantEventListenerTest.java`

**Interfaces:**

- `ApplyEvent`, `ApplicantResultEvent`마다 `Notification` 하나를 저장하고 `NotificationDto` 이벤트 하나를 발행한다.
- `ApplicantEventListener`의 직접 `SimpMessagingTemplate` 호출을 제거한다.

- [x] 스터디 신청·처리 알림이 저장 뒤 이벤트를 발행하도록 변경한다.
- [x] listener가 WebSocket을 직접 의존하지 않는지 검증한다.

### Task 4: 커밋 경계·회귀 검증·기록

**Files:**

- Create: `backend/src/test/java/project/backend/domain/notification/listener/NotificationDeliveryAfterCommitIntegrationTest.java`
- Create: `docs/knowledge/changes/2026-08-14-notification-delivery-failure-handling.md`
- Modify: `ai/rag/corpus.json`

- [x] 통합 테스트로 커밋 전 미전송과 커밋 뒤 `NotificationDelivery-` executor 전송을 검증한다.
- [x] Docker 비의존 관련 테스트 22건을 실행한다.
- [x] 변경 기록을 `active` RAG corpus에 등록하고 로컬 index를 재생성한다.
- [ ] Docker를 사용할 수 있는 환경에서 `NotificationDeliveryAfterCommitIntegrationTest`와 전체 통합 테스트를 다시 실행한다.
