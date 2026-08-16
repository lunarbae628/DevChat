# RAG Corpus Range Selection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 개별 문서 목록 대신 안전한 Markdown 경로 범위로 RAG corpus를 구성하고, 코드 변경 완료 시 지식 기록 필요 여부를 확인하도록 작업 흐름을 정리한다.

**Architecture:** corpus manifest는 포함 glob과 제외 glob을 선언하고, Python 로더가 저장소에서 Git 추적 Markdown 파일만 확장한다. 추적되지 않은 파일·심볼릭 링크·`ai/logs`·`ai/summaries`·계획·로컬 문서는 여전히 제외한다. Stop 훅은 자동 Markdown을 만들지 않고, 세션 시작 이후 코드 변경이 있을 때 한 번만 continuation으로 문서화 여부 확인을 유도한다.

**Tech Stack:** Python 3 표준 라이브러리 (`pathlib`, `fnmatch`, `subprocess`), JSON, `unittest`, Codex hook 설정

## Global Constraints

- 기존 `.DS_Store` 미커밋 파일은 수정·stage·되돌리지 않는다.
- corpus에는 Git 추적된 Markdown 문서만 넣는다.
- `docs/superpowers/plans/`, `docs/local/`, `ai/logs/`, `ai/summaries/`, `_workspace/`는 색인하지 않는다.
- Stop Hook은 요약 파일을 생성하거나 모델에 추가 컨텍스트를 주지 않는다.
- 테스트 메서드에는 동작을 설명하는 한국어 `@DisplayName`을 사용한다. (해당 언어의 테스트가 추가될 경우)

---

### Task 1: 범위 기반 corpus 로더와 manifest 전환

**Files:**
- Modify: `ai/rag/corpus.json`
- Modify: `.codex/rag/corpus.py`
- Modify: `.codex/rag/tests/test_corpus.py`

**Interfaces:**
- Consumes: `include`·`exclude` 문자열 배열을 가진 `corpus.json`.
- Produces: `load_active_documents(repo_root, manifest_path) -> List[CorpusDocument]`.

- [ ] **Step 1: 실패하는 범위 확장 테스트를 작성한다.**

```python
self.manifest.write_text(json.dumps({
    "include": ["docs/knowledge/**/*.md", "AGENTS.md"],
    "exclude": ["docs/knowledge/**/draft-*.md"],
}), encoding="utf-8")

documents = load_active_documents(self.repo, self.manifest)

self.assertEqual(
    [document.path for document in documents],
    ["AGENTS.md", "docs/knowledge/changes/record.md"],
)
```

추가로 `ai/summaries/*.md`, `docs/superpowers/plans/*.md`, 미추적 문서, 심볼릭 링크가 include 패턴에 맞아도 제외되는 테스트를 작성한다.

- [ ] **Step 2: 테스트가 현재 manifest 형식에서 실패하는지 확인한다.**

Run: `python3 -m unittest .codex.rag.tests.test_corpus -v`

Expected: `documents` 키가 없다는 `ValueError` 또는 범위 문서가 반환되지 않아 FAIL.

- [ ] **Step 3: 최소 범위 확장 로직을 구현한다.**

`git ls-files -z -- '*.md'`로 추적 Markdown 후보를 구한 뒤, POSIX 상대 경로에 대해 include 하나 이상과 exclude 어느 것도 일치하지 않는 파일만 선택한다. 후보마다 기존의 루트 이탈·심볼릭 링크 검사를 적용하고, 결과는 경로 오름차순으로 정렬해 재현성을 보장한다.

```python
def load_active_documents(repo_root: Path, manifest_path: Path) -> List[CorpusDocument]:
    payload = json.loads(manifest_path.read_text(encoding="utf-8"))
    include, exclude = _patterns(payload)
    return [
        CorpusDocument(path=path, status="active")
        for path in _tracked_markdown_paths(repo_root)
        if _matches_include(path, include) and not _matches_exclude(path, exclude)
        if _safe_tracked_markdown(repo_root, Path(path))
    ]
```

`corpus.json`은 개별 `documents` 목록을 제거하고 다음 범위를 선언한다.

```json
{
  "include": ["AGENTS.md", "README.md", "ai/*.md", "docs/knowledge/**/*.md", "docs/superpowers/specs/**/*.md"],
  "exclude": ["ai/logs/**", "ai/summaries/**", "docs/local/**", "docs/superpowers/plans/**", "_workspace/**", "**/draft/**", "**/superseded/**"]
}
```

문서 본문에 `상태: draft` 또는 `상태: superseded`가 명시된 spec도 제외하도록 상태 판별을 추가한다. 기존 manifest에 개별 상태로만 표시된 draft·superseded 항목은 새 manifest에서 제거한다.

- [ ] **Step 4: corpus 테스트를 통과시킨다.**

Run: `python3 -m unittest .codex.rag.tests.test_corpus -v`

Expected: 범위 포함·제외, Git 추적 확인, 심볼릭 링크 제외 테스트가 모두 PASS.

### Task 2: Stop 훅으로 완료 시 문서화 확인

**Files:**
- Modify: `AGENTS.md`
- Modify: `docs/knowledge/README.md`
- Modify: `ai/ai-assisted-development-workflow.md`
- Modify: `.codex/hooks.json`
- Create: `.codex/hooks/check_knowledge_record.py`
- Modify: `.gitignore`
- Modify: `.codex/hooks/tests/test_hook_config.py`
- Modify: `.codex/hooks/tests/test_hook_entrypoints.py`

**Interfaces:**
- Consumes: 세션 시작 시점의 Git 상태, 현재 Git 상태, `stop_hook_active`.
- Produces: 세션당 한 번의 Stop continuation 또는 통과 JSON.

- [ ] **Step 1: Stop 훅의 한 번만 질문하는 실패 테스트를 작성한다.**

```python
def test_stop_hook_asks_once_after_a_session_code_change(self):
    result = self.invoke("Stop", changed_file_payload)

    self.assertEqual(json.loads(result.stdout)["decision"], "block")
    self.assertIn("지식 기록", json.loads(result.stdout)["reason"])

    continued = self.invoke("Stop", {**changed_file_payload, "stop_hook_active": True})
    self.assertEqual(continued.stdout, "")
```

- [ ] **Step 2: 테스트가 통과하는지 확인한다.**

Run: `python3 .codex/hooks/tests/test_hook_entrypoints.py HookEntrypointsTest.test_stop_hook_asks_once_after_a_session_code_change -v`

Expected: `Stop` handler가 없으므로 FAIL.

- [ ] **Step 3: Stop handler와 작업 절차를 구현한다.**

`check_knowledge_record.py`는 logging hook이 남긴 세션 기준 Git 상태와 현재 상태를 비교한다. 새 코드 변경이 있고 이번 세션에서 아직 질문하지 않았으며 새 `docs/knowledge/changes/*.md`가 없으면 `{ "decision": "block" }`으로 continuation을 요청한다. 질문 완료 여부는 Git 제외 `ai/logs/*.knowledge-record-check.json`에 세션별로 저장한다. `stop_hook_active`가 참이면 항상 통과해 같은 종료 흐름의 반복을 막는다. workflow와 knowledge README도 자동 Stop summary를 복원하지 않고, Stop 훅이 확인만 한다는 점을 명시한다.

- [ ] **Step 4: Hook 설정 회귀 테스트를 통과시킨다.**

Run: `python3 -m unittest discover -s .codex/hooks/tests -p 'test_*.py' -v`

Expected: Stop handler가 설정되고 자동 summary·`additionalContext` 없이 PASS.

### Task 3: 전체 검증과 corpus 재생성 확인

**Files:**
- Verify only: `.codex/rag/tests/test_*.py`
- Verify only: `.codex/hooks/tests/test_*.py`

- [ ] **Step 1: RAG·Hook 회귀 테스트를 실행한다.**

Run: `python3 -m unittest discover -s .codex/rag/tests -p 'test_*.py' -v && python3 -m unittest discover -s .codex/hooks/tests -p 'test_*.py' -v`

Expected: 모두 PASS.

- [ ] **Step 2: 범위가 실제 대상만 선택하는지 확인한다.**

Run: `python3 -c 'from pathlib import Path; import sys; sys.path.insert(0, ".codex/rag"); from corpus import load_active_documents; print("\\n".join(item.path for item in load_active_documents(Path("."), Path("ai/rag/corpus.json"))))'`

Expected: `docs/knowledge/changes/*.md`와 현재 운영 문서가 출력되고 `ai/summaries/`, `docs/superpowers/plans/`, draft·superseded spec은 출력되지 않는다.

- [ ] **Step 3: staged diff와 관련 없는 파일을 검토한다.**

Run: `git diff --check && git status --short`

Expected: 공백 오류가 없고 기존 `.DS_Store`는 stage하지 않는다.

- [ ] **Step 4: 사용자 요청이 있을 때만 커밋한다.**

```bash
git add AGENTS.md ai/rag/corpus.json .codex/rag/corpus.py .codex/rag/tests/test_corpus.py \
  docs/knowledge/README.md ai/ai-assisted-development-workflow.md \
  .codex/hooks/tests/test_hook_config.py docs/superpowers/plans/2026-08-16-rag-corpus-ranges.md
git commit -m "refactor: RAG corpus 범위 기반 색인 전환"
```

## Self-Review

- corpus 범위 전환은 Task 1에서 구현·테스트하고, 예상 외 문서 색인은 Git 추적·경로·심볼릭 링크 검사로 방지한다.
- Stop summary 자동 생성 금지와 문서화 확인 규칙은 Task 2에서 함께 검증한다.
- 현재 미커밋 `.DS_Store`는 모든 task에서 제외한다.
- `TBD`, `TODO`, 모호한 후속 구현 단계 없이 각 변경과 검증 명령을 명시했다.
