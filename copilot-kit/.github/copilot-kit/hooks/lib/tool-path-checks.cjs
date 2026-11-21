/**
 * tool-path-checks.cjs - decide whether a tool call touches a heavy directory
 * (scout guard) or a secret-like file (privacy guard).
 *
 * Copilot CLI does not document the argument names of its tools, so paths are
 * found by key shape (path, file, dir, glob, ...) instead of a fixed schema.
 * Free-text arguments such as file contents or search patterns are never read,
 * which keeps an edit that merely mentions "node_modules" from being blocked.
 */

const fs = require('fs');
const path = require('path');

const SHELL_TOOLS = new Set(['bash', 'powershell', 'shell']);

/** Shell commands that read or list files; only these can flood the context. */
const SCAN_COMMANDS = new Set([
  'ls', 'tree', 'find', 'fd', 'cat', 'bat', 'head', 'tail', 'less', 'more',
  'grep', 'egrep', 'rg', 'ag', 'ack', 'du', 'wc', 'sed', 'awk', 'xargs', 'dir',
  'get-childitem', 'get-content', 'select-string', 'type'
]);

/** Flags whose following argument names something to skip, not something to read. */
const EXCLUDE_FLAGS = new Set(['--exclude', '--exclude-dir', '--ignore', '-I']);

const DEFAULT_BLOCKED = [
  'node_modules', 'dist', 'build', '.next', '.nuxt', '__pycache__', '.venv', 'venv',
  'vendor', 'target', '.git', 'coverage'
];

const PATH_KEY = /(path|paths|file|files|filename|dir|directory|folder|glob|include)$/;
const PATCH_FILE_LINE = /^\*\*\* (?:Add|Update|Delete) File: (.+)$/gm;

/** Blocked directory names: defaults, plus `.kitignore` additions, minus `!name` lines. */
function loadBlockedNames(kitignorePath) {
  const blocked = new Set(DEFAULT_BLOCKED);
  let text = '';
  try {
    text = fs.readFileSync(kitignorePath, 'utf8');
  } catch (_) {
    return blocked;
  }
  for (const rawLine of text.split('\n')) {
    const line = rawLine.trim().replace(/\/+$/, '');
    if (!line || line.startsWith('#')) continue;
    if (line.startsWith('!')) blocked.delete(line.slice(1));
    else blocked.add(line);
  }
  return blocked;
}

/** Split a shell command into tokens, keeping quoted strings whole and dropping the quotes. */
function tokenize(command) {
  const matches = String(command).match(/"[^"]*"|'[^']*'|[^\s;|&()<>]+/g) || [];
  return matches.map((token) => token.replace(/^["']|["']$/g, ''));
}

/** Split on shell separators so each sub-command is judged by its own first word. */
function splitSegments(command) {
  return String(command).split(/&&|\|\||[;|\n]/).map((s) => s.trim()).filter(Boolean);
}

/** First word of a segment, skipping `VAR=value` prefixes and `sudo`. */
function commandName(tokens) {
  for (const token of tokens) {
    if (/^[A-Za-z_][A-Za-z0-9_]*=/.test(token) || token === 'sudo') continue;
    return path.basename(token).toLowerCase();
  }
  return '';
}

/** Commands whose first plain argument is a search pattern or script, not a path. */
const PATTERN_FIRST_COMMANDS = new Set(['grep', 'egrep', 'rg', 'ag', 'ack', 'sed', 'awk', 'select-string']);

/** Flags whose following argument is a name filter or an explicit pattern, not a path. */
const NAME_FILTER_FLAGS = new Set(['-name', '-iname', '-e', '--regexp']);

/** Tokens of one segment that name something to read, with patterns and exclusions removed. */
function readTargets(tokens, name) {
  const targets = [];
  let patternPending = PATTERN_FIRST_COMMANDS.has(name) && !tokens.includes('-e') && !tokens.includes('-f');
  for (let i = 0; i < tokens.length; i++) {
    const token = tokens[i];
    const previous = tokens[i - 1];
    const beforePrevious = tokens[i - 2];
    if (NAME_FILTER_FLAGS.has(previous)) continue;
    if (patternPending && !token.startsWith('-')) {
      patternPending = false;
      continue;
    }
    if (token.startsWith('!')) continue;
    if (token.startsWith('--exclude') || token.startsWith('--ignore')) continue;
    if (EXCLUDE_FLAGS.has(previous)) continue;
    if (previous === '-not' || previous === '!' || beforePrevious === '-not' || beforePrevious === '!') continue;
    if (tokens[i + 1] === '-prune') continue;
    targets.push(token);
  }
  return targets;
}

/** String values stored under path-like keys, searched a few levels deep. */
function collectPathArgs(args, toolName, depth = 0, found = []) {
  if (!args || typeof args !== 'object' || depth > 3) return found;
  for (const [key, value] of Object.entries(args)) {
    const lowerKey = key.toLowerCase();
    const isPathKey = PATH_KEY.test(lowerKey) || (toolName === 'glob' && lowerKey === 'pattern');
    if (typeof value === 'string') {
      if (isPathKey) found.push(value);
    } else if (Array.isArray(value)) {
      if (isPathKey) found.push(...value.filter((v) => typeof v === 'string'));
    } else if (value && typeof value === 'object') {
      collectPathArgs(value, toolName, depth + 1, found);
    }
  }
  return found;
}

/** File paths named by an apply_patch style argument. */
function collectPatchPaths(args) {
  const found = [];
  for (const value of Object.values(args || {})) {
    if (typeof value !== 'string') continue;
    for (const match of value.matchAll(PATCH_FILE_LINE)) found.push(match[1].trim());
  }
  return found;
}

/**
 * Paths a tool call will touch.
 * @returns {{scan: string[], all: string[]}} `scan` = read/list targets, `all` = every path seen
 */
function extractPaths(toolName, toolArgs) {
  if (SHELL_TOOLS.has(toolName)) {
    const command = toolArgs.command || toolArgs.script || toolArgs.cmd || toolArgs.value || '';
    const scan = [];
    const all = [];
    for (const segment of splitSegments(command)) {
      const tokens = tokenize(segment);
      const name = commandName(tokens);
      const args = tokens.slice(tokens.findIndex((t) => path.basename(t).toLowerCase() === name) + 1);
      all.push(...args);
      if (SCAN_COMMANDS.has(name)) scan.push(...readTargets(args, name));
    }
    return { scan, all };
  }
  const paths = [...collectPathArgs(toolArgs, toolName), ...collectPatchPaths(toolArgs)];
  return { scan: paths, all: paths };
}

/** The blocked directory name a path passes through, or null. */
function findBlockedSegment(candidate, blocked) {
  for (const segment of String(candidate).split(/[\\/]+/)) {
    if (blocked.has(segment)) return segment;
  }
  return null;
}

/** True for files that usually hold secrets; example and template files are exempt. */
function isSensitivePath(candidate) {
  const base = path.basename(String(candidate).replace(/[\\/]+$/, '')).toLowerCase();
  if (!base || /\.(example|sample|template)$/.test(base)) return false;
  return base === '.env' ||
    base.startsWith('.env.') ||
    base.includes('credentials') ||
    /^secrets?\.ya?ml$/.test(base) ||
    /\.(pem|key)$/.test(base) ||
    base === 'id_rsa' ||
    base === 'id_ed25519';
}

module.exports = {
  DEFAULT_BLOCKED,
  loadBlockedNames,
  extractPaths,
  findBlockedSegment,
  isSensitivePath
};
