# DevChat Knowledge Records

`changes/`에는 관련 테스트를 통과한 코드 변경 중 Stop Hook의 확인에 따라 사용자가 기록 생성을 선택한 사실 기반 지식 기록을 둔다. 사용자가 작은 작업의 기록을 명시적으로 면제할 수 있다.

파일명은 `YYYY-MM-DD-<branch-or-topic>.md` 형식으로 작성한다. 각 문서는 목적, 변경 사항, 영향 범위, 검증, 남은 리스크를 포함한다.

이 문서는 작업 중간 로그나 계획이 아니다. `ai/rag/corpus.json`의 `docs/knowledge/**/*.md` 범위와 Git 추적 조건을 만족하는 기록만 로컬 RAG가 검색한다. `docs/superpowers/plans/`, `docs/local/`, `draft`, `superseded`, `ai/summaries/` 문서는 검색 대상이 아니다.
