#!/usr/bin/env node
/**
 * subagent-start-context.cjs - subagentStart hook.
 *
 * Subagents start without the parent conversation or the session-start context,
 * so this prepends the paths, active plan, naming pattern and reply format to
 * every custom agent's prompt.
 */

const { runHook } = require('./lib/hook-io.cjs');
const { loadConfig, resolveKitPaths, buildSubagentContext } = require('./lib/kit-context.cjs');

runHook('subagent-start-context', (input) => {
  const config = loadConfig();
  return { additionalContext: buildSubagentContext(config, resolveKitPaths(config), input) };
});
