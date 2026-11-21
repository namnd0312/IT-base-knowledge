/**
 * kit-context.cjs - config loading and the context blocks injected into the model.
 *
 * All paths resolve against the project root derived from this file's location,
 * not the process cwd, so the injected paths stay correct when the agent or a
 * subagent runs from a subdirectory.
 */

const fs = require('fs');
const path = require('path');
const { KIT_DIR, PROJECT_ROOT } = require('./hook-io.cjs');

const DEFAULT_CONFIG = {
  paths: { plans: 'plans', docs: 'docs' },
  plan: { reportsDir: 'reports' },
  guards: { scout: true, privacy: true },
  locale: { responseLanguage: null },
  assertions: []
};

const ACTIVE_PLAN_FILE = '.active-plan';

/** Merge plain objects; arrays and scalars from `override` replace the base value. */
function deepMerge(base, override) {
  const result = { ...base };
  for (const [key, value] of Object.entries(override || {})) {
    const isPlain = value && typeof value === 'object' && !Array.isArray(value);
    result[key] = isPlain && base[key] && typeof base[key] === 'object'
      ? deepMerge(base[key], value)
      : value;
  }
  return result;
}

/** Load `.github/copilot-kit/config.json`; a missing or unparsable file means defaults. */
function loadConfig() {
  try {
    const raw = fs.readFileSync(path.join(KIT_DIR, 'config.json'), 'utf8');
    return deepMerge(DEFAULT_CONFIG, JSON.parse(raw));
  } catch (_) {
    return DEFAULT_CONFIG;
  }
}

/** Resolve a configured directory, refusing values that escape the project root. */
function resolveDir(value, fallback) {
  const candidate = path.resolve(PROJECT_ROOT, typeof value === 'string' && value ? value : fallback);
  const inside = candidate === PROJECT_ROOT || candidate.startsWith(PROJECT_ROOT + path.sep);
  return inside ? candidate : path.resolve(PROJECT_ROOT, fallback);
}

/** Absolute path of the active plan directory, or null when none is set or it no longer exists. */
function readActivePlan(plansDir) {
  try {
    const recorded = fs.readFileSync(path.join(plansDir, ACTIVE_PLAN_FILE), 'utf8').trim();
    if (!recorded) return null;
    const planDir = path.resolve(PROJECT_ROOT, recorded);
    return fs.statSync(planDir).isDirectory() ? planDir : null;
  } catch (_) {
    return null;
  }
}

/** Timestamp used in plan directory and report file names: YYMMDD-HHmm, local time. */
function formatTimestamp(date) {
  const pad = (n) => String(n).padStart(2, '0');
  return `${String(date.getFullYear()).slice(2)}${pad(date.getMonth() + 1)}${pad(date.getDate())}` +
    `-${pad(date.getHours())}${pad(date.getMinutes())}`;
}

/** Everything the context blocks need, computed once per hook run. */
function resolveKitPaths(config, now = new Date()) {
  const plansDir = resolveDir(config.paths && config.paths.plans, 'plans');
  const docsDir = resolveDir(config.paths && config.paths.docs, 'docs');
  const reportsName = (config.plan && config.plan.reportsDir) || 'reports';
  const activePlan = readActivePlan(plansDir);
  const reportsDir = path.join(activePlan || plansDir, reportsName);
  return { plansDir, docsDir, reportsDir, activePlan, timestamp: formatTimestamp(now) };
}

/** Blocks shared by the main session and subagents. */
function buildSharedBlocks(config, paths) {
  const lines = [
    '## Paths',
    `- Project root: ${PROJECT_ROOT}`,
    `- Plans: ${paths.plansDir}`,
    `- Docs: ${paths.docsDir}`,
    `- Reports: ${paths.reportsDir}`,
    '',
    '## Active Plan',
    paths.activePlan
      ? `- ${paths.activePlan} (read its plan.md before working on it)`
      : '- none (set one with `node .github/copilot-kit/scripts/set-active-plan.cjs <plan-dir>`)',
    '',
    '## Naming',
    `- Plan dir: ${path.join(paths.plansDir, `${paths.timestamp}-{slug}`)}/`,
    `- Report: ${path.join(paths.reportsDir, `{type}-${paths.timestamp}-{slug}-report.md`)}`,
    '- {type}: descriptive kebab-case purpose or handoff, e.g. `from-code-reviewer-to-planner-plan-review`',
    '- {slug}: descriptive kebab-case subject',
    '- The timestamp above is when this context was built; use the current time (YYMMDD-HHmm) for later files',
    '- Never use generic names such as report.md, review.md or notes.md'
  ];
  const language = config.locale && config.locale.responseLanguage;
  if (language) lines.push('', '## Language', `- Respond to the user in: ${language}`);
  return lines;
}

/** Context for the main agent at session start. */
function buildSessionContext(config, paths, input) {
  const lines = [
    '## Session',
    `- Started: ${new Date().toString()}`,
    `- Working directory: ${input.cwd || PROJECT_ROOT}`,
    `- Platform: ${process.platform}`,
    '',
    '## Rules',
    '- Follow `.github/copilot-instructions.md`.',
    '- Read `.github/copilot-kit/rules/development-rules.md` before editing code, tests, scripts or config.',
    '- Read the other files in `.github/copilot-kit/rules/` only when the task needs them.',
    '- Markdown you create belongs under the Plans or Docs path below unless the user asks otherwise.',
    '',
    ...buildSharedBlocks(config, paths)
  ];
  const assertions = Array.isArray(config.assertions) ? config.assertions.filter(Boolean) : [];
  if (assertions.length) lines.push('', '## Assertions', ...assertions.map((a) => `- ${a}`));
  return lines.join('\n');
}

/** Context prepended to a subagent's prompt. Subagents do not see the session-start context. */
function buildSubagentContext(config, paths, input) {
  return [
    '## Subagent',
    `- Agent: ${input.agentName || 'unknown'}`,
    '- You do not have the parent conversation. Work only from your task prompt and the files it names.',
    '- Advisory roles (review, research, brainstorm) report findings and do not change plan or code files.',
    '',
    '## Rules',
    '- Read `.github/copilot-kit/rules/development-rules.md` before editing code, tests, scripts or config.',
    '- Keep changes inside the files your task prompt allows you to modify.',
    '- Write reports to the Reports path below using the Naming pattern.',
    '',
    ...buildSharedBlocks(config, paths),
    '',
    '## Final Reply Format',
    'End your reply with:',
    'Status: DONE | DONE_WITH_CONCERNS | BLOCKED | NEEDS_CONTEXT',
    'Summary: one or two sentences',
    'Concerns/Blockers: optional'
  ].join('\n');
}

module.exports = {
  ACTIVE_PLAN_FILE,
  DEFAULT_CONFIG,
  deepMerge,
  loadConfig,
  resolveDir,
  resolveKitPaths,
  formatTimestamp,
  buildSessionContext,
  buildSubagentContext
};
