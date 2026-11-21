#!/usr/bin/env node
/**
 * pre-tool-guard.cjs - preToolUse hook.
 *
 * Scout guard: denies reads and scans of heavy directories listed in `.kitignore`.
 * Privacy guard: turns access to secret-like files into a real permission prompt
 * by returning `permissionDecision: "ask"`, so the user decides, not the model.
 *
 * Both guards run in one process because this hook fires on every tool call.
 * Toggle each with `guards.scout` / `guards.privacy` in config.json.
 */

const path = require('path');
const { KIT_DIR, runHook, logEvent } = require('./lib/hook-io.cjs');
const { loadConfig } = require('./lib/kit-context.cjs');
const {
  loadBlockedNames,
  extractPaths,
  findBlockedSegment,
  isSensitivePath
} = require('./lib/tool-path-checks.cjs');

runHook('pre-tool-guard', (input) => {
  if (!input.toolName) return {};

  const config = loadConfig();
  const guards = config.guards || {};
  const paths = extractPaths(input.toolName, input.toolArgs);

  if (guards.scout !== false) {
    const blocked = loadBlockedNames(path.join(KIT_DIR, '.kitignore'));
    for (const candidate of paths.scan) {
      const segment = findBlockedSegment(candidate, blocked);
      if (!segment) continue;
      logEvent('pre-tool-guard', { status: 'deny', tool: input.toolName, target: candidate, segment });
      return {
        permissionDecision: 'deny',
        permissionDecisionReason:
          `Blocked by the kit scout guard: "${candidate}" is inside "${segment}", a directory with too many ` +
          'files to read into context. Narrow the path to source files. To allow it, add ' +
          `"!${segment}" to .github/copilot-kit/.kitignore.`
      };
    }
  }

  if (guards.privacy !== false) {
    const sensitive = paths.all.find(isSensitivePath);
    if (sensitive) {
      logEvent('pre-tool-guard', { status: 'ask', tool: input.toolName, target: sensitive });
      return {
        permissionDecision: 'ask',
        permissionDecisionReason:
          `"${sensitive}" looks like a file that holds secrets. Approve only if the agent really needs it.`
      };
    }
  }

  return {};
});
