'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const { validate, message, run } = require('./convention-check.cjs');

const issue = {
  number: 12, title: '[FEAT] 모임 생성',
  body: '## 📝 설명\n모임 생성 API가 필요합니다.\n## ✅ 할 일\n- [ ] 구현',
};
const pr = {
  number: 14, state: 'open', title: 'feat: 모임 생성 (#12)',
  body: '## 📌 관련 이슈\nRefs #12\n## ✨ 작업 내용\n모임을 생성합니다.\n## 🧪 테스트\n- `./gradlew test` 통과\n## ✅ 체크리스트\n- [ ] 셀프 리뷰',
  base: { ref: 'main', repo: { full_name: 'Team-AnI/MOAMOA-Server' } },
  head: { ref: 'feature/#12-test', sha: 'a'.repeat(40), repo: { id: 42 } },
};
const source = {
  id: 100, event: 'pull_request', status: 'completed', conclusion: 'failure',
  path: '.github/workflows/metadata-validation.yml', pull_requests: [],
  repository: { full_name: 'Team-AnI/MOAMOA-Server' },
  head_repository: { id: 42, owner: { login: 'contributor' } },
  head_branch: pr.head.ref, head_sha: pr.head.sha,
};
const bot = { login: 'github-actions[bot]', type: 'Bot' };
const comment = kind => ({ id: 9, user: bot, body: `<!-- moamoa-convention:${kind}:v1 -->\nold` });

function mock(eventName, options = {}) {
  const calls = { errors: [], warnings: [], failed: [], summaries: [], created: [], updated: [], lists: [] };
  const comments = structuredClone(options.comments || []);
  const get = async () => ({ data: options.latest ? options.latest() : eventName === 'issues' ? issue : pr });
  const rest = {
    issues: {
      get, listComments() {},
      async createComment(params) {
        if (options.commentError) throw { status: 403 };
        calls.created.push(params);
        comments.push({ id: 10, user: bot, body: params.body });
      },
      async updateComment(params) {
        if (options.commentError) throw { status: 403 };
        calls.updated.push(params);
        comments.find(item => item.id === params.comment_id).body = params.body;
      },
    },
    pulls: { get, list() {} },
    actions: { getWorkflowRun: async () => ({ data: options.source || source }) },
  };
  const github = { rest, async paginate(method, params) {
    if (method === rest.issues.listComments) return comments;
    assert.equal(method, rest.pulls.list);
    calls.lists.push(params);
    return options.candidates || [pr];
  } };
  const core = {
    error: text => calls.errors.push(text), warning: text => calls.warnings.push(text),
    setFailed: text => calls.failed.push(text), info() {},
    summary: { addRaw(text) { calls.summaries.push(text); return this; }, async write() {} },
  };
  const context = {
    eventName, repo: { owner: 'Team-AnI', repo: 'MOAMOA-Server' },
    payload: { issue: eventName === 'issues' ? { number: issue.number } : undefined, pull_request: pr, workflow_run: { id: source.id } },
  };
  return { github, context, core, calls, comments };
}

for (const type of ['FEAT', 'FIX', 'REFACTOR', 'DOCS', 'CHORE', 'TEST', 'CI']) {
  test(`Issue accepts ${type}`, () => assert.deepEqual(validate('issue', { ...issue, title: `[${type}] 작업` }), []));
}
for (const type of ['feat', 'fix', 'refactor', 'style', 'test', 'docs', 'chore', 'ci', 'rename', 'remove']) {
  test(`PR accepts ${type}`, () => assert.deepEqual(validate('pr', { ...pr, title: `${type}: 작업 (#12)` }), []));
}
for (const [kind, patch, error] of [
  ['issue', { title: '[DESIGN] 화면' }, 'Issue 제목 오류'],
  ['issue', { title: '[FEAT] ' }, 'Issue 제목 오류'],
  ['issue', { body: '## 할 일\n- [ ] 구현' }, '설명 섹션'],
  ['issue', { body: '## 설명\n<!-- 안내 -->\n## 할 일\n- [ ] 구현' }, '설명 내용을'],
  ['pr', { title: 'Feat: 작업 (#12)' }, '허용된 type'],
  ['pr', { title: 'feat: 작업' }, '끝에 관련 이슈'],
  ['pr', { title: 'feat: (#12)' }, 'subject'],
  ['pr', { body: pr.body.replace('Refs #12', 'Refs #99') }, '목록에 없습니다'],
  ['pr', { body: pr.body.replace('Refs #12', '') }, 'close #12'],
  ['pr', { body: pr.body.replace('## 🧪 테스트', '## 검증 자료') }, '테스트 또는'],
]) {
  test(`${kind} rejects ${error}`, () => assert.ok(validate(kind, { ...(kind === 'issue' ? issue : pr), ...patch }).some(text => text.includes(error))));
}

for (const kind of ['issue', 'pr']) {
  const fixture = kind === 'issue' ? issue : pr;
  const section = kind === 'issue' ? '## ✅ 할 일\n- [ ] 구현' : '## ✅ 체크리스트\n- [ ] 셀프 리뷰';
  for (const [name, value, valid] of [
    ['empty section', '', false], ['plain text', '작업 확인', false],
    ['empty checkbox', '- [ ] ', false], ['checked empty checkbox', '- [x] ', false],
    ['HTML comment', '<!-- - [ ] 작업 확인 -->', false],
    ['four-space code block', '\n    - [ ] 작업 확인', false],
    ['four-space checked code block', '\n    - [x] 작업 확인', false],
    ['eight-space code block', '\n        - [ ] 작업 확인', false],
    ['tab-indented code block', '\n\t- [ ] 작업 확인', false],
    ['three-space item', '   - [ ] 작업 확인', true],
    ['item after indented code block', '\n    - [ ] 예시\n\n- [ ] 작업 확인', true],
    ['fenced example', '```markdown\n- [ ] 작업 확인\n```', false],
    ['tilde example', '~~~markdown\n- [x] 작업 확인\n~~~', false],
    ['shorter nested fence', '````markdown\n```\n- [ ] 작업 확인\n````', false],
    ['unchecked item', '- [ ] A', true], ['checked item', '- [x] 작업 확인', true],
    ['uppercase checked item', '* [X] 작업 확인', true],
    ['item after example', '```markdown\n- [ ] 예시\n```\n- [ ] 작업 확인', true],
  ]) {
    test(`${kind} checklist: ${name}`, () => {
      const errors = validate(kind, { ...fixture, body: fixture.body.replace(section, `${section.split('\n')[0]}\n${value}`) });
      if (valid) assert.deepEqual(errors, []);
      else assert.ok(errors.some(text => text.includes('체크리스트 항목')));
    });
  }
}

test('heading levels, emoji-free headings, blank lines and plain text are safe', () => {
  assert.deepEqual(validate('issue', { ...issue, body: '일반 텍스트\n\n## 설명\n설명\n### 할 일\n- [ ] 작업' }), []);
  assert.deepEqual(validate('issue', { ...issue, title: '[FIX] 오류' }), []);
});
for (const keyword of ['close', 'CLOSES', 'Fixes', 'rEfS']) {
  test(`PR accepts multiple references with ${keyword}`, () => assert.deepEqual(validate('pr', { ...pr, body: pr.body.replace('Refs #12', `${keyword} #99\n${keyword} #12`) }), []));
}
test('test aliases and skip reasons are accepted', () => {
  assert.deepEqual(validate('pr', { ...pr, body: pr.body.replace('## 🧪 테스트\n- `./gradlew test` 통과', '### 테스트 및 검증\n- 미실행 사유: 문서만 변경하여 링크를 수동 확인했습니다.') }), []);
});
for (const text of ['', '- 실행한 명령/확인 방법:\n- 실제 결과:\n- 미실행 항목과 이유(있다면):', '- 실행한 명령/확인 방법: ./gradlew test\n- 실제 결과:', '- `./gradlew test`']) {
  test(`PR rejects an unwritten test result: ${JSON.stringify(text)}`, () => assert.ok(validate('pr', { ...pr, body: pr.body.replace('- `./gradlew test` 통과', text) }).some(error => error.includes('미실행 사유'))));
}
test('commented or fenced headings are not real sections', () => {
  assert.ok(validate('pr', { ...pr, body: `<!--\n${pr.body}\n-->\n\`\`\`markdown\n${pr.body}\n\`\`\`` }).some(error => error.includes('섹션이 없습니다')));
});
test('feedback never interpolates user shell expressions or annotation commands', () => {
  const errors = validate('issue', { title: '$(unsafe-command)\n::error::payload', body: '' });
  assert.ok(!message('issue', errors).includes('unsafe-command'));
  assert.ok(!message('issue', errors).includes('::error::payload'));
});

test('invalid Issue emits all errors, summary and failure even when commenting fails', async () => {
  const api = mock('issues', { latest: () => ({ ...issue, title: 'invalid', body: '' }), commentError: true });
  await run(api);
  assert.equal(api.calls.errors.length, 3);
  assert.equal(api.calls.failed.length, 1);
  assert.match(api.calls.summaries[0], /수정 필요/);
  assert.equal(api.calls.warnings.length, 1);
});
for (const event of ['issues', 'pull_request']) {
  test(`${event} first pass creates no comment`, async () => {
    const api = mock(event);
    await run(api);
    assert.equal(api.calls.failed.length, 0);
    assert.equal(api.comments.length, 0);
  });
}
test('PR validation fails without posting comments', async () => {
  const api = mock('pull_request', { latest: () => ({ ...pr, title: 'invalid' }) });
  await run(api);
  assert.equal(api.calls.failed.length, 1);
  assert.equal(api.comments.length, 0);
});
for (const [event, fixture] of [['issues', issue], ['workflow_run', pr]]) {
  test(`${event} updates one comment through repeated failure and recovery`, async () => {
    let latest = { ...fixture, title: 'invalid' };
    const api = mock(event, { latest: () => latest });
    await run(api);
    const id = api.comments[0].id;
    latest = { ...fixture, body: '' };
    await run(api);
    assert.match(api.comments[0].body, /섹션이 없습니다/);
    assert.ok(!api.comments[0].body.includes('제목 오류'));
    latest = fixture;
    await run(api);
    await run(api);
    assert.equal(api.calls.created.length, 1);
    assert.equal(api.calls.updated.length, 2);
    assert.equal(api.comments[0].id, id);
    assert.match(api.comments[0].body, /통과했습니다/);
  });
}
test('Fork lookup does not depend on workflow_run.pull_requests', async () => {
  const api = mock('workflow_run', { latest: () => ({ ...pr, title: 'invalid' }) });
  await run(api);
  assert.equal(api.calls.lists[0].head, 'contributor:feature/#12-test');
  assert.equal(api.calls.created[0].issue_number, pr.number);
});
test('passing Fork PR creates no first comment', async () => {
  const api = mock('workflow_run');
  await run(api);
  assert.equal(api.comments.length, 0);
});
test('human, other-bot and other-kind comments are untouched', async () => {
  const comments = [
    { ...comment('pr'), id: 1, user: { login: 'member', type: 'User' } },
    { ...comment('pr'), id: 2, user: { login: 'other[bot]', type: 'Bot' } },
    { ...comment('issue'), id: 3 },
  ];
  const api = mock('workflow_run', { comments, latest: () => ({ ...pr, title: 'invalid' }) });
  await run(api);
  assert.deepEqual(api.comments.slice(0, 3), comments);
  assert.equal(api.calls.created.length, 1);
  assert.equal(api.calls.updated.length, 0);
});
for (const patch of [
  { repository: { full_name: 'other/repo' } }, { event: 'push' },
  { path: '.github/workflows/other.yml' }, { status: 'in_progress' }, { conclusion: 'cancelled' },
  { head_sha: 'b'.repeat(40) }, { head_branch: 'other' },
  { head_repository: { id: 99, owner: { login: 'other' } } },
]) {
  test(`unrelated source cannot comment: ${JSON.stringify(patch)}`, async () => {
    const api = mock('workflow_run', { source: { ...source, ...patch }, comments: [comment('pr')] });
    await run(api);
    assert.equal(api.calls.created.length + api.calls.updated.length, 0);
  });
}
test('ambiguous PR candidates cannot receive feedback', async () => {
  const api = mock('workflow_run', { candidates: [pr, { ...pr, number: 15 }] });
  await run(api);
  assert.equal(api.calls.created.length, 0);
  assert.equal(api.calls.warnings.length, 1);
});
for (const latest of [
  { ...pr, head: { ...pr.head, sha: 'b'.repeat(40) } },
  { ...pr, base: { ...pr.base, ref: 'release' } }, { ...pr, state: 'closed' },
]) {
  test(`PR changed before comment write is skipped: ${JSON.stringify(latest.head)} ${latest.base.ref} ${latest.state}`, async () => {
    const api = mock('workflow_run', { latest: () => latest, comments: [comment('pr')] });
    await run(api);
    assert.equal(api.calls.created.length + api.calls.updated.length, 0);
  });
}
test('latest Issue content is read again after listing comments', async () => {
  let reads = 0;
  const api = mock('issues', { latest: () => ++reads === 1 ? { ...issue, title: 'invalid' } : issue, comments: [comment('issue')] });
  await run(api);
  assert.equal(api.calls.failed.length, 1);
  assert.match(api.calls.updated[0].body, /통과했습니다/);
});
test('PR comment failure does not change a separate metadata result', async () => {
  const check = mock('pull_request');
  await run(check);
  const feedback = mock('workflow_run', { latest: () => ({ ...pr, title: 'invalid' }), commentError: true });
  await assert.rejects(run(feedback), error => error.status === 403);
  assert.equal(check.calls.failed.length, 0);
});
