/*
 * gen-astro-textures.js — 把 sprites.js 的 6 种程序化形状导出成 PNG
 *
 *   node web/tools/gen-astro-textures.js
 *
 * 产物：web/assets/particles/astro/{glow,star,spark,drop,rod,square}.png
 * 之后跑一次 sync-assets.js 重建清单。改形状后重跑本脚本即可。
 */
'use strict';

const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');
const os = require('os');

const CHROME = [
	'C:/Program Files/Google/Chrome/Application/chrome.exe',
	'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
].find((file) => fs.existsSync(file));
const PORT = 9336;
const PAGE = 'file:///' + path.resolve(__dirname, '..', 'index.html').replace(/\\/g, '/');
const OUT = path.resolve(__dirname, '..', 'assets', 'particles', 'astro');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
	if (!CHROME) throw new Error('chrome or edge not found');
	fs.mkdirSync(OUT, { recursive: true });

	const chrome = spawn(CHROME, [
		'--headless=new', '--disable-gpu', '--no-sandbox', '--hide-scrollbars',
		'--remote-debugging-port=' + PORT, '--user-data-dir=' + path.join(os.tmpdir(), 'ap-gen-' + Date.now()),
		'about:blank',
	], { stdio: 'ignore' });

	let wsUrl = null;
	for (let i = 0; i < 60 && !wsUrl; i++) {
		await sleep(150);
		try {
			const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
			const page = list.find((t) => t.type === 'page');
			if (page) wsUrl = page.webSocketDebuggerUrl;
		} catch (err) { /* 等浏览器起来 */ }
	}
	if (!wsUrl) { chrome.kill(); throw new Error('no debugging target'); }

	const ws = new WebSocket(wsUrl);
	let nextId = 1;
	const pending = new Map();
	ws.addEventListener('message', (event) => {
		const msg = JSON.parse(event.data);
		if (msg.id && pending.has(msg.id)) { pending.get(msg.id)(msg); pending.delete(msg.id); }
	});
	await new Promise((resolve) => ws.addEventListener('open', resolve));

	const send = (method, params) => new Promise((resolve) => {
		const id = nextId++;
		pending.set(id, resolve);
		ws.send(JSON.stringify({ id, method, params: params || {} }));
	});

	await send('Page.enable');
	await send('Page.navigate', { url: PAGE });
	await sleep(1200);

	const response = await send('Runtime.evaluate', {
		returnByValue: true,
		expression: `JSON.stringify(window.APShapes.SHAPES.map(function (shape) {
			var canvas = window.APShapes.makeSprite(shape, 64);
			return { shape: shape, data: canvas.toDataURL('image/png') };
		}))`,
	});
	const raw = response.result && response.result.result && response.result.result.value;
	if (!raw) throw new Error('canvas export failed: ' + JSON.stringify(response.result || {}));

	for (const item of JSON.parse(raw)) {
		const base64 = item.data.replace(/^data:image\/png;base64,/, '');
		const file = path.join(OUT, item.shape + '.png');
		fs.writeFileSync(file, Buffer.from(base64, 'base64'));
		const size = fs.statSync(file).size;
		console.log(`[astro] ${item.shape.padEnd(7)} → ${path.relative(path.resolve(__dirname, '..', '..'), file)} (${size} B)`);
	}

	ws.close();
	chrome.kill();
	await sleep(200);
}

main().catch((err) => { console.error('[gen-astro] ' + err.message); process.exit(1); });
