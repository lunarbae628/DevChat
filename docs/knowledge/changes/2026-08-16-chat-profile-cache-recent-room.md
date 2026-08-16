# 채팅 프로필 캐시와 최근 방 저장 보정

## 목적

실시간 채팅 메시지의 프로필 이미지 캐시 미스에서 `null`이 전달되는 문제와, 채팅방 입장 뒤 최근 방 정보가 저장되지 않아 로고 클릭 시 `/blank`로 이동하는 문제를 보정했다.

## 변경 사항

- 채팅 브로드캐스트에서 프로필 이미지 캐시가 비어 있으면 회원 정보를 조회해 캐시를 채운 뒤 해당 이미지를 사용한다.
- Redis 캐시 쓰기 실패는 채팅 메시지 브로드캐스트를 중단하지 않고 경고 로그로만 남긴다.
- 채팅방 입장 시 `recentRoomId`와 읽음 시퀀스 갱신이 class-level read-only 트랜잭션에 묻히지 않도록 쓰기 트랜잭션을 적용했다.

## 영향 범위

- 캐시가 비어 있는 사용자의 채팅 메시지도 프로필 이미지와 함께 실시간 브로드캐스트된다.
- 입장한 채팅방이 최근 방으로 저장되어 홈 로고의 최근 방 이동에 사용된다.

## 검증

- `bash ./gradlew test --rerun-tasks --tests project.backend.domain.chat.chatmessage.listener.ChatMessageBroadcastListenerTest --tests project.backend.domain.member.app.ProfileImageCacheTest --tests project.backend.domain.chat.chatroom.app.ChatRoomServiceTest` — 통과
- `ChatRoomRecentRoomIntegrationTest`는 Testcontainers가 Docker에 연결하지 못해 초기화 단계에서 실행하지 못했다.

## 남은 리스크

- Redis 장애와 정상 캐시 미스를 구분해 장애 시 재캐시를 피하는 개선은 별도 후속 작업으로 관리한다.
- 채팅방 입장과 회원·참여자 상태 변경의 동시 갱신 충돌 방지 역시 별도 후속 작업으로 관리한다.
- Docker가 가능한 환경에서 `ChatRoomRecentRoomIntegrationTest`를 다시 실행해야 한다.
