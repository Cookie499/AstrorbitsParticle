/*
 * golden-check.js — 用仓库自己的黄金基线验证 JS 引擎
 *
 *   node web/tools/golden-check.js
 *
 * 对 9 个测试预设、seed=12345、age ∈ {0, 0.5, 1, 2} 采样，与 golden/<name>.txt 比对：
 * 粒子数必须逐块相等，argb 与位置/尺寸允许的偏差分别单列。
 */
'use strict';

const fs = require('fs');
const path = require('path');

const C = require('../core.js');
const S = require('../styles.js');
const E = require('../effect.js');
const P = require('../presets.js');

const REPO = path.resolve(__dirname, '..', '..');
const GOLDEN = path.join(REPO, 'golden');
const AGES = [0, 0.5, 1, 2];
const SEED = 12345;

function fmt(v) {
	const s = v.toFixed(5);
	if (s === '0.00000' && Object.is(v, -0)) return '-0.00000';
	return s;
}

function render(bound) {
	const rand = new C.Rand(SEED);
	const out = [];
	for (const age of AGES) {
		const buffer = E.sampleRaw(bound, rand, age);
		out.push(`age=${age.toFixed(1)} count=${buffer.count}`);
		for (let i = 0; i < buffer.count; i++) {
			out.push(`${fmt(buffer.x[i])},${fmt(buffer.y[i])},${fmt(buffer.z[i])},` +
				`${fmt(buffer.size[i])},${(buffer.argb[i] >>> 0).toString(16)}`);
		}
	}
	return out;
}

function parseGolden(text) {
	const blocks = [];
	let current = null;
	for (const line of text.split(/\r?\n/)) {
		if (!line) continue;
		const head = /^age=([-\d.]+) count=(\d+)$/.exec(line);
		if (head) {
			current = { age: Number(head[1]), count: Number(head[2]), rows: [] };
			blocks.push(current);
			continue;
		}
		if (!current) continue;
		const parts = line.split(',');
		current.rows.push({
			x: Number(parts[0]), y: Number(parts[1]), z: Number(parts[2]),
			size: Number(parts[3]), argb: parseInt(parts[4], 16) >>> 0,
		});
	}
	return blocks;
}

let failures = 0;
const summary = [];

for (const preset of P.TEST) {
	const file = path.join(GOLDEN, preset.id + '.txt');
	if (!fs.existsSync(file)) {
		console.log(`[skip] ${preset.id}: no baseline`);
		continue;
	}
	const def = E.parseDefinition(preset.json);
	const { bound, problems } = E.bind(def);
	if (problems.length) {
		console.log(`[warn] ${preset.id}: ${problems.join('; ')}`);
	}
	const actual = render(bound);
	const expected = parseGolden(fs.readFileSync(file, 'utf8'));

	let maxPos = 0, maxSize = 0, argbMismatch = 0, total = 0, countMismatch = 0;
	let cursor = 0;
	for (const block of expected) {
		const head = actual[cursor++];
		const m = /^age=[-\d.]+ count=(\d+)$/.exec(head || '');
		const gotCount = m ? Number(m[1]) : -1;
		if (gotCount !== block.count) countMismatch++;
		const n = Math.min(gotCount, block.count);
		for (let i = 0; i < n; i++) {
			const parts = actual[cursor + i].split(',');
			const row = block.rows[i];
			maxPos = Math.max(maxPos,
				Math.abs(Number(parts[0]) - row.x),
				Math.abs(Number(parts[1]) - row.y),
				Math.abs(Number(parts[2]) - row.z));
			maxSize = Math.max(maxSize, Math.abs(Number(parts[3]) - row.size));
			if ((parseInt(parts[4], 16) >>> 0) !== row.argb) argbMismatch++;
			total++;
		}
		cursor += gotCount;
	}

	const ok = countMismatch === 0 && maxPos <= 5e-5 && maxSize <= 5e-5 && argbMismatch === 0;
	if (!ok) failures++;
	summary.push({
		preset: preset.id, ok,
		countMismatch, maxPos, maxSize, argbMismatch, total,
		expectedLines: expected.length, actualLines: actual.length,
	});
}

const pad = (s, n) => String(s).padEnd(n);
console.log(pad('preset', 12) + pad('ok', 6) + pad('count≠', 8) + pad('max|Δpos|', 12) +
	pad('max|Δsize|', 12) + pad('argb≠', 8) + 'particles');
for (const row of summary) {
	console.log(
		pad(row.preset, 12) +
		pad(row.ok ? 'PASS' : 'FAIL', 6) +
		pad(row.countMismatch, 8) +
		pad(row.maxPos.toPrecision(3), 12) +
		pad(row.maxSize.toPrecision(3), 12) +
		pad(row.argbMismatch, 8) +
		row.total,
	);
}

if (failures > 0) {
	console.log(`\n[fail] ${failures} preset(s) differ from the golden baseline`);
	process.exit(1);
}
console.log(`\n[ok] ${summary.length} presets match golden/*.txt (seed ${SEED}, ages ${AGES.join('/')})`);
