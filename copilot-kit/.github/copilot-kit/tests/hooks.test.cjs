#!/usr/bin/env node
/**
 * Tests for the kit hooks, driven the way Copilot CLI drives them: one JSON
 * payload on stdin, one JSON object on stdout, exit code 0.
 *
 * Run from the project root:
 *   node --test .github/copilot-kit/tests/hooks.test.cjs
 *
 * Each test works on a private copy of the kit in a temp directory, so the real
 * config, active plan and log are never touched.
 */

const { describe, it, before, after } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const KIT_SOURCE = path.join(__dirname, '..');
let projectRoot;
let kitDir;

/** Run a hook script from the temp kit with a payload; returns parsed stdout and exit code. */
function runHook(script, payload, rawStdin) {
  const result = spawnSync('node', [path.join(kitDir, 'hooks', script)], {
    input: rawStdin !== undefined ? rawStdin : JSON.stringify(payload),
    encoding: 'utf8',
    cwd: projectRoot
  });
  return { status: result.status, output: JSON.parse(result.stdout || '{}'), stdout: result.stdout };
}

function guard(toolName, toolArgs) {
  return runHook('pre-tool-guard.cjs', { sessionId: 's1', cwd: projectRoot, toolName, toolArgs }).output;
}

function writeConfig(config) {
  fs.writeFileSync(path.join(kitDir, 'config.json'), JSON.stringify(config));
}

before(() => {
  projectRoot = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'copilot-kit-test-')));
  kitDir = path.join(projectRoot, '.github', 'copilot-kit');
  fs.cpSync(KIT_SOURCE, kitDir, { recursive: true, filter: (src) => !src.includes(`${path.sep}.logs`) });
});

after(() => {
  fs.rmSync(projectRoot, { recursive: true, force: true });
});

describe('pre-tool-guard: scout guard', () => {
  it('denies a file tool reading inside a blocked directory', () => {
    const out = guard('view', { path: 'node_modules/react/index.js' });
    assert.strictEqual(out.permissionDecision, 'deny');
    assert.match(out.permissionDecisionReason, /node_modules/);
  });

  it('denies a glob whose pattern targets a blocked directory', () => {
    assert.strictEqual(guard('glob', { pattern: 'dist/**/*.js' }).permissionDecision, 'deny');
  });

  it('denies a shell scan of a blocked directory', () => {
    assert.strictEqual(guard('bash', { command: 'ls -la node_modules' }).permissionDecision, 'deny');
    assert.strictEqual(guard('bash', { command: 'cd src && cat ../build/out.js' }).permissionDecision, 'deny');
  });

  it('allows source paths', () => {
    assert.deepStrictEqual(guard('view', { path: 'src/builder/index.ts' }), {});
    assert.deepStrictEqual(guard('bash', { command: 'ls src' }), {});
  });

  it('allows build and package commands that merely name a blocked directory', () => {
    assert.deepStrictEqual(guard('bash', { command: 'npm run build' }), {});
    assert.deepStrictEqual(guard('bash', { command: 'rm -rf dist && pnpm install' }), {});
  });

  it('allows scans that exclude a blocked directory', () => {
    assert.deepStrictEqual(guard('bash', { command: "find . -type f -not -path '*/node_modules/*'" }), {});
    assert.deepStrictEqual(guard('bash', { command: 'grep -r TODO --exclude-dir=node_modules src' }), {});
    assert.deepStrictEqual(guard('bash', { command: 'grep -r TODO --exclude-dir node_modules src' }), {});
    assert.deepStrictEqual(guard('bash', { command: "rg TODO -g '!dist' src" }), {});
    assert.deepStrictEqual(guard('bash', { command: 'find . -name node_modules -prune -o -name "*.ts" -print' }), {});
  });

  it('treats the first grep argument as a pattern and still checks the path after it', () => {
    assert.deepStrictEqual(guard('bash', { command: 'grep -rn build src' }), {});
    assert.deepStrictEqual(guard('bash', { command: 'cat src/a.ts | grep dist' }), {});
    assert.deepStrictEqual(guard('bash', { command: 'find . -name build -type d' }), {});
    assert.strictEqual(guard('bash', { command: 'grep -rn TODO node_modules' }).permissionDecision, 'deny');
    assert.strictEqual(guard('bash', { command: 'rg -e TODO vendor' }).permissionDecision, 'deny');
  });

  it('ignores blocked names inside file contents and search patterns', () => {
    assert.deepStrictEqual(guard('edit', { path: 'README.md', new_str: 'ignore node_modules and dist' }), {});
    assert.deepStrictEqual(guard('grep', { pattern: 'node_modules', path: 'src' }), {});
  });

  it('reads paths from an apply_patch style argument', () => {
    const patch = '*** Begin Patch\n*** Update File: vendor/lib/a.go\n@@\n-x\n+y\n*** End Patch';
    assert.strictEqual(guard('apply_patch', { input: patch }).permissionDecision, 'deny');
  });

  it('accepts the snake_case payload with tool_input as a JSON string', () => {
    const out = runHook('pre-tool-guard.cjs', {
      session_id: 's1',
      tool_name: 'view',
      tool_input: JSON.stringify({ file_path: '.git/config' })
    }).output;
    assert.strictEqual(out.permissionDecision, 'deny');
  });

  it('honours a negation in .kitignore', () => {
    const kitignore = path.join(kitDir, '.kitignore');
    const original = fs.readFileSync(kitignore, 'utf8');
    fs.writeFileSync(kitignore, original + '\n!vendor\ngenerated\n');
    try {
      assert.deepStrictEqual(guard('view', { path: 'vendor/lib/a.go' }), {});
      assert.strictEqual(guard('view', { path: 'generated/api.ts' }).permissionDecision, 'deny');
    } finally {
      fs.writeFileSync(kitignore, original);
    }
  });

  it('can be disabled in config', () => {
    writeConfig({ guards: { scout: false } });
    try {
      assert.deepStrictEqual(guard('view', { path: 'node_modules/react/index.js' }), {});
    } finally {
      fs.cpSync(path.join(KIT_SOURCE, 'config.json'), path.join(kitDir, 'config.json'));
    }
  });
});

describe('pre-tool-guard: privacy guard', () => {
  it('asks before a file tool reads a secret-like file', () => {
    for (const file of ['.env', '.env.production', 'config/credentials.json', 'secrets.yaml', 'certs/server.pem', 'deploy.key', '/home/u/.ssh/id_rsa']) {
      assert.strictEqual(guard('view', { path: file }).permissionDecision, 'ask', file);
    }
  });

  it('asks before a shell command touches a secret-like file', () => {
    assert.strictEqual(guard('bash', { command: 'cat .env' }).permissionDecision, 'ask');
    assert.strictEqual(guard('bash', { command: 'cp certs/server.pem /tmp/x' }).permissionDecision, 'ask');
  });

  it('allows example and template files', () => {
    assert.deepStrictEqual(guard('view', { path: '.env.example' }), {});
    assert.deepStrictEqual(guard('view', { path: 'secrets.yaml.template' }), {});
  });

  it('allows ordinary files with similar names', () => {
    assert.deepStrictEqual(guard('view', { path: 'src/environment.ts' }), {});
    assert.deepStrictEqual(guard('view', { path: 'docs/keyboard.md' }), {});
  });

  it('can be disabled in config', () => {
    writeConfig({ guards: { privacy: false } });
    try {
      assert.deepStrictEqual(guard('view', { path: '.env' }), {});
    } finally {
      fs.cpSync(path.join(KIT_SOURCE, 'config.json'), path.join(kitDir, 'config.json'));
    }
  });
});

describe('pre-tool-guard: failure policy', () => {
  it('exits 0 and allows on empty stdin', () => {
    const result = runHook('pre-tool-guard.cjs', null, '');
    assert.strictEqual(result.status, 0);
    assert.deepStrictEqual(result.output, {});
  });

  it('exits 0 and allows on malformed stdin', () => {
    const result = runHook('pre-tool-guard.cjs', null, '{not json');
    assert.strictEqual(result.status, 0);
    assert.deepStrictEqual(result.output, {});
  });

  it('exits 0 and allows when config.json is unparsable', () => {
    fs.writeFileSync(path.join(kitDir, 'config.json'), '{broken');
    try {
      const result = runHook('pre-tool-guard.cjs', { toolName: 'view', toolArgs: { path: 'src/a.ts' } });
      assert.strictEqual(result.status, 0);
      assert.deepStrictEqual(result.output, {});
    } finally {
      fs.cpSync(path.join(KIT_SOURCE, 'config.json'), path.join(kitDir, 'config.json'));
    }
  });

  it('prints exactly one JSON object', () => {
    const result = runHook('pre-tool-guard.cjs', { toolName: 'view', toolArgs: { path: '.env' } });
    assert.doesNotThrow(() => JSON.parse(result.stdout));
  });
});

describe('session-start-context', () => {
  it('injects session, rules, paths and naming resolved from the project root', () => {
    const { status, output } = runHook('session-start-context.cjs', { sessionId: 's1', cwd: path.join(projectRoot, 'src'), source: 'startup' });
    assert.strictEqual(status, 0);
    const context = output.additionalContext;
    for (const heading of ['## Session', '## Rules', '## Paths', '## Active Plan', '## Naming']) {
      assert.ok(context.includes(heading), heading);
    }
    assert.ok(context.includes(`- Plans: ${path.join(projectRoot, 'plans')}`));
    assert.ok(context.includes(`- Reports: ${path.join(projectRoot, 'plans', 'reports')}`));
    assert.match(context, /\{type\}-\d{6}-\d{4}-\{slug\}-report\.md/);
    assert.ok(context.includes('- none'));
  });

  it('adds language and assertions from config', () => {
    writeConfig({ locale: { responseLanguage: 'Vietnamese' }, assertions: ['Never edit generated files'] });
    try {
      const context = runHook('session-start-context.cjs', { sessionId: 's1' }).output.additionalContext;
      assert.ok(context.includes('Respond to the user in: Vietnamese'));
      assert.ok(context.includes('- Never edit generated files'));
    } finally {
      fs.cpSync(path.join(KIT_SOURCE, 'config.json'), path.join(kitDir, 'config.json'));
    }
  });

  it('falls back to the default directory when a configured path escapes the project', () => {
    writeConfig({ paths: { plans: '../outside' } });
    try {
      const context = runHook('session-start-context.cjs', { sessionId: 's1' }).output.additionalContext;
      assert.ok(context.includes(`- Plans: ${path.join(projectRoot, 'plans')}`));
    } finally {
      fs.cpSync(path.join(KIT_SOURCE, 'config.json'), path.join(kitDir, 'config.json'));
    }
  });
});

describe('active plan', () => {
  const planDir = () => path.join(projectRoot, 'plans', '261119-2340-sample-feature');
  const setPlan = (...args) => spawnSync('node', [path.join(kitDir, 'scripts', 'set-active-plan.cjs'), ...args], { encoding: 'utf8', cwd: projectRoot });

  it('rejects a directory without plan.md', () => {
    fs.mkdirSync(planDir(), { recursive: true });
    assert.strictEqual(setPlan(planDir()).status, 1);
  });

  it('records the plan and both hooks report it with plan-scoped reports', () => {
    fs.writeFileSync(path.join(planDir(), 'plan.md'), '# Sample\n');
    assert.strictEqual(setPlan(path.join(planDir(), 'plan.md')).status, 0);

    const session = runHook('session-start-context.cjs', { sessionId: 's2' }).output.additionalContext;
    assert.ok(session.includes(`- ${planDir()}`));
    assert.ok(session.includes(`- Reports: ${path.join(planDir(), 'reports')}`));

    const subagent = runHook('subagent-start-context.cjs', { sessionId: 's2', agentName: 'tester' }).output.additionalContext;
    assert.ok(subagent.includes('- Agent: tester'));
    assert.ok(subagent.includes(`- ${planDir()}`));
    assert.ok(subagent.includes('Status: DONE | DONE_WITH_CONCERNS | BLOCKED | NEEDS_CONTEXT'));
  });

  it('treats a deleted plan directory as no active plan', () => {
    fs.rmSync(planDir(), { recursive: true, force: true });
    const session = runHook('session-start-context.cjs', { sessionId: 's3' }).output.additionalContext;
    assert.ok(session.includes('- none'));
  });

  it('clears the active plan', () => {
    assert.strictEqual(setPlan('--clear').status, 0);
    assert.ok(!fs.existsSync(path.join(projectRoot, 'plans', '.active-plan')));
  });
});

describe('hook registration file', () => {
  it('registers every hook script that exists, under camelCase events', () => {
    const registration = JSON.parse(fs.readFileSync(path.join(KIT_SOURCE, '..', 'hooks', 'copilot-kit.json'), 'utf8'));
    assert.strictEqual(registration.version, 1);
    assert.deepStrictEqual(Object.keys(registration.hooks).sort(), ['preToolUse', 'sessionStart', 'subagentStart']);
    for (const entries of Object.values(registration.hooks)) {
      for (const entry of entries) {
        const script = entry.command.replace(/^node /, '');
        assert.ok(fs.existsSync(path.join(KIT_SOURCE, '..', '..', script)), script);
      }
    }
  });
});
