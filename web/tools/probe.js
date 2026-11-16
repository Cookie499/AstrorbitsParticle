/*
 * probe.js — 用 CDP 打开 index.html，读运行时状态、抓脚本错误、按需截图
 *
 *   node tools/probe.js                       # 默认打开首屏，打印诊断
 *   node tools/probe.js "?preset=spiral&age=2" # 指定 URL 参数
 *
 * 不依赖 puppeteer：Node 22+ 自带 WebSocket，直接讲 DevTools 协议。
 */
'use strict';

const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');

const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const PORT = 9333;
const PAGE = 'file:///' + path.resolve(__dirname, '..', 'index.html').replace(/\\/g, '/');

	const query = process.argv[2] || '';
	const shotPath = process.argv[3] || null;
	const extraEvalArg = process.argv.slice(4).join(' ').trim();

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
	const profile = path.join(require('os').tmpdir(), 'ap-probe-' + Date.now());
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
		} catch (e) { /* 还没起来 */ }
	}
	if (!wsUrl) { chrome.kill(); throw new Error('chrome did not expose a debugging target'); }

	const ws = new WebSocket(wsUrl);
	let nextId = 1;
	const pending = new Map();
	const logs = [];

	ws.addEventListener('message', (event) => {
		const msg = JSON.parse(event.data);
		if (msg.id && pending.has(msg.id)) {
			pending.get(msg.id)(msg);
			pending.delete(msg.id);
		} else if (msg.method === 'Runtime.exceptionThrown') {
			logs.push('EXCEPTION ' + JSON.stringify(msg.params.exceptionDetails.exception?.description ||
				msg.params.exceptionDetails.text));
		} else if (msg.method === 'Runtime.consoleAPICalled') {
			const text = msg.params.args.map((a) => a.value ?? a.description ?? a.type).join(' ');
			logs.push(msg.params.type.toUpperCase() + ' ' + text);
		} else if (msg.method === 'Log.entryAdded') {
			logs.push('LOG[' + msg.params.entry.level + '] ' + msg.params.entry.text);
		}
	});

	await new Promise((resolve) => ws.addEventListener('open', resolve));

	const send = (method, params) => new Promise((resolve) => {
		const id = nextId++;
		pending.set(id, resolve);
		ws.send(JSON.stringify({ id, method, params: params || {} }));
	});

	const evaluate = async (expression) => {
		const res = await send('Runtime.evaluate', {
			expression, returnByValue: true, awaitPromise: true, userGesture: true,
		});
		if (res.result && res.result.exceptionDetails) {
			return { error: res.result.exceptionDetails.exception?.description || 'eval failed' };
		}
		return res.result && res.result.result ? res.result.result.value : undefined;
	};

	await send('Runtime.enable');
	await send('Log.enable');
	await send('Page.enable');
	await send('Emulation.setDeviceMetricsOverride', {
		width: 1680, height: 1000, deviceScaleFactor: 1, mobile: false,
	});

	await send('Page.navigate', { url: PAGE + query });
	await sleep(1400);

	const report = await evaluate(`(() => {
		const s = window.__studio && window.__studio.state;
		if (!s) return { boot: 'no __studio (script error?)', bootError: document.getElementById('bootError').textContent };
		const p = s.playback;
		const buf = p ? p.frame(s.age) : null;
		let maxR = 0, avgSize = 0, avgAlpha = 0;
		if (buf && buf.count) {
			for (let i = 0; i < buf.count; i++) {
				maxR = Math.max(maxR, Math.hypot(buf.x[i], buf.y[i], buf.z[i]));
				avgSize += buf.size[i]; avgAlpha += buf.alpha[i];
			}
			avgSize /= buf.count; avgAlpha /= buf.count;
		}
		const c = document.getElementById('view');
		return {
			style: s.def.style,
			age: +s.age.toFixed(2),
			playing: s.playing,
			seed: s.seed,
			particles: buf ? buf.count : -1,
			maxRadius: +maxR.toFixed(2),
			avgSize: +avgSize.toFixed(3),
			avgAlpha: +avgAlpha.toFixed(3),
			boundRadius: s.bound ? +s.bound.radius.toFixed(2) : null,
			camera: { yaw: +s.camera.yaw.toFixed(0), pitch: +s.camera.pitch.toFixed(0), dist: +s.camera.dist.toFixed(1), targetY: +s.camera.target[1].toFixed(1) },
			canvas: c ? c.width + 'x' + c.height : null,
			rows: document.querySelectorAll('.row').length,
			sections: document.querySelectorAll('.section').length,
			problems: s.problems,
			bootError: document.getElementById('bootError').hidden ? null : document.getElementById('bootError').textContent,
			diagDot: !document.getElementById('diagDot').hidden,
			jsonLen: document.getElementById('outputBody').textContent.length,
			fps: Math.round(s.fps || 0),
		};
	})()`);

	console.log(JSON.stringify(report, null, 2));
	if (logs.length) {
		console.log('\n--- console ---');
		for (const line of logs.slice(0, 30)) console.log(line);
	} else {
		console.log('\n[console] clean');
	}

	if (extraEvalArg) {
		const out = await evaluate(extraEvalArg);
		console.log('\n[eval] ' + JSON.stringify(out, null, 2));
	}

	if (shotPath) {
		await sleep(300);
		const shot = await send('Page.captureScreenshot', { format: 'png' });
		fs.writeFileSync(shotPath, Buffer.from(shot.result.data, 'base64'));
		console.log('\n[shot] ' + shotPath);
	}

	ws.close();
	chrome.kill();
	await sleep(200);
}

main().catch((err) => { console.error('[probe] ' + err.message); process.exit(1); });
