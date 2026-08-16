# 알림 비동기 전달 실패 처리

## 목적

친구·스터디 알림의 WebSocket 전달 실패가 친구 요청이나 스터디 신청·승인·거절 트랜잭션을 되돌리지 않도록 전달 시점을 커밋 이후로 통일하고, 실패를 운영에서 확인할 수 있게 했다.

## 변경 사항

- 친구·스터디 알림은 `notification` 저장 후 `AFTER_COMMIT` 이벤트에서 전용 `notificationDeliveryExecutor`로 전달 작업을 등록한다.
- executor의 크기와 큐 용량을 `app.notification-delivery` properties로 관리한다. 기본값은 core 2, max 4, queue 100이다.
- executor 크기·큐 용량·Discord 쿨다운의 기본값은 `application.yml`만 관리한다. 불변 record properties는 누락값·1 미만 크기·0 이하 쿨다운·`max-pool-size`가 `core-pool-size`보다 작은 조합을 애플리케이션 시작 단계에서 검증한다.
- WebSocket 전달 성공, 전송 예외, executor 거절을 `notification.delivery` counter의 `result`, `type` 태그로 기록한다. Prometheus 노출 이름은 Micrometer naming convention에 따라 `notification_delivery_total`이다.
- 전달 실패와 executor 거절은 사용자명·알림 본문·알림 ID를 포함하지 않는 구조화 로그와 별도 alert executor의 Discord webhook 알림으로 기록한다. Discord webhook이 비어 있으면 전송하지 않으며, 기본 5분 쿨다운으로 반복 경보를 제한한다. alert executor가 포화되면 `notification.alert` rejection counter와 로그만 기록한다.
- 스터디 알림 listener는 WebSocket에 직접 전송하지 않고 저장한 `NotificationDto`를 이벤트로 발행한다.
- 이번 변경으로 추가하거나 수정한 테스트 메서드에는 한국어 `@DisplayName`으로 검증 의도를 표시한다.
- DM 실시간 전달, 자동 재시도, Outbox는 변경하지 않았다.

## 영향 범위

- 친구·스터디 알림의 실시간 전송은 원래 업무 트랜잭션이 성공적으로 커밋된 뒤에만 시도된다.
- 전송 실패 시 사용자는 영속 알림함에서 알림을 다시 확인할 수 있다.
- WebSocket 전송 성공 metric은 서버의 `convertAndSend` 호출 성공을 의미하며, 브라우저 수신 보장은 아니다.

## 검증

- `bash ./gradlew --no-daemon test --tests project.backend.global.config.async.NotificationDeliveryPropertiesTest --tests project.backend.domain.notification.listener.NotificationEventListenerTest --tests project.backend.domain.notification.listener.DiscordNotificationDeliveryAlertTest --tests project.backend.domain.community.listener.ApplicantEventListenerTest --tests project.backend.domain.community.app.ApplicantServiceTest`
- 관련 결과 XML 8개에서 총 22개 테스트의 실패와 오류가 0건임을 확인했다.
- `bash ./gradlew --no-daemon test`는 107개 테스트를 실행했고, Docker를 사용할 수 없어 Testcontainers를 쓰는 통합 테스트 5개가 초기화 단계에서 실패했다.

## 남은 리스크

- Docker를 사용할 수 있는 환경에서 `NotificationDeliveryAfterCommitIntegrationTest`와 전체 통합 테스트를 다시 실행해야 한다.
- Discord는 운영 탐지 수단이며 전달 복구나 브라우저 수신 보장을 제공하지 않는다.
- 서버 재시작 뒤에도 미전송 전달을 복구하거나 자동 재시도가 필요해지면 Outbox와 멱등성 처리를 별도 변경으로 검토해야 한다.
