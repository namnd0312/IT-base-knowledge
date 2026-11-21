/**
 * hook-io.cjs - stdin/stdout plumbing shared by every Copilot CLI hook in this kit.
 *
 * Copilot CLI sends one JSON object on stdin and reads one JSON object from stdout.
 * Field names are camelCase when the event is registered in camelCase (this kit),
 * and snake_case when registered in PascalCase, so input is normalized here.
 *
 * Failure policy: every hook exits 0 with `{}` on any internal error. This matters
 * most for preToolUse, where Copilot treats a non-zero exit as a denial; a crashing
 * guard must never lock the agent out of all tools.
 */

const fs = require('fs');
const path = require('path');

const KIT_DIR = path.join(__dirname, '..', '..');
const PROJECT_ROOT = path.join(KIT_DIR, '..', '..');
const LOG_DIR = path.join(KIT_DIR, '.logs');
const LOG_FILE = path.join(LOG_DIR, 'hook-log.jsonl');
const MAX_LOG_LINES = 1000;
const KEEP_LOG_LINES = 500;

/** Read all of stdin synchronously; empty string when nothing is piped. */
function readStdin() {
  try {
    return fs.readFileSync(0, 'utf8');
  } catch (_) {
    return '';
  }
}

/** Tool arguments arrive as an object or as a JSON string depending on the payload format. */
function parseToolArgs(value) {
  if (value && typeof value === 'object') return value;
  if (typeof value === 'string') {
    try {
      const parsed = JSON.parse(value);
      return parsed && typeof parsed === 'object' ? parsed : { value };
    } catch (_) {
      return { value };
    }
  }
  return {};
}

/** Map either payload format onto one shape the hooks can rely on. */
function normalizeInput(raw) {
  let data = {};
  try {
    data = raw ? JSON.parse(raw) : {};
  } catch (_) {
    data = {};
  }
  return {
    sessionId: data.sessionId || data.session_id || '',
    cwd: data.cwd || '',
    source: data.source || '',
    toolName: String(data.toolName || data.tool_name || '').toLowerCase(),
    toolArgs: parseToolArgs(data.toolArgs !== undefined ? data.toolArgs : data.tool_input),
    agentName: data.agentName || data.agent_name || data.agentType || data.agent_type || ''
  };
}

/** Append one JSON line; rotate so the log never grows without bound. Never throws. */
function logEvent(hook, data) {
  try {
    fs.mkdirSync(LOG_DIR, { recursive: true });
    fs.appendFileSync(LOG_FILE, JSON.stringify({ ts: new Date().toISOString(), hook, ...data }) + '\n');
    const lines = fs.readFileSync(LOG_FILE, 'utf8').split('\n');
    if (lines.length > MAX_LOG_LINES) {
      fs.writeFileSync(LOG_FILE, lines.slice(-KEEP_LOG_LINES).join('\n'));
    }
  } catch (_) {
    // Logging is best effort.
  }
}

/**
 * Run a hook handler with the kit's failure policy.
 * @param {string} name hook name used in the log
 * @param {(input: object) => object|void} handler returns the JSON object to print
 */
function runHook(name, handler) {
  let output = {};
  try {
    output = handler(normalizeInput(readStdin())) || {};
  } catch (error) {
    logEvent(name, { status: 'crash', error: String(error && error.message ? error.message : error) });
    output = {};
  }
  process.stdout.write(JSON.stringify(output));
  process.exit(0);
}

module.exports = { KIT_DIR, PROJECT_ROOT, LOG_FILE, normalizeInput, logEvent, runHook };
