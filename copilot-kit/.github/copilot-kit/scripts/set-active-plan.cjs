#!/usr/bin/env node
/**
 * set-active-plan.cjs - record which plan the session and its subagents work on.
 *
 * Usage:
 *   node .github/copilot-kit/scripts/set-active-plan.cjs <plan-dir | plan.md>
 *   node .github/copilot-kit/scripts/set-active-plan.cjs --clear
 *
 * The plan is stored as a project-relative path in `<plans>/.active-plan`, so it
 * survives new sessions until it is replaced or cleared.
 */

const fs = require('fs');
const path = require('path');
const { PROJECT_ROOT } = require('../hooks/lib/hook-io.cjs');
const { ACTIVE_PLAN_FILE, loadConfig, resolveDir } = require('../hooks/lib/kit-context.cjs');

function fail(message) {
  console.error(message);
  process.exit(1);
}

const argument = process.argv[2];
if (!argument) fail('Usage: set-active-plan.cjs <plan-dir | plan.md> | --clear');

const config = loadConfig();
const plansDir = resolveDir(config.paths && config.paths.plans, 'plans');
const markerPath = path.join(plansDir, ACTIVE_PLAN_FILE);

if (argument === '--clear') {
  fs.rmSync(markerPath, { force: true });
  console.log('Active plan cleared.');
  process.exit(0);
}

let planDir = path.resolve(process.cwd(), argument);
if (!fs.existsSync(planDir)) planDir = path.resolve(PROJECT_ROOT, argument);
if (!fs.existsSync(planDir)) fail(`Plan path not found: ${argument}`);
if (fs.statSync(planDir).isFile()) planDir = path.dirname(planDir);
if (!fs.existsSync(path.join(planDir, 'plan.md'))) fail(`No plan.md in ${planDir}`);

const relative = path.relative(PROJECT_ROOT, planDir);
if (relative.startsWith('..')) fail(`Plan must be inside the project: ${planDir}`);

fs.mkdirSync(plansDir, { recursive: true });
fs.writeFileSync(markerPath, relative + '\n');
console.log(`Active plan: ${relative}`);
