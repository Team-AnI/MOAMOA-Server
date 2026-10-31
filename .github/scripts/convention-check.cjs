'use strict';

const ISSUE_TYPES = ['FEAT', 'FIX', 'REFACTOR', 'DOCS', 'CHORE', 'TEST', 'CI'];
const PR_TYPES = ['feat', 'fix', 'refactor', 'style', 'test', 'docs', 'chore', 'ci', 'rename', 'remove'];
const SECTION_NAMES = ['설명', '할 일', '관련 이슈', '작업 내용', '테스트', '테스트 및 검증', '체크리스트'];

// Keep fenced examples as section content, but never interpret their headings.
function sections(body = '') {
  const result = new Map();
  let current;
  let level;
  let fence;
  for (const line of (body || '').replace(/<!--[^]*?-->/g, '').split(/\r?\n/)) {
    const delimiter = line.match(/^\s{0,3}(`{3,}|~{3,})/);
    if (delimiter && (!fence || (delimiter[1][0] === fence[0] && delimiter[1].length >= fence.length))) {
      fence = fence ? undefined : delimiter[1];
    } else if (!fence) {
      const heading = line.match(/^\s{0,3}(#{1,6})\s+(.+?)\s*#*\s*$/);
      const name = heading?.[2].replace(/^[^\p{L}\p{N}]+/u, '').trim();
      if (heading && (!current || heading[1].length <= level || SECTION_NAMES.includes(name))) {
        current = undefined;
        if ([2, 3].includes(heading[1].length)) {
          current = name;
          level = heading[1].length;
          if (!result.has(current)) result.set(current, []);
        }
        continue;
      }
    }
    if (current) result.get(current).push(line);
  }
  return new Map([...result].map(([key, lines]) => [key, lines.join('\n')]));
}

function hasContent(value = '') {
  return value.split('\n').some(line => {
    const text = line.replace(/^\s*(?:[-*+]\s+)?(?:\[[ xX]\]\s*)?/, '').trim();
    return !/^(?:#{1,6}\s|`{3,}|~{3,})/.test(text) && /[\p{L}\p{N}]/u.test(text)
      && !/^(?:어떤 작업인지, 왜 필요한지 적습니다\.|작업한 내용을 요약합니다\.|실행한 테스트와 검증 결과를 적습니다\.|변경한 내용과 이유:|영향 범위\(해당되는 경우\):|실행한 명령\/확인 방법:|실제 결과:|미실행 항목과 이유\(있다면\):)$/.test(text);
  });
}

function hasChecklistItem(value) {
  let fence;
  for (const line of value.split('\n')) {
    const delimiter = line.match(/^\s{0,3}(`{3,}|~{3,})/);
    if (delimiter) {
      if (!fence) fence = delimiter[1];
      else if (delimiter[1][0] === fence[0] && delimiter[1].length >= fence.length && !line.slice(delimiter[0].length).trim()) fence = undefined;
      continue;
    }
    if (!fence && /^ {0,3}[-*+]\s+\[[ xX]\]\s+\S.*/.test(line)) return true;
  }
  return false;
}

function validate(kind, { title = '', body = '' }) {
  const errors = [];
  const parts = sections(body);
  const label = kind === 'issue' ? 'Issue' : 'PR';
  const required = kind === 'issue'
    ? [['설명'], ['할 일']]
    : [['관련 이슈'], ['작업 내용'], ['테스트', '테스트 및 검증'], ['체크리스트']];
  for (const names of required) {
    if (!names.some(name => parts.has(name))) errors.push(`${label} 본문 오류: ${names.join(' 또는 ')} 섹션이 없습니다.`);
  }

  if (kind === 'issue') {
    const match = title.match(/^\[([^\]]+)\]\s+(.+)$/);
    if (!match || !ISSUE_TYPES.includes(match[1]) || !match[2].trim()) {
      errors.push(`Issue 제목 오류: [TYPE] 작업 내용 형식으로 작성해 주세요. 허용 TYPE: ${ISSUE_TYPES.join(', ')}.`);
    }
    if (parts.has('설명') && !hasContent(parts.get('설명'))) errors.push('Issue 본문 오류: 설명 내용을 작성해 주세요.');
    if (parts.has('할 일') && !hasChecklistItem(parts.get('할 일'))) {
      errors.push('Issue 본문 오류: 할 일에 최소 한 개의 체크리스트 항목을 작성해 주세요.');
    }
  } else {
    const prefix = title.match(/^([a-z]+):\s+(.+)$/);
    const issue = title.match(/\s\(#([1-9]\d*)\)$/);
    if (!prefix || !PR_TYPES.includes(prefix[1])) errors.push(`PR 제목 오류: 허용된 type과 콜론을 사용해 주세요. 허용 type: ${PR_TYPES.join(', ')}.`);
    if (!issue) errors.push('PR 제목 오류: 끝에 관련 이슈 번호가 없습니다. (#12) 형식으로 작성해 주세요.');
    if (!prefix || !prefix[2].replace(/\s*\(#[1-9]\d*\)$/, '').trim()) errors.push('PR 제목 오류: 작업 설명(subject)이 비어 있습니다.');
    const references = [...(parts.get('관련 이슈') || '').matchAll(/\b(?:close|closes|fixes|refs)\s+#([1-9]\d*)\b/gi)].map(match => match[1]);
    if (!references.length) errors.push('PR 본문 오류: 관련 이슈에 close #12, closes #12, fixes #12 또는 refs #12를 작성해 주세요.');
    if (issue && references.length && !references.includes(issue[1])) errors.push('PR 본문 오류: 제목의 이슈 번호가 관련 이슈 목록에 없습니다.');
    if (parts.has('작업 내용') && !hasContent(parts.get('작업 내용'))) errors.push('PR 본문 오류: 작업 내용을 작성해 주세요.');
    if (parts.has('체크리스트') && !hasChecklistItem(parts.get('체크리스트'))) errors.push('PR 본문 오류: 체크리스트에 최소 한 개의 체크리스트 항목을 작성해 주세요.');
    const test = ['테스트', '테스트 및 검증'].filter(name => parts.has(name));
    // A filled command label alone is not a result or a reason for skipping.
    const hasResult = name => hasContent(parts.get(name)
      .replace(/^\s*[-*+]?\s*실행한 명령\/확인 방법:.*$/gm, '')
      .replace(/^\s*[-*+]?\s*`(?:\.\/gradlew|npm|node|git)\s+[^`\n]*`\s*$/gm, ''));
    if (test.length && !test.some(hasResult)) errors.push('PR 본문 오류: 테스트 실행 결과 또는 미실행 사유를 작성해 주세요.');
  }
  return errors;
}

function message(kind, errors) {
  const marker = `<!-- moamoa-convention:${kind}:v1 -->`;
  if (!errors.length) return `${marker}\n### 컨벤션 자동 검수 결과\n\n✅ 수정된 제목과 본문이 컨벤션 검수를 통과했습니다.\n\n실제 테스트 성공 여부는 Gradle Test에서 확인해 주세요.`;
  const examples = kind === 'issue'
    ? '- 제목: `[FEAT] 모임 생성 API 구현`\n- 본문: `## 설명`에 작업 설명, `## 할 일`에 `- [ ] API 구현`을 작성해 주세요.'
    : '- 제목: `feat: 모임 생성 API 구현 (#12)`\n- 관련 이슈: `Closes #12` 또는 `Refs #12`\n- 테스트: 실행한 명령어 및 결과 또는 미실행 사유를 작성해 주세요.';
  return `${marker}\n### 컨벤션 자동 검수 결과\n\n수정이 필요한 항목이 발견되었습니다.\n\n**수정 필요**\n${errors.map(error => `- ❌ ${error}`).join('\n')}\n\n**수정 예시**\n${examples}\n\n내용을 수정하시면 자동으로 다시 검수합니다.`;
}

async function report(core, kind, errors) {
  for (const error of errors) core.error(error);
  await core.summary.addRaw(message(kind, errors)).write();
  if (errors.length) core.setFailed(`컨벤션 검수 ${errors.length}개 항목을 확인해 주세요.`);
}

function matchesRun(pr, run, repository) {
  return pr.state === 'open' && pr.base.ref === 'main'
    && pr.base.repo.full_name === repository
    && pr.head.repo?.id === run.head_repository?.id
    && pr.head.ref === run.head_branch && pr.head.sha === run.head_sha;
}

async function feedback({ github, context, core }, kind, number, run) {
  const params = { ...context.repo, issue_number: number };
  const comments = await github.paginate(github.rest.issues.listComments, { ...params, per_page: 100 });
  const marker = `<!-- moamoa-convention:${kind}:v1 -->`;
  const previous = comments.find(comment => comment.user?.login === 'github-actions[bot]'
    && comment.user?.type === 'Bot' && comment.body?.startsWith(marker));
  // Re-read after listing comments, under the workflow's concurrency lock.
  const { data: latest } = kind === 'issue'
    ? await github.rest.issues.get(params)
    : await github.rest.pulls.get({ ...context.repo, pull_number: number });
  if (kind === 'pr' && !matchesRun(latest, run, `${context.repo.owner}/${context.repo.repo}`)) {
    core.info('최신 PR과 원본 실행이 일치하지 않아 댓글을 생략합니다.');
    return;
  }
  const errors = validate(kind, latest);
  const body = message(kind, errors);
  if (previous && previous.body !== body) {
    await github.rest.issues.updateComment({ ...context.repo, comment_id: previous.id, body });
  } else if (!previous && errors.length) {
    await github.rest.issues.createComment({ ...params, body });
  }
}

async function run({ github, context, core }) {
  const api = { github, context, core };
  if (context.eventName === 'workflow_run') {
    const { data: source } = await github.rest.actions.getWorkflowRun({ ...context.repo, run_id: context.payload.workflow_run.id });
    const repository = `${context.repo.owner}/${context.repo.repo}`;
    if (source.repository.full_name !== repository || source.event !== 'pull_request'
      || source.path !== '.github/workflows/metadata-validation.yml' || source.status !== 'completed'
      || !['success', 'failure'].includes(source.conclusion)) {
      core.setFailed('원본 컨벤션 검수 실행을 확인할 수 없습니다.');
      return;
    }
    // Fork runs may have pull_requests: []. Resolve only through GitHub's API.
    const candidates = await github.paginate(github.rest.pulls.list, {
      ...context.repo, state: 'open', base: 'main',
      head: `${source.head_repository.owner.login}:${source.head_branch}`, per_page: 100,
    });
    const matches = candidates.filter(pr => matchesRun(pr, source, repository));
    if (matches.length !== 1) {
      core.warning('원본 실행과 일치하는 최신 main 대상 PR을 하나로 확인할 수 없어 댓글을 생략합니다.');
      return;
    }
    await feedback(api, 'pr', matches[0].number, source);
    return;
  }

  const kind = context.eventName === 'issues' ? 'issue' : 'pr';
  const number = context.payload.issue?.number || context.payload.pull_request.number;
  const { data: latest } = kind === 'issue'
    ? await github.rest.issues.get({ ...context.repo, issue_number: number })
    : await github.rest.pulls.get({ ...context.repo, pull_number: number });
  await report(core, kind, validate(kind, latest));
  if (kind === 'issue') {
    try {
      await feedback(api, kind, number);
    } catch (error) {
      // A comment outage must never turn a failed validation into success.
      core.warning(`안내 댓글을 갱신하지 못했습니다 (HTTP ${error.status || 'unknown'}). 검수 결과는 유지됩니다.`);
    }
  }
}

module.exports = { validate, message, run };
