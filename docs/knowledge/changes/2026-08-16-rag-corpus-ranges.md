# RAG corpus 범위 기반 색인과 지식 기록 확인 훅

## 목적

개별 문서 목록을 수동으로 관리하면서 생기는 RAG 색인 누락과 draft·superseded 문서 포함 문제를 줄이고, 코드 변경 뒤 지식 기록 생성 여부를 한 번 확인한다.

## 변경 사항

- `ai/rag/corpus.json`을 include·exclude 범위 설정으로 전환하고, Git 추적 Markdown만 색인하도록 로더를 변경했다.
- `draft`·`superseded` 상태 표기가 있는 문서, `ai/summaries/`, 로그, 계획, 로컬 문서를 색인에서 제외했다.
- `Stop` Hook이 세션 기준 이후 코드 변경을 감지하면 지식 기록 생성 또는 작은 작업 면제를 한 번 묻도록 추가했다.
- 질문 완료 상태는 Git 제외 `ai/logs/*.knowledge-record-check.json`에만 저장하며 자동 Markdown summary는 생성하지 않는다.

## 영향 범위

- 로컬 RAG index를 다시 생성하면 새 `docs/knowledge/changes/*.md` 기록은 manifest를 수정하지 않아도 색인 대상이 된다.
- Hook 설정을 변경했으므로 Codex에서 `/hooks`를 통해 새 Stop handler를 신뢰해야 실제 세션에 적용된다.

## 검증

- `python3 -m unittest discover -s .codex/hooks/tests -p 'test_*.py' -v` — 65개 통과
- `python3 -m unittest discover -s .codex/rag/tests -p 'test_*.py' -v` — 31개 통과
- `git check-ignore -v ai/logs/example.knowledge-record-check.json`으로 상태 파일의 Git 제외를 확인했다.

## 남은 리스크

- Stop Hook은 세션 시작 시점 이후의 Git 변경을 기준으로 판단하므로, Codex 외부에서 같은 파일을 동시에 수정하면 해당 변경도 세션 변경으로 인식할 수 있다.
