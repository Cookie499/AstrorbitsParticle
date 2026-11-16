/*
 * sync-assets.js — 收集粒子贴图，生成 web/assets/particles/manifest.js
 *
 *   node web/tools/sync-assets.js              # 自带近似贴图 + 模组贴图 + 重建清单
 *   node web/tools/sync-assets.js --vanilla    # 额外从 Loom 缓存解出原版粒子贴图
 *
 * 目录约定（web/assets/particles/）：
 *   astro/        仓库自带的近似贴图，由 tools/gen-astro-textures.js 生成
 *   minecraft/    从本机客户端 jar 解出的原版贴图，不进版本库
 *   <namespace>/  放在 src/client/resources/assets/<ns>/textures/particle/ 下的模组贴图，同步时复制过来
 *   其它手放的 PNG 也会被扫描：文件名即贴图名，命名空间取父目录名
 *
 * 清单是 JS 而不是 JSON：浏览器用 file:// 打开时 fetch 会被拦，脚本标签不会。
 */
'use strict';

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const ROOT = path.resolve(__dirname, '..', '..');
const WEB = path.join(ROOT, 'web');
const PARTICLES = path.join(WEB, 'assets', 'particles');
const MOD_ASSETS = path.join(ROOT, 'src', 'client', 'resources', 'assets');

// 自带近似贴图：sprite id → 文件。文件名对不上 manifest 时按这张表兜底。
const ASTRO_MAP = {
	'minecraft:generic_0': 'astro/square.png',
	'minecraft:generic_1': 'astro/square.png',
	'minecraft:generic_2': 'astro/square.png',
	'minecraft:glitter_0': 'astro/star.png',
	'minecraft:glitter_1': 'astro/star.png',
	'minecraft:glow': 'astro/glow.png',
	'minecraft:spark_0': 'astro/spark.png',
	'minecraft:spark_1': 'astro/spark.png',
	'minecraft:splash_0': 'astro/drop.png',
	'minecraft:end_rod': 'astro/rod.png',
	'minecraft:crit': 'astro/spark.png',
	'minecraft:enchanted_hit': 'astro/spark.png',
	'minecraft:flame': 'astro/glow.png',
	'minecraft:note': 'astro/star.png',
	'minecraft:smoke': 'astro/square.png',
	'minecraft:cloud': 'astro/square.png',
};

// ───────────────────────── 极简 zip 读取（只需要 stored / deflate） ─────────────────────────
function openZip(file) {
	const buf = fs.readFileSync(file);
	let eocd = -1;
	for (let i = buf.length - 22; i >= Math.max(0, buf.length - 66000); i--) {
		if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
	}
	if (eocd < 0) throw new Error('not a zip archive: ' + file);
	const count = buf.readUInt16LE(eocd + 10);
	let offset = buf.readUInt32LE(eocd + 16);
	const entries = new Map();
	for (let i = 0; i < count; i++) {
		if (buf.readUInt32LE(offset) !== 0x02014b50) throw new Error('bad central directory in ' + file);
		const method = buf.readUInt16LE(offset + 10);
		const compSize = buf.readUInt32LE(offset + 20);
		const nameLen = buf.readUInt16LE(offset + 28);
		const extraLen = buf.readUInt16LE(offset + 30);
		const commentLen = buf.readUInt16LE(offset + 32);
		const localOffset = buf.readUInt32LE(offset + 42);
		const name = buf.toString('utf8', offset + 46, offset + 46 + nameLen);
		entries.set(name, { method, compSize, localOffset });
		offset += 46 + nameLen + extraLen + commentLen;
	}

	function read(name) {
		const entry = entries.get(name);
		if (!entry) return null;
		if (buf.readUInt32LE(entry.localOffset) !== 0x04034b50) throw new Error('bad local header for ' + name);
		const nameLen = buf.readUInt16LE(entry.localOffset + 26);
		const extraLen = buf.readUInt16LE(entry.localOffset + 28);
		const start = entry.localOffset + 30 + nameLen + extraLen;
		const raw = buf.subarray(start, start + entry.compSize);
		if (entry.method === 0) return raw;
		if (entry.method === 8) return zlib.inflateRawSync(raw);
		throw new Error('unsupported compression method ' + entry.method + ' in ' + name);
	}

	return { entries, read };
}

// ───────────────────────── 工具 ─────────────────────────
function ensureDir(dir) {
	fs.mkdirSync(dir, { recursive: true });
}

function listPng(dir, base) {
	const out = [];
	if (!fs.existsSync(dir)) return out;
	for (const name of fs.readdirSync(dir)) {
		const full = path.join(dir, name);
		const stat = fs.statSync(full);
		if (stat.isDirectory()) out.push(...listPng(full, base));
		else if (name.toLowerCase().endsWith('.png')) out.push(path.relative(base, full).replace(/\\/g, '/'));
	}
	return out;
}

function minecraftVersion() {
	const props = fs.readFileSync(path.join(ROOT, 'gradle.properties'), 'utf8');
	const match = /^minecraft_version=(.+)$/m.exec(props);
	return match ? match[1].trim() : null;
}

// ───────────────────────── 1. 自带近似贴图 ─────────────────────────
function collectAstro(entries) {
	let added = 0;
	for (const [id, file] of Object.entries(ASTRO_MAP)) {
		if (!fs.existsSync(path.join(PARTICLES, file))) continue;
		entries.set(id, { id, files: [file], source: 'astro' });
		added++;
	}
	return added;
}

// ───────────────────────── 2. 模组贴图 ─────────────────────────
function collectModTextures(entries) {
	if (!fs.existsSync(MOD_ASSETS)) return 0;
	let added = 0;
	for (const namespace of fs.readdirSync(MOD_ASSETS)) {
		const dir = path.join(MOD_ASSETS, namespace, 'textures', 'particle');
		if (!fs.existsSync(dir)) continue;
		for (const file of listPng(dir, dir)) {
			const target = path.join(PARTICLES, namespace, file);
			ensureDir(path.dirname(target));
			fs.copyFileSync(path.join(dir, file), target);
			const name = file.replace(/\.png$/i, '');
			const id = `${namespace}:${name}`;
			entries.set(id, { id, files: [`${namespace}/${file}`], source: 'mod' });
			added++;
		}
	}
	return added;
}

// ───────────────────────── 3. 原版贴图 ─────────────────────────
function findClientJar() {
	const version = minecraftVersion();
	const cache = path.join(require('os').homedir(), '.gradle', 'caches', 'fabric-loom');
	const candidates = [];
	for (const dir of version ? [version] : []) {
		const full = path.join(cache, dir);
		for (const name of ['minecraft-client.jar', 'minecraft-merged.jar', 'minecraft-clientOnly.jar']) {
			const file = path.join(full, name);
			if (fs.existsSync(file)) candidates.push(file);
		}
	}
	if (!candidates.length && fs.existsSync(cache)) {
		// 版本目录变了就退一步：全盘找第一个 client jar
		for (const dir of fs.readdirSync(cache)) {
			for (const name of ['minecraft-client.jar', 'minecraft-merged.jar']) {
				const file = path.join(cache, dir, name);
				if (fs.existsSync(file)) candidates.push(file);
			}
			if (candidates.length) break;
		}
	}
	return candidates[0] || null;
}

function collectVanilla(entries) {
	const jar = findClientJar();
	if (!jar) {
		console.log('[vanilla] 没找到客户端 jar，跳过（先跑一次 gradlew build 让 Loom 下载）');
		return { textures: 0, definitions: 0 };
	}
	console.log('[vanilla] 读取 ' + path.relative(ROOT, jar));
	const zip = openZip(jar);
	const outDir = path.join(PARTICLES, 'minecraft');
	let textures = 0;

	// 3a. 贴图本体
	for (const name of zip.entries.keys()) {
		if (!name.startsWith('assets/minecraft/textures/particle/') || !name.endsWith('.png')) continue;
		const relative = name.slice('assets/minecraft/textures/particle/'.length);
		const target = path.join(outDir, relative);
		ensureDir(path.dirname(target));
		fs.writeFileSync(target, zip.read(name));
		textures++;
	}

	// 3b. 原版粒子定义：一个定义可能引用多张贴图，决定 particle/effect/cycle 的变体
	const definitions = {};
	for (const name of zip.entries.keys()) {
		const match = /^assets\/minecraft\/particles\/([^/]+)\.json$/.exec(name);
		if (!match) continue;
		let json;
		try {
			json = JSON.parse(zip.read(name).toString('utf8'));
		} catch (err) {
			continue;
		}
		const list = Array.isArray(json.textures) ? json.textures : null;
		if (!list || !list.length) continue;
		const files = list
			.map((id) => String(id).replace(/^minecraft:/, ''))
			.map((sprite) => `minecraft/${sprite}.png`)
			.filter((file) => fs.existsSync(path.join(PARTICLES, file)));
		if (!files.length) continue;
		definitions[match[1]] = files;
	}

	for (const [name, files] of Object.entries(definitions)) {
		const id = `minecraft:${name}`;
		entries.set(id, { id, files, source: 'vanilla' });
	}

	// 3c. 单张贴图也能按 sprite id 直接引用
	for (const file of listPng(outDir, PARTICLES)) {
		if (!file.startsWith('minecraft/')) continue;
		const id = 'minecraft:' + file.slice('minecraft/'.length).replace(/\.png$/i, '');
		// 原版贴图优先于 astro/ 里的近似贴图
		entries.set(id, { id, files: [file], source: 'vanilla' });
	}

	fs.writeFileSync(path.join(PARTICLES, 'vanilla-index.json'), JSON.stringify(definitions, null, 2));
	return { textures, definitions: Object.keys(definitions).length };
}

// ───────────────────────── 输出 ─────────────────────────
function writeManifest(entries) {
	const sorted = Array.from(entries.values()).sort((a, b) => a.id.localeCompare(b.id));
	const lines = [
		'/*',
		' * 由 tools/sync-assets.js 生成，不要手改。',
		' *',
		' * 加贴图的两条路：',
		' *   1. 放进 src/client/resources/assets/<ns>/textures/particle/，跑 node web/tools/sync-assets.js',
		' *   2. 直接放到 web/assets/particles/<ns>/，再跑同一个脚本重建清单',
		' * 原版贴图：node web/tools/sync-assets.js --vanilla（只在本机生成，不进版本库）',
		' */',
		'window.AP_SPRITES = {',
		`\tgenerated: ${JSON.stringify(new Date().toISOString())},`,
		'\tentries: [',
	];
	for (const entry of sorted) {
		lines.push(`\t\t{ id: ${JSON.stringify(entry.id)}, files: ${JSON.stringify(entry.files)}, source: ${JSON.stringify(entry.source)} },`);
	}
	lines.push('\t],', '};', '');
	fs.writeFileSync(path.join(PARTICLES, 'manifest.js'), lines.join('\n'));
	return sorted.length;
}

function main() {
	ensureDir(PARTICLES);
	const entries = new Map();
	const astro = collectAstro(entries);
	const mod = collectModTextures(entries);
	let vanilla = { textures: 0, definitions: 0 };
	if (process.argv.includes('--vanilla')) {
		vanilla = collectVanilla(entries);
	} else if (fs.existsSync(path.join(PARTICLES, 'minecraft'))) {
		console.log('[vanilla] 检测到已有的 minecraft/ 目录，一并写入清单（用 --vanilla 可重新解出）');
		for (const file of listPng(path.join(PARTICLES, 'minecraft'), PARTICLES)) {
			const id = 'minecraft:' + file.slice('minecraft/'.length).replace(/\.png$/i, '');
			entries.set(id, { id, files: [file], source: 'vanilla' });
		}
	}
	const total = writeManifest(entries);
	console.log(`[assets] astro ${astro} · mod ${mod} · vanilla ${vanilla.textures} 贴图 / ${vanilla.definitions} 定义 → 共 ${total} 个 sprite id`);
	console.log('[assets] 清单：web/assets/particles/manifest.js');
}

main();
