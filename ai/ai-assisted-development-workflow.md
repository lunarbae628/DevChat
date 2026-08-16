# AI 보조 개발 워크플로우

## 목적

DevChat은 Superpowers 플러그인의 절차를 저장소 작업 규칙에 적용한다. 별도의 멀티 에이전트 시스템을 개발했다는 의미가 아니며, AI가 제안한 변경을 사람이 계획 단계에서 승인하고 검증 근거를 남기는 것이 목적이다.

```text
Research → Plan → Human Approval → Implementation → Review → Verification → PR Record
```

## 단계

1. **Research**: 현재 코드, 문서, Git 상태와 변경 경계를 확인한다.
2. **Plan**: 수정 파일, 위험, 테스트와 완료 조건을 작성한다.
3. **Human Approval**: 고위험 변경은 구현 전에 사람이 범위와 트레이드오프를 승인한다.
4. **Implementation**: 승인된 범위만 테스트 우선으로 구현한다.
5. **Review**: 구현과 분리된 관점에서 정확성, 보안, 트랜잭션과 운영 실패를 검토한다.
6. **Verification**: 테스트·빌드·성능 측정 등 주장에 대응하는 명령을 새로 실행한다.
7. **PR Record**: 채택·기각 이유, 실행한 검증과 남은 리스크를 사람이 기록한다.

단순 조회나 문구 수정처럼 위험이 낮은 작업에는 전체 절차를 강제하지 않는다.

## Logging Hook

프로젝트의 `.codex/hooks.json`은 다음 이벤트를 로컬에서 관찰한다.

모든 이벤트에는 세션·턴 ID, 모델과 권한 모드를 공통 메타데이터로 남긴다.

- `UserPromptSubmit`: 마스킹된 요청 미리보기, 길이와 SHA-256
- `SubagentStart`·`SubagentStop`: 서브에이전트 ID와 역할, 시작·종료 경계, 마스킹된 결과 미리보기
- `PermissionRequest`: 승인이 필요했던 도구 입력과 요청 사유. 실제 승인 여부는 기록하지 못한다.
- `PreToolUse`: Bash와 파일 수정 도구의 마스킹된 입력, 입력 길이·SHA-256과 대상 파일
- `PostToolUse`: 도구 성공 여부, exit code, 결과 길이·SHA-256과 실패 응답 미리보기

서브에이전트 결과는 마스킹 후 300자, 실패 응답은 500자까지만 저장한다. transcript 경로와 대상 파일 경로도 마스킹하며 경로 한 건은 500자, 한 도구 호출은 100건으로 제한한다. transcript 본문, 원본 프롬프트와 전체 도구 결과는 저장하지 않는다. 정규식 마스킹은 완전한 비밀정보 보호 수단이 아니므로 JSONL 원본은 Git에서 제외한다.

## 재현성과 한계

이 워크플로우에서 말하는 검증 재현성은 같은 Git 기준점과 기록된 검증 명령으로 결과를 다시 확인할 수 있다는 뜻이다. 같은 프롬프트가 동일한 코드 결과를 만든다는 뜻이 아니다.

Logging Hook은 실행을 관찰할 뿐 위험 명령을 차단하지 않는다. `PermissionRequest` 기록은 승인 절차가 시작됐다는 뜻이며, 사용자가 승인했다는 증거가 아니다. Permission Guard와 RAG는 별도 설계와 검증을 거쳐 추가한다.

## 종료 기록

자동 Markdown 요약·토큰 사용량 snapshot 생성은 제거됐다. 기존 `ai/summaries/` 파일은 로컬 보관 자료이며 새 작업 기록은 생성하지 않는다. `Stop` Hook은 세션 시작 이후 코드 변경이 있고 아직 질문하지 않았을 때만 continuation으로 지식 기록 생성 또는 작은 작업 면제를 묻도록 Codex에 지시한다. 질문 완료 상태는 Git에서 제외한 `ai/logs/*.knowledge-record-check.json`에만 남긴다. 기록을 만들기로 하면 테스트를 통과한 코드 변경의 목적·검증·남은 리스크를 `docs/knowledge/changes/`와 PR 본문에 사람이 남긴다.
