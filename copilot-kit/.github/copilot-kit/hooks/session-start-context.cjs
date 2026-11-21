#!/usr/bin/env node
/**
 * session-start-context.cjs - sessionStart hook.
 *
 * Injects the session, rules, paths, active plan and naming blocks once per
 * session. Copilot CLI has no per-prompt context injection, so everything the
 * agent must keep following is stated here and in copilot-instructions.md.
 */

const { runHook } = require('./lib/hook-io.cjs');
const { loadConfig, resolveKitPaths, buildSessionContext } = require('./lib/kit-context.cjs');

runHook('session-start-context', (input) => {
  const config = loadConfig();
  return { additionalContext: buildSessionContext(config, resolveKitPaths(config), input) };
});
