/*
 * uitest.js — 用 CDP 把界面点一遍，检查状态、DOM 与脚本错误
 *
 *   node tools/uitest.js
 */
'use strict';

const { spawn } = require('child_process');
const path = require('path');
const os = require('os');

const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const PORT = 9334;
const PAGE = 'file:///' + path.resolve(__dirname, '..', 'index.html').replace(/\\/g, '/');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const SCRIPT = `(async () => {
	const out = [];
	const check = (name, ok, detail) => out.push({ name, ok: !!ok, detail: detail === undefined ? '' : String(detail) });
	const frame = () => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
	const S = window.__studio;
	check('boot', !!S, document.getElementById('bootError').textContent);
	check('bootError hidden', document.getElementById('bootError').hidden);
	check('9 style cards', document.querySelectorAll('.style-card').length === 9, document.querySelectorAll('.style-card').length);
	check('13 preset cards', document.querySelectorAll('.preset-card').length === 13, document.querySelectorAll('.preset-card').length);
	check('8 sections', document.querySelectorAll('.section').length === 8);
	check('sections have icons', document.querySelectorAll('.section-head .ico use').length === 16,
		document.querySelectorAll('.section-head .ico use').length);
	check('8 section chevrons', document.querySelectorAll('.section-head .chev use').length === 8);

	// 逐个样式切换
	for (const card of document.querySelectorAll('.style-card')) {
		card.click();
		await frame();
		const styleId = S.state.def.style;
		const rows = document.querySelectorAll('.section[data-section="params"] .row').length;
		check('style ' + styleId.split(':')[1], styleId === card.dataset.style && rows > 0 && S.state.problems.length === 0, rows + ' rows');
	}

	// 模板库 → 点第一个模板
	document.getElementById('btnTemplates').click();
	await frame();
	check('template modal open', !document.getElementById('modal').hidden);
	const firstTpl = document.querySelector('.tpl-card');
	const tplName = firstTpl && firstTpl.querySelector('b').textContent;
	if (firstTpl) firstTpl.click();
	await frame();
	check('template applied', document.getElementById('modal').hidden && S.state.def.style.includes('radial'), tplName);
	document.querySelector('[data-tab="command"]').click();
	await frame();
	check('command detects test preset',
		document.getElementById('outputBody').textContent.includes('/astrorticle test radial'),
		document.getElementById('outputBody').textContent.split('\\n')[1]);
	document.querySelector('[data-tab="json"]').click();
	await frame();

	// 种子改变 → 采样结果改变
	S.state.age = 1;
	await frame();
	const before = Array.from(S.sample(1).x.subarray(0, 4)).map((v) => v.toFixed(3)).join(',');
	const seedInput = document.getElementById('seedInput');
	seedInput.value = '999';
	seedInput.dispatchEvent(new Event('change'));
	await frame();
	const after = Array.from(S.sample(1).x.subarray(0, 4)).map((v) => v.toFixed(3)).join(',');
	check('seed changes samples', before !== after, before + ' → ' + after);
	seedInput.value = '12345';
	seedInput.dispatchEvent(new Event('change'));
	await frame();

	// 数量改 2000 → bound 跟随
	const countRow = Array.from(document.querySelectorAll('.section[data-section="emission"] .row'))
		.find((row) => row.querySelector('.row-label').textContent === '粒子数');
	const countInput = countRow.querySelector('input');
	countInput.value = '2000';
	countInput.dispatchEvent(new Event('input'));
	countInput.dispatchEvent(new Event('change'));
	await frame();
	check('count 2000 rebound', S.state.bound.count === 2000 && S.state.def.emission.count === 2000, S.state.bound.count);
	countInput.value = '500';
	countInput.dispatchEvent(new Event('input'));
	countInput.dispatchEvent(new Event('change'));
	await frame();

	// 轨迹开关
	const trailSection = document.querySelector('.section[data-section="trail"]');
	trailSection.querySelector('.section-head').click();
	await frame();
	let toggle = trailSection.querySelector('.toggle input');
	toggle.click();
	await frame();
	check('trail enabled', !!S.state.def.trail, JSON.stringify(S.state.def.trail));
	const trailRows = trailSection.querySelectorAll('.row').length;
	check('trail rows expanded', trailRows >= 8, trailRows);

	// 溅射开关 + 地面
	const splashSection = document.querySelector('.section[data-section="splash"]');
	splashSection.querySelector('.section-head').click();
	await frame();
	toggle = splashSection.querySelector('.toggle input');
	toggle.click();
	await frame();
	check('splash enabled', !!S.state.def.splash);
	const ground = document.getElementById('chkGround');
	ground.checked = true;
	ground.dispatchEvent(new Event('change'));
	await frame();
	check('ground plane on', S.state.useGround && Number.isFinite(S.state.playback.groundY), S.state.playback.groundY);

	// 输出页签
	document.querySelector('[data-tab="command"]').click();
	await frame();
	const commandText = document.getElementById('outputBody').textContent;
	check('command tab', commandText.includes('/astrorticle effect play') && commandText.includes('my_effect'),
		commandText.split('\\n')[1]);
	document.querySelector('[data-tab="diag"]').click();
	await frame();
	check('diag tab', document.getElementById('outputBody').textContent.includes('参数问题'));
	check('diag shows sprite', document.getElementById('outputBody').textContent.includes('贴图'));

	// 原始配置页：可查看、可编辑、可应用
	document.querySelector('[data-tab="raw"]').click();
	await frame();
	const rawText = document.getElementById('rawText');
	check('raw tab visible', !document.getElementById('rawPane').hidden && rawText.value.length > 100, rawText.value.length);
	rawText.value = rawText.value.replace(/"count": \\d+/, '"count": 777');
	document.getElementById('btnRawApply').click();
	await frame();
	check('raw apply', S.state.def.emission.count === 777, S.state.def.emission.count);
	document.getElementById('btnRawRefill').click();
	await frame();
	check('raw refill', rawText.value.includes('777'));
	document.querySelector('[data-tab="json"]').click();
	await frame();

	// 贴图清单与导出
	const spriteSelect = document.querySelector('.section[data-section="appearance"] select.sel');
	check('sprite options', spriteSelect && spriteSelect.options.length > 10, spriteSelect ? spriteSelect.options.length : 0);
	check('download button', !!document.getElementById('btnDownload'));

	// 动画状态
	const ageBefore = S.state.age;
	document.getElementById('btnPlay').click();
	await frame();
	check('pause', S.state.playing === false);
	document.getElementById('btnPlay').click();
	await frame();
	check('play', S.state.playing === true);

	// 时间轴拖动
	const range = document.getElementById('timeRange');
	range.value = '1.5';
	range.dispatchEvent(new Event('input'));
	await frame();
	check('scrub', Math.abs(S.state.age - 1.5) < 1e-6, S.state.age);

	// 视图按钮
	document.querySelector('#camPresets button[data-cam="top"]').click();
	await frame();
	check('camera preset', S.state.camera.pitch > 60, S.state.camera.pitch);
	document.getElementById('btnFit').click();
	await frame();
	check('fit', S.state.camera.dist > 0 && S.state.camera.dist <= 48, S.state.camera.dist.toFixed(1));

	// 说明与导入
	document.getElementById('btnHelp').click();
	await frame();
	check('help modal', document.getElementById('modalBody').textContent.includes('快捷键'));
	document.getElementById('modalClose').click();
	await frame();
	document.getElementById('btnImport').click();
	await frame();
	const area = document.querySelector('#modalBody textarea');
	check('import textarea', !!area && area.value.length > 100, area ? area.value.length : 0);
	area.value = '{ broken json';
	Array.from(document.querySelectorAll('#modalFoot .btn')).find((b) => b.textContent.includes('载入')).click();
	await frame();
	check('import error handled', !document.getElementById('toast').hidden && document.getElementById('toast').className.includes('bad'));
	document.getElementById('modalClose').click();
	await frame();

	// 键盘
	const playingBefore = S.state.playing;
	const ageBeforeKey = S.state.age;
	window.dispatchEvent(new KeyboardEvent('keydown', { key: ' ' }));
	await frame();
	check('space toggles', S.state.playing === !playingBefore,
		'before=' + playingBefore + ' after=' + S.state.playing + ' age=' + ageBeforeKey.toFixed(2) + '→' + S.state.age.toFixed(2) + ' dur=' + S.state.def.duration);
	window.dispatchEvent(new KeyboardEvent('keydown', { key: '3' }));
	await frame();
	check('digit switches style', S.state.def.style.includes('ring'), S.state.def.style);

	// 曲线编辑器存在且可点
	document.querySelector('.section[data-section="appearance"] .section-head').click();
	await frame();
	const curve = document.querySelector('.curve-editor svg');
	check('curve editor', !!curve && curve.querySelectorAll('circle').length >= 2,
		curve ? curve.querySelectorAll('curve, circle').length : 0);

	// 样式参数：多选 chip、颜色继承、数值区间
	document.querySelectorAll('.style-card')[7].click();
	await frame();
	const chips = document.querySelectorAll('.section[data-section="params"] .chip-multi');
	check('ambience mode chips', chips.length === 3, chips.length);
	if (chips.length) { chips[0].click(); await frame(); }
	check('chip toggles param',
		Array.isArray(S.state.def.params.mode) && !S.state.def.params.mode.includes('drift'),
		JSON.stringify(S.state.def.params.mode));

	document.querySelectorAll('.style-card')[6].click();
	await frame();
	const inherit = Array.from(document.querySelectorAll('.section[data-section="params"] .toggle'))
		.find((el) => el.textContent.includes('继承外观颜色'));
	check('comet color inherit control', !!inherit);
	if (inherit) {
		inherit.querySelector('input').click();
		await frame();
		check('headColor set', typeof S.state.def.params.headColor === 'string', S.state.def.params.headColor);
	}
	check('no params problems after edits', S.state.problems.length === 0, S.state.problems.join(';'));

	return out;
})()`;

async function main() {
	const profile = path.join(os.tmpdir(), 'ap-uitest-' + Date.now());
	const chrome = spawn(CHROME, [
		'--headless=new', '--disable-gpu', '--no-sandbox', '--hide-scrollbars',
		'--remote-debugging-port=' + PORT, '--user-data-dir=' + profile,
		'--window-size=1680,1000', 'about:blank',
	], { stdio: 'ignore' });

	let wsUrl = null;
	for (let i = 0; i < 60 && !wsUrl; i++) {
		await sleep(150);
		try {
			const res = await fetch(`http://127.0.0.1:${PORT}/json/list`);
			const list = await res.json();
			const page = list.find((t) => t.type === 'page');
			if (page) wsUrl = page.webSocketDebuggerUrl;
		} catch (e) { /* 等 chrome */ }
	}
	if (!wsUrl) { chrome.kill(); throw new Error('chrome did not start'); }

	const ws = new WebSocket(wsUrl);
	let nextId = 1;
	const pending = new Map();
	const errors = [];

	ws.addEventListener('message', (event) => {
		const msg = JSON.parse(event.data);
		if (msg.id && pending.has(msg.id)) {
			pending.get(msg.id)(msg);
			pending.delete(msg.id);
		} else if (msg.method === 'Runtime.exceptionThrown') {
			errors.push('EXCEPTION ' + (msg.params.exceptionDetails.exception?.description || msg.params.exceptionDetails.text));
		} else if (msg.method === 'Runtime.consoleAPICalled' && msg.params.type === 'error') {
			errors.push('console.error ' + msg.params.args.map((a) => a.value ?? a.description).join(' '));
		}
	});
	await new Promise((resolve) => ws.addEventListener('open', resolve));

	const send = (method, params) => new Promise((resolve) => {
		const id = nextId++;
		pending.set(id, resolve);
		ws.send(JSON.stringify({ id, method, params: params || {} }));
	});

	const evaluate = async (expression, awaitPromise) => {
		const res = await send('Runtime.evaluate', {
			expression, returnByValue: true, awaitPromise: !!awaitPromise, userGesture: true,
		});
		const payload = res.result || {};
		if (payload.exceptionDetails) {
			throw new Error(payload.exceptionDetails.exception?.description || payload.exceptionDetails.text);
		}
		return payload.result ? payload.result.value : undefined;
	};

	await send('Runtime.enable');
	await send('Page.enable');
	await send('Page.navigate', { url: PAGE });
	await sleep(1500);

	let results;
	try {
		results = await evaluate(SCRIPT, true);
	} catch (err) {
		console.log('[fail] scenario threw: ' + err.message);
		chrome.kill();
		process.exit(1);
	}

	let failed = 0;
	for (const row of results) {
		if (!row.ok) failed++;
		console.log((row.ok ? 'PASS ' : 'FAIL ') + row.name.padEnd(28) + (row.detail ? '  ' + row.detail : ''));
	}
	console.log(`\n${results.length - failed}/${results.length} passed`);
	if (errors.length) {
		console.log('\n--- page errors ---');
		for (const line of errors) console.log(line);
	} else {
		console.log('page errors: none');
	}

	ws.close();
	chrome.kill();
	await sleep(200);
	process.exit(failed === 0 && errors.length === 0 ? 0 : 1);
}

main().catch((err) => { console.error('[uitest] ' + err.message); process.exit(1); });
