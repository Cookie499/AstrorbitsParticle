/*
 * ui.js — 界面、控件与 Canvas 预览
 *
 * 数据流：state.def（与 effects/*.json 同构）→ Fx.bind → Fx.Playback → 每帧采样 → Canvas 2D。
 * 任何控件改动只写 state.def，然后 commit()；下一帧开头重新绑定。
 */
(function () {
	'use strict';

	const Core = window.APCore;
	const St = window.APStyles;
	const Fx = window.APEffect;
	const Presets = window.APPresets;
	const DEG = Math.PI / 180;
	const SVG_NS = 'http://www.w3.org/2000/svg';

	// ───────────────────────── DOM 小工具 ─────────────────────────
	const $ = (sel) => document.querySelector(sel);

	function h(tag, props, ...kids) {
		const node = document.createElement(tag);
		if (props) {
			for (const key of Object.keys(props)) {
				const value = props[key];
				if (value === null || value === undefined || value === false) continue;
				if (key === 'class') node.className = value;
				else if (key === 'text') node.textContent = value;
				else if (key === 'html') node.innerHTML = value;
				else if (key.startsWith('on')) node.addEventListener(key.slice(2).toLowerCase(), value);
				else node.setAttribute(key, value === true ? '' : value);
			}
		}
		for (const kid of kids.flat(4)) {
			if (kid === null || kid === undefined || kid === false) continue;
			node.append(kid.nodeType ? kid : document.createTextNode(String(kid)));
		}
		return node;
	}

	function icon(id, cls) {
		const svg = document.createElementNS(SVG_NS, 'svg');
		svg.setAttribute('class', 'ico' + (cls ? ' ' + cls : ''));
		const use = document.createElementNS(SVG_NS, 'use');
		use.setAttribute('href', '#' + id);
		svg.append(use);
		return svg;
	}

	const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));
	const fmtNum = (v, digits) => {
		if (!Number.isFinite(v)) return '0';
		const s = Number(v).toFixed(digits === undefined ? 3 : digits);
		return s.replace(/\.?0+$/, '') || '0';
	};

	let toastTimer = 0;
	function toast(text, bad) {
		const box = $('#toast');
		box.textContent = text;
		box.className = 'toast' + (bad ? ' bad' : '');
		box.hidden = false;
		clearTimeout(toastTimer);
		toastTimer = setTimeout(() => { box.hidden = true; }, 2200);
	}

	// ───────────────────────── 状态 ─────────────────────────
	const state = {
		def: null,
		bound: null,
		problems: [],
		playback: null,
		seed: '12345',
		age: 0,
		playing: true,
		speed: 1,
		previewLoop: true,
		dirty: true,
		effectName: 'my_effect',
		outputTab: 'json',
		rawText: '',
		rawPainted: null,
		camera: { yaw: 34, pitch: 15, dist: 22, target: [0, 2.6, 0] },
		showGrid: true,
		useGround: false,
		spriteMode: 'texture',
		open: { base: true, emission: true, physics: false, appearance: true, trail: false, splash: false, visibility: false, params: true },
		fps: 0,
		splashAcc: 0,
		lastFrame: 0,
	};

	const styleOf = (def) => St.styleById(def.style) || St.STYLES[0];

	function normalizeDef(def) {
		// 编辑器里显示真实数值而不是「缺省」：绑定语义不变，只是把默认值写实。
		const a = def.appearance;
		if (a.size === null) a.size = 0.2;
		if (a.sizeByAge === null) a.sizeByAge = [[0, 1], [1, 0]];
		if (a.alphaByAge === null) a.alphaByAge = [[0, 1], [0.7, 1], [1, 0]];
		if (a.stretch === null) a.stretch = 0;
		if (a.spin === null) a.spin = 0;
		if (a.color === null) a.color = '#FFFFFF';
		return def;
	}

	function applyPreset(preset, quiet) {
		const def = normalizeDef(Fx.parseDefinition(preset.json));
		state.def = def;
		state.age = 0;
		state.playing = true;
		state.dirty = true;
		rebuildInspector();
		updateChrome();
		state.dirty = true;
		ensureBound();
		frameEffect();
		try { setRawText(JSON.stringify(JSON.parse(preset.json), null, 2)); } catch (err) { /* 预设是常量 JSON */ }
		if (!quiet) notify(`已载入模板：${preset.name}`);
	}

	/**
	 * 按「画面里大多数粒子」的半径取景：包围球取的是全年龄最大值，
	 * 直接拿它当取景半径会让彗星这类飞得很远的样式缩成一个点。
	 */
	function fitRadius() {
		const playback = state.playback;
		if (!playback || !state.bound) return 8;
		const distances = [];
		for (const fraction of [0.2, 0.45]) {
			const buffer = playback.sample(state.def.duration * fraction, false);
			const stride = Math.max(1, Math.floor(buffer.count / 300));
			for (let i = 0; i < buffer.count; i += stride) {
				distances.push(Math.hypot(buffer.x[i], buffer.y[i], buffer.z[i]));
			}
		}
		if (!distances.length) return 8;
		distances.sort((a, b) => a - b);
		return distances[Math.floor(distances.length * 0.62)] || 8;
	}

	/** 地面取在「粒子最低处附近」，保证水平面真的能被下落粒子穿过。 */
	function autoGroundY() {
		const playback = state.playback;
		if (!playback) return -6;
		const ys = [];
		for (const fraction of [0.3, 0.6, 0.9]) {
			const buffer = playback.sample(state.def.duration * fraction, false);
			const stride = Math.max(1, Math.floor(buffer.count / 250));
			for (let i = 0; i < buffer.count; i += stride) ys.push(buffer.y[i]);
		}
		if (!ys.length) return -6;
		ys.sort((a, b) => a - b);
		return Math.round(ys[Math.floor(ys.length * 0.08)] * 10) / 10;
	}

	/** 按画面里大多数粒子的范围把相机拉到合适距离。 */
	function frameEffect() {
		const radius = Math.max(2, fitRadius());
		state.camera.dist = clamp(radius * 1.35 + 3, 7, 40);
		state.camera.target = [0, clamp(radius * 0.26, 0, 4.5), 0];
	}

	function commit(options) {
		state.dirty = true;
		if (options && options.structure) rebuildInspector();
	}

	// ───────────────────────── 预览渲染 ─────────────────────────
	const canvas = $('#view');
	const ctx = canvas.getContext('2d');
	const view = { w: 800, h: 500, dpr: 1, focal: 600 };

	// ── 贴图 ──
	// assets/particles/manifest.js 声明 sprite id → 图片文件；没有图片时回落到程序化形状。
	const SHAPE = 64;
	const SPRITE_ENTRIES = new Map();
	const imageCache = new Map();
	const tintCache = new Map();
	const shapeCache = new Map();
	let nextTextureId = 1;

	function loadSpriteManifest() {
		const data = window.AP_SPRITES;
		if (!data || !Array.isArray(data.entries)) return;
		for (const entry of data.entries) {
			if (!entry || !entry.id) continue;
			const files = Array.isArray(entry.files) ? entry.files.slice() : [];
			for (const file of files) {
				if (!imageCache.has(file)) {
					imageCache.set(file, { img: null, ready: false, failed: false, id: nextTextureId++ });
				}
			}
			SPRITE_ENTRIES.set(entry.id, { files, source: entry.source || '' });
		}
	}

	function imageFor(file) {
		let record = imageCache.get(file);
		if (!record) {
			record = { img: null, ready: false, failed: false, id: nextTextureId++ };
			imageCache.set(file, record);
		}
		if (!record.img && !record.failed) {
			const img = new Image();
			img.onload = () => {
				record.ready = true;
				const waiters = record.waiters || [];
				record.waiters = [];
				for (const callback of waiters) callback();
			};
			img.onerror = () => { record.failed = true; };
			img.src = 'assets/particles/' + file;
			record.img = img;
		}
		return record;
	}

	function baseShape(shape) {
		let canvas = shapeCache.get(shape);
		if (!canvas) {
			canvas = window.APShapes.makeSprite(shape);
			shapeCache.set(shape, canvas);
		}
		return canvas;
	}

	/** 按 (贴图, 量化颜色) 缓存上色结果；没有图片时用程序化形状顶替。 */
	function textureFor(file, shape, argb) {
		const record = file ? imageFor(file) : null;
		let textureId;
		let source;
		if (record && record.ready && !record.failed) {
			textureId = record.id;
			source = record.img;
		} else {
			textureId = 10000 + window.APShapes.SHAPES.indexOf(shape);
			source = baseShape(shape);
		}
		const r = ((argb >>> 16) & 0xff) >> 3;
		const g = ((argb >>> 8) & 0xff) >> 3;
		const b = (argb & 0xff) >> 3;
		const key = ((textureId * 32 + r) * 32 + g) * 32 + b;
		let canvas = tintCache.get(key);
		if (canvas) return canvas;
		// 按贴图原始分辨率上色：原版粒子贴图常见 8×8，放大会糊成一团灰雾
		const width = source.width || SHAPE;
		const height = source.height || SHAPE;
		canvas = document.createElement('canvas');
		canvas.width = width;
		canvas.height = height;
		const g2 = canvas.getContext('2d');
		g2.drawImage(source, 0, 0);
		g2.globalCompositeOperation = 'source-in';
		g2.fillStyle = `rgb(${(r << 3) | (r >> 2)},${(g << 3) | (g >> 2)},${(b << 3) | (b >> 2)})`;
		g2.fillRect(0, 0, width, height);
		if (tintCache.size > 900) tintCache.clear();
		tintCache.set(key, canvas);
		return canvas;
	}

	/**
	 * 一帧内如何选图：单图直接定，effect 用 seed，cycle 用年龄，
	 * particle 逐粒子用 SPRITE 流（与原版 SpriteSet 的三种变体对应）。
	 */
	function buildSpritePlan(appearance, age) {
		const shape = window.APShapes.shapeFor(appearance.sprite);
		if (state.spriteMode === 'approx') return { fixed: null, files: null, shape };
		const entry = SPRITE_ENTRIES.get(appearance.sprite);
		if (!entry || entry.files.length === 0) return { fixed: null, files: null, shape };
		const files = entry.files;
		if (files.length === 1) return { fixed: files[0], files: null, shape };
		if (appearance.spriteVariation === 'effect') {
			let seedValue;
			try { seedValue = BigInt(String(state.seed).trim()); } catch (err) { seedValue = 0n; }
			const index = Number((seedValue >> 1n) % BigInt(files.length));
			return { fixed: files[Math.abs(index)], files: null, shape };
		}
		if (appearance.spriteVariation === 'cycle') {
			const frame = Math.floor(age * 20 / Math.max(1, appearance.spriteCycleTicks)) % files.length;
			return { fixed: files[(frame + files.length) % files.length], files: null, shape };
		}
		return { fixed: null, files, shape };
	}

	function particleVariantFile(files, index) {
		const rand = state.playback ? state.playback.rand : null;
		const bits = rand ? rand.hashLo(Core.Streams.SPRITE, index, 0) : index;
		return files[(bits >>> 1) % files.length];
	}

	function cameraBasis() {
		const cam = state.camera;
		const yaw = cam.yaw * DEG;
		const pitch = cam.pitch * DEG;
		const cp = Math.cos(pitch);
		const pos = [
			cam.target[0] + cam.dist * cp * Math.sin(yaw),
			cam.target[1] + cam.dist * Math.sin(pitch),
			cam.target[2] + cam.dist * cp * Math.cos(yaw),
		];
		const fwd = Core.vnorm(cam.target[0] - pos[0], cam.target[1] - pos[1], cam.target[2] - pos[2]);
		let right = [fwd[2], 0, -fwd[0]];
		if (Core.vlen(right[0], right[1], right[2]) < 1e-6) right = [1, 0, 0];
		else right = Core.vnorm(right[0], right[1], right[2]);
		const up = [
			right[1] * fwd[2] - right[2] * fwd[1],
			right[2] * fwd[0] - right[0] * fwd[2],
			right[0] * fwd[1] - right[1] * fwd[0],
		];
		return { pos, fwd, right, up: [-up[0], -up[1], -up[2]] };
	}

	// 投影结果写进复用数组，避免逐粒子分配
	const proj = new Float32Array(4);

	function project(cam, x, y, z) {
		const dx = x - cam.pos[0];
		const dy = y - cam.pos[1];
		const dz = z - cam.pos[2];
		const depth = dx * cam.fwd[0] + dy * cam.fwd[1] + dz * cam.fwd[2];
		if (depth <= 0.08) return null;
		const sx = (dx * cam.right[0] + dy * cam.right[1] + dz * cam.right[2]);
		const sy = (dx * cam.up[0] + dy * cam.up[1] + dz * cam.up[2]);
		const s = view.focal / depth;
		proj[0] = view.w / 2 + sx * s;
		proj[1] = view.h / 2 - sy * s;
		proj[2] = depth;
		proj[3] = s;
		return proj;
	}

	function resize() {
		const rect = canvas.parentElement.getBoundingClientRect();
		view.w = Math.max(120, Math.round(rect.width));
		view.h = Math.max(120, Math.round(rect.height));
		view.dpr = Math.min(2, window.devicePixelRatio || 1);
		canvas.width = Math.round(view.w * view.dpr);
		canvas.height = Math.round(view.h * view.dpr);
		view.focal = (view.h / 2) / Math.tan(31 * DEG);
	}

	function drawBackground(cam) {
		const sky = ctx.createLinearGradient(0, 0, 0, view.h);
		sky.addColorStop(0, '#0a0d13');
		sky.addColorStop(0.55, '#121a16');
		sky.addColorStop(1, '#1b2418');
		ctx.fillStyle = sky;
		ctx.fillRect(0, 0, view.w, view.h);

		// 远处地平线的一点光
		const glow = ctx.createRadialGradient(view.w * 0.5, view.h * 0.62, 0, view.w * 0.5, view.h * 0.62, view.w * 0.55);
		glow.addColorStop(0, 'rgba(66,165,0,.10)');
		glow.addColorStop(1, 'rgba(66,165,0,0)');
		ctx.fillStyle = glow;
		ctx.fillRect(0, 0, view.w, view.h);

		if (!state.showGrid) return;

		// 地面网格：以 2 格为单位；打开「地面」后网格贴在地面平面上
		const gridY = state.useGround ? (state.groundY || 0) : 0;
		const R = 24;
		ctx.lineWidth = 1;
		for (let i = -R; i <= R; i += 2) {
			for (const axis of [0, 2]) {
				const a = axis === 0 ? [i, gridY, -R] : [-R, gridY, i];
				const b = axis === 0 ? [i, gridY, R] : [R, gridY, i];
				const pa = project(cam, a[0], a[1], a[2]);
				if (!pa) continue;
				const ax = proj[0], ay = proj[1];
				const pb = project(cam, b[0], b[1], b[2]);
				if (!pb) continue;
				const strength = 1 - clamp(Math.abs(i) / R, 0, 1);
				ctx.strokeStyle = `rgba(120,160,90,${0.05 + strength * 0.16})`;
				ctx.beginPath();
				ctx.moveTo(ax, ay);
				ctx.lineTo(proj[0], proj[1]);
				ctx.stroke();
			}
		}

		// 地面平面（splash 检测面）
		if (state.useGround) {
			const gy = state.groundY || 0;
			const corners = [[-14, gy, -14], [14, gy, -14], [14, gy, 14], [-14, gy, 14]];
			const pts = corners.map((c) => {
				const p = project(cam, c[0], c[1], c[2]);
				return p ? [proj[0], proj[1]] : null;
			});
			if (pts.every(Boolean)) {
				ctx.beginPath();
				ctx.moveTo(pts[0][0], pts[0][1]);
				for (let i = 1; i < 4; i++) ctx.lineTo(pts[i][0], pts[i][1]);
				ctx.closePath();
				ctx.fillStyle = 'rgba(58,84,38,.42)';
				ctx.fill();
				ctx.strokeStyle = 'rgba(150,200,90,.35)';
				ctx.lineWidth = 1.5;
				ctx.stroke();
			}
		}

		// 原点坐标轴
		const axes = [
			[[3.2, 0, 0], 'rgba(220,90,90,.65)'],
			[[0, 3.2, 0], 'rgba(140,210,90,.65)'],
			[[0, 0, 3.2], 'rgba(110,170,240,.65)'],
		];
		for (const [dir, color] of axes) {
			const p = project(cam, dir[0], dir[1], dir[2]);
			if (!p) continue;
			const bx = proj[0], by = proj[1];
			const o = project(cam, 0, 0, 0);
			if (!o) continue;
			ctx.strokeStyle = color;
			ctx.lineWidth = 1.4;
			ctx.beginPath();
			ctx.moveTo(proj[0], proj[1]);
			ctx.lineTo(bx, by);
			ctx.stroke();
			ctx.fillStyle = color;
			ctx.beginPath();
			ctx.arc(bx, by, 2.4, 0, Math.PI * 2);
			ctx.fill();
		}
	}

	/** 深度分桶，避免每帧对几万个粒子做比较排序。 */
	const buckets = Array.from({ length: 96 }, () => []);
	let bucketStamp = new Int32Array(0);
	let bucketIndex = new Int32Array(0);

	function drawParticles(cam, buffer, bound) {
		if (!buffer || buffer.count === 0) return 0;
		const appearance = bound.appearance;
		const additive = appearance.blend === 'additive';
		const stretch = appearance.stretch.at(state.age);
		const stretchVec = stretch > 0 && state.playback ? state.playback.stretchVector(state.age) : null;
		const stretched = stretchVec && (stretchVec[0] ** 2 + stretchVec[1] ** 2 + stretchVec[2] ** 2) > 1e-6;
		const spin = appearance.spin.at(state.age);
		const scale = state.playback ? state.playback.scale : 1;
		const ox = state.origin[0], oy = state.origin[1], oz = state.origin[2];
		const plan = buildSpritePlan(appearance, state.age);

		let maxCount = 9000;
		const step = Math.max(1, Math.ceil(buffer.count / maxCount));

		ctx.globalCompositeOperation = additive ? 'lighter' : 'source-over';

		const n = Math.ceil(buffer.count / step);
		if (n === 0) return 0;

		if (additive) {
			for (let i = 0; i < buffer.count; i += step) {
				const wx = ox + (buffer.x[i] - ox) * scale;
				const wy = oy + (buffer.y[i] - oy) * scale;
				const wz = oz + (buffer.z[i] - oz) * scale;
				const p = project(cam, wx, wy, wz);
				if (!p) continue;
				const sx = proj[0], sy = proj[1], depth = proj[2], sc = proj[3];
				const sprite = textureFor(
					plan.fixed !== null ? plan.fixed : (plan.files ? particleVariantFile(plan.files, i) : null),
					plan.shape, buffer.argb[i]);
				// 贴图小于 64 时按原始像素显示，避免细碎高光被缩没
				const minSize = sprite.width < 64 ? sprite.width : 2;
				const size = snapTextureSize(sprite, Math.max(minSize, buffer.size[i] * scale * sc));
				ctx.globalAlpha = clamp(buffer.alpha[i], 0, 1);
				if (spin !== 0) {
					ctx.save();
					ctx.translate(sx, sy);
					ctx.rotate(spin * state.age * DEG);
					ctx.drawImage(sprite, -size / 2, -size / 2, size, size);
					ctx.restore();
				} else {
					ctx.drawImage(sprite, sx - size / 2, sy - size / 2, size, size);
				}
				if (stretched) drawStretch(cam, sprite, wx, wy, wz, stretchVec, stretch, size, buffer.alpha[i], ox, oy, oz, scale);
			}
			ctx.globalAlpha = 1;
			ctx.globalCompositeOperation = 'source-over';
			return n;
		}

		// alpha 混合：按深度从远到近
		if (bucketStamp.length < n) bucketStamp = new Int32Array(n);
		if (bucketIndex.length < n) bucketIndex = new Int32Array(n);
		for (const list of buckets) list.length = 0;
		let zMin = Infinity, zMax = -Infinity;
		const depths = new Float32Array(n);
		let k = 0;
		for (let i = 0; i < buffer.count; i += step, k++) {
			const wx = ox + (buffer.x[i] - ox) * scale;
			const wy = oy + (buffer.y[i] - oy) * scale;
			const wz = oz + (buffer.z[i] - oz) * scale;
			const p = project(cam, wx, wy, wz);
			const depth = p ? proj[2] : -1;
			depths[k] = depth;
			if (depth > 0) {
				if (depth < zMin) zMin = depth;
				if (depth > zMax) zMax = depth;
			}
			bucketStamp[k] = i;
		}
		const span = Math.max(1e-4, zMax - zMin);
		for (let j = 0; j < n; j++) {
			if (depths[j] <= 0) continue;
			const bi = clamp(Math.floor((depths[j] - zMin) / span * 95), 0, 95);
			buckets[95 - bi].push(j);
		}
		for (let b = 0; b < 96; b++) {
			const list = buckets[b];
			for (let li = 0; li < list.length; li++) {
				const j = list[li];
				const i = bucketStamp[j];
				const wx = ox + (buffer.x[i] - ox) * scale;
				const wy = oy + (buffer.y[i] - oy) * scale;
				const wz = oz + (buffer.z[i] - oz) * scale;
				const p = project(cam, wx, wy, wz);
				if (!p) continue;
				const sx = proj[0], sy = proj[1], sc = proj[3];
				const sprite = textureFor(
					plan.fixed !== null ? plan.fixed : (plan.files ? particleVariantFile(plan.files, i) : null),
					plan.shape, buffer.argb[i]);
				const minSize = sprite.width < 64 ? sprite.width : 2;
				const size = snapTextureSize(sprite, Math.max(minSize, buffer.size[i] * scale * sc));
				ctx.globalAlpha = clamp(buffer.alpha[i], 0, 1);
				ctx.drawImage(sprite, sx - size / 2, sy - size / 2, size, size);
				if (stretched) drawStretch(cam, sprite, wx, wy, wz, stretchVec, stretch, size, buffer.alpha[i], ox, oy, oz, scale);
			}
		}
		ctx.globalAlpha = 1;
		return n;
	}

	/**
	 * 小尺寸贴图（原版多为 8×8 或 16×16）按整数倍放大，
	 * 让每个贴图像素落在整数个屏幕像素上，放大后仍是清晰的方块边缘。
	 */
	function snapTextureSize(sprite, size) {
		const natural = sprite.width;
		if (natural < 4 || natural > 32) return size;
		return natural * Math.max(1, Math.round(size / natural));
	}

	/** appearance.stretch：沿速度方向补 2 段递减的面片，与渲染器里的 3 段一致。 */
	function drawStretch(cam, sprite, wx, wy, wz, vec, stretch, size, alpha, ox, oy, oz, scale) {
		for (let segment = 1; segment < 3; segment++) {
			const f = segment / 3;
			const tx = wx - vec[0] * stretch * f;
			const ty = wy - vec[1] * stretch * f;
			const tz = wz - vec[2] * stretch * f;
			const p = project(cam, tx, ty, tz);
			if (!p) continue;
			const s2 = size * (1 - f * 0.4);
			ctx.globalAlpha = clamp(alpha * (1 - f * 0.6), 0, 1);
			ctx.drawImage(sprite, proj[0] - s2 / 2, proj[1] - s2 / 2, s2, s2);
		}
	}

	// ───────────────────────── 播放循环 ─────────────────────────
	function rebuildPlayback() {
		const bound = state.bound;
		if (!bound) { state.playback = null; return; }
		const origin = [0, 0, 0];
		state.origin = origin;
		const playback = new Fx.Playback(bound, state.seed, origin, {
			scale: 1,
			groundY: state.useGround ? 0 : null,
			viewYaw: state.camera.yaw + 180,
			viewPitch: -state.camera.pitch,
		});
		state.playback = playback;
		state.splashAcc = 0;
		if (state.age > 0) {
			if (!state.def.loop && state.age >= state.def.duration) playback.stop(state.def.duration);
		}
	}

	function ensureBound() {
		if (!state.dirty) return;
		state.dirty = false;
		const result = Fx.bind(state.def);
		state.bound = result.bound;
		state.problems = result.problems;
		rebuildPlayback();
		updateChrome();
		updateOutput();
	}

	function restart() {
		state.age = 0;
		rebuildPlayback();
		state.playing = true;
		updateTransport();
	}

	function loop(now) {
		const dt = state.lastFrame ? clamp((now - state.lastFrame) / 1000, 0, 0.05) : 0;
		state.lastFrame = now;
		if (dt > 0) state.fps = state.fps ? state.fps * 0.9 + (1 / dt) * 0.1 : 1 / dt;

		ensureBound();

		const duration = state.def.duration;
		if (state.playing && state.playback) {
			state.age += dt * state.speed;
			if (!state.def.loop && state.age >= duration) {
				if (state.previewLoop) {
					state.age = 0;
					rebuildPlayback();
				} else {
					state.age = duration;
					state.playing = false;
					state.playback.stop(duration);
					updateTransport();
				}
			}
			state.splashAcc += dt * state.speed;
			if (state.splashAcc >= 0.05) {
				state.splashAcc = 0;
				if (state.playback) state.playback.tickSplash(state.age);
			}
		}

		render(now);
		requestAnimationFrame(loop);
	}

	function render(now) {
		const cam = cameraBasis();
		ctx.setTransform(view.dpr, 0, 0, view.dpr, 0, 0);
		drawBackground(cam);
		// 原版粒子贴图是像素图：最近邻采样，放大后仍是方块状的像素边缘
		ctx.imageSmoothingEnabled = false;

		let shown = 0;
		if (state.playback && state.bound) {
			const buffer = state.playback.frame(state.age);
			shown = drawParticles(cam, buffer, state.bound);
			$('#hudCount').textContent = buffer.count;
			$('#hudBudget').textContent = state.playback.maxParticles;
			$('#hudStride').textContent = Math.max(1, Math.ceil(buffer.count / 9000));
		} else {
			$('#hudCount').textContent = '0';
			$('#hudBudget').textContent = '0';
			$('#hudStride').textContent = '1';
		}
		$('#hudFps').textContent = state.fps ? Math.round(state.fps) : '–';
		$('#hudTime').textContent = state.age.toFixed(2) + 's';
		updateTransportLabel();
	}

	// ───────────────────────── 控件 ─────────────────────────
	function row(labelText, docText, control, opts) {
		opts = opts || {};
		const label = h('div', { class: 'row-label' }, labelText);
		const main = h('div', { class: 'row-main' }, label, control.el);
		const wrap = h('div', { class: 'row' }, main);
		if (docText) wrap.append(h('div', { class: 'row-doc' + (opts.warn ? ' warn' : '') }, docText));
		if (control.scrub) {
			label.classList.add('scrub');
			label.title = '左右拖动调整数值（Shift ×10，Alt ×0.1）';
			label.addEventListener('pointerdown', (event) => {
				event.preventDefault();
				label.setPointerCapture(event.pointerId);
				const startX = event.clientX;
				const startValue = control.get();
				if (typeof startValue !== 'number') return;
				const move = (ev) => {
					const mul = ev.shiftKey ? 10 : ev.altKey ? 0.1 : 1;
					control.set(startValue + (ev.clientX - startX) * control.step * mul);
					control.sync && control.sync();
				};
				const up = () => {
					label.removeEventListener('pointermove', move);
					label.removeEventListener('pointerup', up);
				};
				label.addEventListener('pointermove', move);
				label.addEventListener('pointerup', up);
			});
		}
		return { el: wrap, control, label };
	}

	function stepFor(min, max, fallback) {
		if (min !== undefined && max !== undefined && Number.isFinite(min) && Number.isFinite(max) && max > min) {
			return Math.max(1e-4, (max - min) / 200);
		}
		return fallback || 0.1;
	}

	/** 数值控件：可键入，也可拖左侧标签改值；allowExpr 时接受 t/d/u/n 表达式。 */
	function numControl(opts) {
		const input = h('input', {
			class: 'num txt' + (opts.allowExpr ? ' expr' : ''),
			spellcheck: 'false',
			inputmode: 'decimal',
		});
		const min = opts.min;
		const max = opts.max;
		const integer = !!opts.integer;
		let value = opts.value;

		const format = (v) => {
			if (typeof v === 'string') return v;
			if (!Number.isFinite(v)) return '0';
			if (integer) return String(Math.round(v));
			const scaled = Math.round(v * 1000) / 1000;
			return String(scaled);
		};
		const apply = (raw, silent) => {
			let next;
			if (opts.allowExpr && typeof raw === 'string' && !/^\s*-?\d*\.?\d+(?:[eE][+-]?\d+)?\s*$/.test(raw)) {
				next = raw.trim() === '' ? 0 : raw.trim();
			} else {
				const parsed = Number(raw);
				if (!Number.isFinite(parsed)) return;
				next = integer ? Math.round(parsed) : parsed;
				if (min !== undefined && min !== null) next = Math.max(min, next);
				if (max !== undefined && max !== null) next = Math.min(max, next);
			}
			value = next;
			opts.onChange(next);
			if (!silent) commit();
		};

		input.value = format(value);
		input.addEventListener('input', () => apply(input.value, true));
		input.addEventListener('change', () => {
			apply(input.value, false);
			input.value = format(value);
		});
		input.addEventListener('keydown', (event) => {
			if (event.key !== 'ArrowUp' && event.key !== 'ArrowDown') return;
			if (typeof value !== 'number') return;
			event.preventDefault();
			const step = stepFor(min, max, 0.1) * (event.shiftKey ? 10 : 1);
			const next = value + (event.key === 'ArrowUp' ? step : -step);
			value = clamp(next, min === undefined || min === null ? -Infinity : min, max === undefined || max === null ? Infinity : max);
			input.value = format(value);
			opts.onChange(value);
			commit();
		});

		return {
			el: input,
			step: stepFor(min, max, 0.1),
			get: () => value,
			set: (v) => {
				value = integer ? Math.round(v) : Math.round(v * 1000) / 1000;
				if (min !== undefined && min !== null) value = Math.max(min, value);
				if (max !== undefined && max !== null) value = Math.min(max, value);
				input.value = format(value);
				opts.onChange(value);
				commit();
			},
			sync: () => { input.value = format(value); },
		};
	}

	function textControl(opts) {
		const input = h('input', { class: 'txt', spellcheck: 'false', value: opts.value || '' });
		input.addEventListener('input', () => { opts.onChange(input.value); commit(); });
		return { el: input, get: () => input.value, set: (v) => { input.value = v; opts.onChange(v); commit(); } };
	}

	function toggleControl(opts) {
		const input = h('input', { type: 'checkbox' });
		input.checked = !!opts.value;
		input.addEventListener('change', () => { opts.onChange(input.checked); commit(); });
		const el = h('label', { class: 'toggle' }, input, h('span', {}, opts.text || ''));
		return { el, get: () => input.checked, set: (v) => { input.checked = !!v; opts.onChange(!!v); commit(); } };
	}

	function selectControl(opts) {
		const select = h('select', { class: 'sel' });
		for (const option of opts.options) {
			const [value, label] = Array.isArray(option) ? option : [option, option];
			select.append(h('option', { value }, label));
		}
		select.value = opts.value;
		select.addEventListener('change', () => { opts.onChange(select.value); commit({ structure: !!opts.restructure }); });
		return { el: select, get: () => select.value, set: (v) => { select.value = v; opts.onChange(v); commit(); } };
	}

	function segmentedControl(opts) {
		const el = h('div', { class: 'seg' });
		const buttons = opts.options.map(([value, label]) =>
			h('button', {
				class: value === opts.value ? 'on' : '',
				onclick: () => {
					for (const b of buttons) b.classList.toggle('on', b.dataset.value === value);
					opts.onChange(value);
					commit({ structure: !!opts.restructure });
				},
				'data-value': value,
			}, label));
		el.append(...buttons);
		return { el };
	}

	function vecControl(opts) {
		const el = h('div', { class: 'vec' });
		const axisNames = opts.labels || ['X', 'Y', 'Z'];
		const controls = [];
		opts.value.forEach((component, index) => {
			const ctl = numControl({
				value: component,
				step: opts.step,
				onChange: (v) => {
					const next = controls.map((c) => c.get());
					next[index] = v;
					opts.onChange(next);
				},
			});
			controls.push(ctl);
			el.append(h('div', { class: 'axis' }, h('span', {}, axisNames[index]), ctl.el));
		});
		return { el, get: () => controls.map((c) => c.get()) };
	}

	const MC_COLORS = [
		'#FFFFFF', '#FFD36E', '#FFB347', '#FF8B3D', '#FF5A1F', '#FF3B6B', '#FF7AD9', '#C77DFF', '#8E7BFF',
		'#6EA8FF', '#7FD4FF', '#8AF0FF', '#66E0FF', '#9EE7FF', '#B7C9FF', '#8FB8FF', '#B1DC58', '#42A500',
	];

	function colorControl(opts) {
		const color = h('input', { type: 'color', value: opts.value || '#ffffff' });
		const text = h('input', { class: 'txt', value: opts.value || '#FFFFFF', spellcheck: 'false', style: 'width:88px' });
		const wrap = h('div', { class: 'color-ctl' }, color, text);
		const swatches = h('div', { class: 'swatches' },
			MC_COLORS.map((c) => h('button', {
				title: c,
				style: `background:${c}`,
				onclick: () => set(c),
			})));
		const body = h('div', {}, wrap, swatches);
		function set(value) {
			color.value = value.length === 7 ? value : '#ffffff';
			text.value = value;
			opts.onChange(value);
			commit();
		}
		color.addEventListener('input', () => { text.value = color.value.toUpperCase(); opts.onChange(color.value.toUpperCase()); commit(); });
		text.addEventListener('change', () => {
			let v = text.value.trim();
			if (!v.startsWith('#')) v = '#' + v;
			if (!/^#[0-9a-fA-F]{6}$/.test(v)) { text.value = opts.value; return; }
			set(v.toUpperCase());
		});
		return { el: body, get: () => text.value, set };
	}

	/** 曲线编辑器：拖点改值，空白处点击加点，右键删点。 */
	function curveControl(opts) {
		const wrap = h('div', { class: 'curve-editor' });
		const svg = document.createElementNS(SVG_NS, 'svg');
		const foot = h('div', { class: 'curve-editor-foot' });
		const rangeHint = h('span', {}, 'u 0→1，点右键删除');
		const resetBtn = h('button', { type: 'button', onclick: () => { points = opts.defaultPoints.slice(); emit(); } }, '重置');
		foot.append(rangeHint, resetBtn);
		wrap.append(svg, foot);

		let points = opts.value.map((v, i) => (i % 2 === 0 ? clamp(v, 0, 1) : clamp(v, 0, 1)));
		if (points.length < 4) points = opts.defaultPoints.slice();

		function emit() {
			opts.onChange(points.slice());
			commit();
		}

		function layout() {
			const rect = svg.getBoundingClientRect();
			const w = Math.max(120, Math.round(rect.width));
			const hgt = Math.max(40, Math.round(rect.height));
			svg.setAttribute('viewBox', `0 0 ${w} ${hgt}`);
			return { w, hgt };
		}

		function render() {
			const { w, hgt } = layout();
			svg.innerHTML = '';
			for (let i = 1; i < 4; i++) {
				svg.append(svgEl('line', {
					x1: w * i / 4, y1: 0, x2: w * i / 4, y2: hgt,
					stroke: '#e5e8db', 'stroke-width': 1,
				}));
			}
			svg.append(svgEl('line', { x1: 0, y1: hgt - 1, x2: w, y2: hgt - 1, stroke: '#d3d7c7', 'stroke-width': 1 }));
			const pts = [];
			for (let i = 0; i < points.length; i += 2) pts.push([points[i] * w, (1 - points[i + 1]) * hgt]);
			svg.append(svgEl('path', {
				d: pts.map((p, i) => (i === 0 ? 'M' : 'L') + p[0].toFixed(1) + ' ' + p[1].toFixed(1)).join(' '),
				fill: 'none', stroke: '#42a500', 'stroke-width': 1.8,
			}));
			pts.forEach((p, index) => {
				const circle = svgEl('circle', { cx: p[0], cy: p[1], r: 3.6, fill: '#fff', stroke: '#42a500', 'stroke-width': 1.6 });
				circle.style.cursor = 'grab';
				circle.dataset.index = String(index * 2);
				svg.append(circle);
			});
		}

		function svgEl(tag, attrs) {
			const node = document.createElementNS(SVG_NS, tag);
			for (const key of Object.keys(attrs)) node.setAttribute(key, attrs[key]);
			return node;
		}

		function coords(event) {
			const rect = svg.getBoundingClientRect();
			return {
				u: clamp((event.clientX - rect.left) / rect.width, 0, 1),
				v: clamp(1 - (event.clientY - rect.top) / rect.height, 0, 1),
			};
		}

		svg.addEventListener('contextmenu', (event) => {
			event.preventDefault();
			const target = event.target.closest('circle');
			if (!target) return;
			const index = Number(target.dataset.index);
			if (points.length <= 4) { toast('曲线至少保留两个点'); return; }
			points.splice(index, 2);
			render();
			emit();
		});

		svg.addEventListener('pointerdown', (event) => {
			if (event.button !== 0) return;
			event.preventDefault();
			svg.setPointerCapture(event.pointerId);
			const { u, v } = coords(event);
			let index = -1;
			const target = event.target.closest('circle');
			if (target) {
				index = Number(target.dataset.index);
			} else {
				// 找最近的线段插入，保持 u 递增
				let insert = points.length;
				for (let i = 0; i < points.length; i += 2) {
					if (points[i] > u) { insert = i; break; }
				}
				points.splice(insert, 0, u, v);
				index = insert;
			}
			const move = (ev) => {
				const c = coords(ev);
				points[index] = c.u;
				points[index + 1] = c.v;
				// 拖动时保持顺序
				render();
				opts.onChange(points.slice());
				state.dirty = true;
			};
			const up = () => {
				svg.removeEventListener('pointermove', move);
				window.removeEventListener('pointerup', up);
				sortPoints();
				render();
				emit();
			};
			svg.addEventListener('pointermove', move);
			window.addEventListener('pointerup', up);
			render();
		});

		function sortPoints() {
			const pairs = [];
			for (let i = 0; i < points.length; i += 2) pairs.push([points[i], points[i + 1]]);
			pairs.sort((a, b) => a[0] - b[0]);
			points = pairs.flat();
		}

		render();
		window.addEventListener('resize', render);
		return { el: wrap };
	}

	/** 颜色渐变：一串 [u, "#RRGGBB"]。 */
	function gradientControl(opts) {
		const list = h('div', { class: 'gradient-list' });
		const bar = h('div', { class: 'gradient-bar' });
		const addBtn = h('button', {
			class: 'btn btn-mini',
			style: 'margin-top:4px',
			onclick: () => {
				stops.push([clamp((stops[stops.length - 1]?.[0] ?? 1) * 0.5 + 0.5, 0, 1), '#FFFFFF']);
				stops.sort((a, b) => a[0] - b[0]);
				render();
				emit();
			},
		}, '+ 添加渐变点');
		const wrap = h('div', {}, bar, list, addBtn);
		let stops = (opts.value || []).map((s) => [Number(s[0]), String(s[1])]);

		function emit() {
			opts.onChange(stops.length >= 2 ? stops.map((s) => [Math.round(s[0] * 1000) / 1000, s[1]]) : null);
			commit();
		}

		function render() {
			list.innerHTML = '';
			bar.style.background = stops.length >= 2
				? 'linear-gradient(90deg,' + stops.map((s) => `${s[1]} ${(s[0] * 100).toFixed(1)}%`).join(',') + ')'
				: '#eee';
			stops.forEach((stop, index) => {
				const color = h('input', { type: 'color', value: stop[1] });
				const range = h('input', { type: 'range', min: 0, max: 1, step: 0.01, value: stop[0] });
				const label = h('span', { class: 'u' }, stop[0].toFixed(2));
				const del = h('button', { title: '删除', onclick: () => { stops.splice(index, 1); render(); emit(); } }, '×');
				color.addEventListener('input', () => { stop[1] = color.value.toUpperCase(); render(); emit(); });
				range.addEventListener('input', () => {
					stop[0] = Number(range.value);
					label.textContent = stop[0].toFixed(2);
					bar.style.background = 'linear-gradient(90deg,' + stops.map((s) => `${s[1]} ${(s[0] * 100).toFixed(1)}%`).join(',') + ')';
					emit();
				});
				list.append(h('div', { class: 'gradient-stop' }, range, label, color, del));
			});
		}

		render();
		return { el: wrap };
	}

	function chipsControl(opts) {
		const all = opts.options;
		let selected = new Set(opts.value);
		const el = h('div', { class: 'chip-row' });
		for (const option of all) {
			const btn = h('button', {
				class: 'chip-multi' + (selected.has(option) ? ' on' : ''),
				onclick: () => {
					if (selected.has(option)) selected.delete(option);
					else selected.add(option);
					btn.classList.toggle('on', selected.has(option));
					opts.onChange(Array.from(selected));
					commit();
				},
			}, option);
			el.append(btn);
		}
		return { el };
	}

	const FEATURED_SPRITES = [
		'minecraft:generic_0', 'minecraft:generic_1', 'minecraft:glitter_0', 'minecraft:glow',
		'minecraft:spark_0', 'minecraft:spark_1', 'minecraft:splash_0', 'minecraft:end_rod',
		'minecraft:crit', 'minecraft:flame', 'minecraft:smoke', 'minecraft:note',
	];

	function spriteHint(id) {
		const entry = SPRITE_ENTRIES.get(id);
		if (!entry || entry.files.length === 0) return '图集里没有这张贴图，预览用程序化形状代替。';
		const source = entry.source ? `（${entry.source}）` : '';
		return `已载入 ${entry.files.length} 张图${source}`;
	}

	/** 贴图缩略图：棋盘底 + 最近邻放大，用来确认引用的到底是哪张图。 */
	function spritePreview(spriteId) {
		const box = h('div', { class: 'sprite-preview' });

		function paint() {
			box.innerHTML = '';
			const entry = SPRITE_ENTRIES.get(spriteId);
			if (!entry || entry.files.length === 0) {
				const shape = window.APShapes.shapeFor(spriteId);
				const canvas = document.createElement('canvas');
				canvas.width = canvas.height = 40;
				const g = canvas.getContext('2d');
				g.imageSmoothingEnabled = false;
				g.drawImage(window.APShapes.makeSprite(shape), 0, 0, 40, 40);
				box.append(canvas, h('span', { class: 'sprite-meta' }, `没有这张贴图，用程序化形状「${shape}」代替`));
				return;
			}
			for (const file of entry.files.slice(0, 4)) {
				const canvas = document.createElement('canvas');
				canvas.width = canvas.height = 40;
				const g = canvas.getContext('2d');
				for (let y = 0; y < 40; y += 10) {
					for (let x = 0; x < 40; x += 10) {
						g.fillStyle = ((x / 10 + y / 10) % 2) ? '#33392b' : '#22271d';
						g.fillRect(x, y, 10, 10);
					}
				}
				const record = imageFor(file);
				if (record.ready && record.img) {
					g.imageSmoothingEnabled = false;
					g.drawImage(record.img, 0, 0, 40, 40);
				}
				box.append(canvas);
			}
			const first = imageCache.get(entry.files[0]);
			const sizeText = first && first.ready && first.img ? `${first.img.naturalWidth}×${first.img.naturalHeight}` : '加载中';
			const more = entry.files.length > 1 ? `（共 ${entry.files.length} 张）` : '';
			box.append(h('span', { class: 'sprite-meta' },
				`${entry.files[0]}${more} · ${sizeText} · ${entry.source}`));
			for (const file of entry.files.slice(0, 4)) {
				const record = imageCache.get(file);
				if (record && !record.ready && !record.failed) {
					record.waiters = record.waiters || [];
					record.waiters.push(paint);
					break;
				}
			}
		}

		paint();
		return box;
	}

	/** 贴图选择：常用 id 分组 + 已载入贴图分组 + 任意 id 输入。 */
	function spriteControl(opts) {
		const custom = h('input', { class: 'txt', value: opts.value, spellcheck: 'false' });
		const select = h('select', { class: 'sel' });
		select.append(h('optgroup', { label: '常用 id' },
			...FEATURED_SPRITES.map((id) => h('option', { value: id }, id.replace('minecraft:', '')))));
		const withFiles = Array.from(SPRITE_ENTRIES.entries()).filter(([, entry]) => entry.files.length > 0);
		if (withFiles.length) {
			select.append(h('optgroup', { label: `已载入贴图（${withFiles.length}）` },
				...withFiles.map(([id, entry]) => h('option', { value: id },
					id.replace('minecraft:', '') + (entry.files.length > 1 ? ` ×${entry.files.length}` : '')))));
		}
		select.value = opts.value;
		let preview = spritePreview(opts.value);
		const refresh = (id) => {
			const next = spritePreview(id);
			preview.replaceWith(next);
			preview = next;
		};
		select.addEventListener('change', () => {
			custom.value = select.value;
			opts.onChange(select.value);
			refresh(select.value);
			commit();
		});
		custom.addEventListener('change', () => {
			opts.onChange(custom.value);
			refresh(custom.value);
			commit();
		});
		return { el: h('div', { class: 'vec', style: 'flex-direction:column;gap:3px' }, custom, select, preview) };
	}

	// ───────────────────────── Inspector ─────────────────────────
	const SECTIONS = [
		{ id: 'base', icon: 'i-clock', title: '基础', note: () => `${fmtNum(state.def.duration, 2)}s${state.def.loop ? ' · 循环' : ''}` },
		{ id: 'emission', icon: 'i-emission', title: '发射', note: () => `${state.def.emission.mode} · ${state.def.emission.count}` },
		{ id: 'physics', icon: 'i-gravity', title: '物理', note: () => `重力 ${fmtNum(state.def.physics.gravity[1], 1)}` },
		{ id: 'appearance', icon: 'i-palette', title: '外观', note: () => state.def.appearance.blend },
		{ id: 'trail', icon: 'i-trail', title: '轨迹', note: () => (state.def.trail ? `${state.def.trail.mode} ×${state.def.trail.cap}` : '关闭') },
		{ id: 'splash', icon: 'i-splash', title: '溅射', note: () => (state.def.splash ? `${state.def.splash.count} 颗` : '关闭') },
		{ id: 'visibility', icon: 'i-eye', title: '可见性', note: () => `${fmtNum(state.def.visibility.viewDistance, 0)} 格` },
		{ id: 'params', icon: 'i-sliders', title: '样式参数', note: () => styleOf(state.def).title },
	];

	const sectionNodes = new Map();

	function buildSectionShell(spec) {
		const body = h('div', { class: 'section-body' });
		const note = h('span', { class: 'sec-note' });
		const head = h('div', {
			class: 'section-head',
			onclick: () => {
				state.open[spec.id] = !state.open[spec.id];
				node.classList.toggle('open', !!state.open[spec.id]);
			},
		}, icon(spec.icon), h('b', {}, spec.title), note, icon('i-chevron', 'chev'));
		const node = h('section', { class: 'section' + (state.open[spec.id] ? ' open' : ''), 'data-section': spec.id }, head, body);
		sectionNodes.set(spec.id, { node, body, note, spec });
		return node;
	}

	function rebuildInspector() {
		const inspector = $('#inspector');
		inspector.innerHTML = '';
		for (const spec of SECTIONS) inspector.append(buildSectionShell(spec));
		refreshSections();
	}

	function refreshSections() {
		for (const [id, entry] of sectionNodes) {
			entry.body.innerHTML = '';
			const builder = BUILDERS[id];
			if (builder) builder(entry.body);
			entry.note.textContent = entry.spec.note ? entry.spec.note() : '';
		}
	}

	function updateNotes() {
		for (const [, entry] of sectionNodes) {
			if (entry.spec.note) entry.note.textContent = entry.spec.note();
		}
	}

	// 各分区内容
	const BUILDERS = {
		base(body) {
			const def = state.def;
			body.append(row('时长', '一个周期或整段播放的长度（秒）。循环时按它取模。', numControl({
				value: def.duration, min: 0.05, max: 3600, step: 0.1,
				onChange: (v) => { def.duration = v; },
			})).el);
			body.append(row('循环', '每个周期重新发射一批粒子；轨迹与停止逻辑都按周期回溯。', toggleControl({
				value: def.loop, text: def.loop ? '开' : '关',
				onChange: (v) => { def.loop = v; },
			})).el);
			body.append(row('朝向', 'world 固定世界坐标；view 把局部 +Z 对齐触发时的视线方向。', selectControl({
				value: def.orientation,
				options: [['world', 'world · 世界坐标'], ['view', 'view · 视线方向']],
				onChange: (v) => { def.orientation = v; },
			})).el);
			body.append(row('反向', '按 duration - age 采样；与循环同时启用时先按 duration 取模。', toggleControl({
				value: def.reverse, text: def.reverse ? '开' : '关',
				onChange: (v) => { def.reverse = v; },
			})).el);
			body.append(row('时间缩放', '负值反向播放，绝对值改变速率（-8..8）。', numControl({
				value: def.timeScale, min: -8, max: 8, step: 0.1,
				onChange: (v) => { def.timeScale = v; },
			})).el);
			body.append(row('子特效 id', '本体结束后生成的目标定义 id，留空则不生成。', textControl({
				value: def.subEffect || '',
				onChange: (v) => { def.subEffect = v || null; },
			})).el);
			body.append(row('子特效触发', 'end 本体结束、impact 客户端粒子碰撞、particle_end 单个粒子消失。', selectControl({
				value: def.subEffectTrigger,
				options: [['end', 'end · 本体结束'], ['impact', 'impact · 粒子碰撞'], ['particle_end', 'particle_end · 粒子消失']],
				onChange: (v) => { def.subEffectTrigger = v; },
			})).el);
		},

		emission(body) {
			const e = state.def.emission;
			body.append(row('方式', 'burst 同时出生；stream 在窗口内均匀出生；random 随机出生。', segmentedControl({
				value: e.mode,
				options: [['burst', 'burst'], ['stream', 'stream'], ['random', 'random']],
				onChange: (v) => { e.mode = v; },
			})).el);
			body.append(row('粒子数', '单次采样的发射序号数量（1..8192），轨迹和雨丝另计。', numControl({
				value: e.count, min: 1, max: 8192, integer: true, step: 1,
				onChange: (v) => { e.count = v; },
			})).el);
			body.append(row('发射窗口', '出生时间区间 [开始, 结束]（秒）。burst 下仍参与氛围类样式的错峰。', vecControl({
				value: [e.windowStart, e.windowEnd],
				labels: ['始', '末'],
				onChange: (v) => { e.windowStart = v[0]; e.windowEnd = v[1]; },
			})).el);
			body.append(row('出生抖动', '在出生时刻上叠加的 -jitter..+jitter 秒抖动（0..10）。', numControl({
				value: e.jitter, min: 0, max: 10, step: 0.01,
				onChange: (v) => { e.jitter = v; },
			})).el);
			body.append(row('寿命', '单颗粒子的存活时间（秒）。缺省等于 duration。', numControl({
				value: e.lifetime, min: 0.05, max: 3600, step: 0.1,
				onChange: (v) => { e.lifetime = v; },
			})).el);
			body.append(row('寿命抖动', '每颗粒子寿命的随机比例（0..1）。循环时决定回溯几个周期。', numControl({
				value: e.lifetimeJitter, min: 0, max: 1, step: 0.01,
				onChange: (v) => { e.lifetimeJitter = v; },
			})).el);
			const maxLookback = clamp(Math.ceil(e.lifetime * (1 + Math.abs(e.lifetimeJitter)) / state.def.duration), 1, 8);
			body.append(h('div', { class: 'sub-note' },
				h('b', {}, '循环回溯 '), `${maxLookback} 个周期（寿命上界 ÷ 时长，上限 8）。`));
		},

		physics(body) {
			const p = state.def.physics;
			body.append(row('重力', '加速度（块/秒²）。只写一个数字时取 Y 分量。', vecControl({
				value: p.gravity, step: 0.1,
				onChange: (v) => { p.gravity = v; },
			})).el);
			body.append(row('阻尼', '线性阻尼（1/秒）。大于 0 时按解析闭式解积分。', numControl({
				value: p.drag, min: 0, max: 10, step: 0.01,
				onChange: (v) => { p.drag = v; },
			})).el);
			body.append(row('风', '稳定的风场速度（块/秒），与阻尼一起形成终端速度。', vecControl({
				value: p.wind, step: 0.1,
				onChange: (v) => { p.wind = v; },
			})).el);
		},

		appearance(body) {
			const a = state.def.appearance;
			body.append(row('贴图', spriteHint(a.sprite), spriteControl({
				value: a.sprite,
				onChange: (v) => { a.sprite = v; },
			})).el);
			body.append(row('变体选择', '原版定义有多张贴图时：particle 逐粒子、effect 整效共用、cycle 按 tick 轮换。', selectControl({
				value: a.spriteVariation,
				options: [['particle', 'particle · 逐粒子'], ['effect', 'effect · 整效共用'], ['cycle', 'cycle · 轮换']],
				onChange: (v) => { a.spriteVariation = v; },
			})).el);
			if (a.spriteVariation === 'cycle') {
				body.append(row('换图间隔', 'cycle 模式每隔多少 tick 换一张（1..200）。', numControl({
					value: a.spriteCycleTicks, min: 1, max: 200, integer: true, step: 1,
					onChange: (v) => { a.spriteCycleTicks = v; },
				})).el);
			}
			body.append(row('尺寸', '面片边长（块）。可写含 t/d/u/n 的表达式。', numControl({
				value: a.size, min: 0, max: 16, allowExpr: true, step: 0.01,
				onChange: (v) => { a.size = v; },
			})).el);
			body.append(row('尺寸抖动', '逐粒子尺寸的随机比例（0..1）。', numControl({
				value: a.sizeJitter, min: 0, max: 1, step: 0.01,
				onChange: (v) => { a.sizeJitter = v; },
			})).el);
			body.append(row('尺寸曲线', '横轴归一化年龄 u，纵轴尺寸倍率；点击空白加点，右键删点。', curveControl({
				value: flatten(a.sizeByAge, [0, 1, 1, 0]),
				defaultPoints: [0, 1, 1, 0],
				onChange: (v) => { a.sizeByAge = unflatten(v); },
			})).el);
			body.append(row('基色', '粒子基色；有渐变时渐变优先。', colorControl({
				value: typeof a.color === 'string' ? a.color : '#FFFFFF',
				onChange: (v) => { a.color = v; },
			})).el);
			body.append(row('亮度抖动', '逐粒子颜色亮度的随机比例（0..1）。', numControl({
				value: a.colorJitter, min: 0, max: 1, step: 0.01,
				onChange: (v) => { a.colorJitter = v; },
			})).el);
			const useGradient = Array.isArray(a.colorByAge) && a.colorByAge.length >= 2;
			body.append(row('按年龄渐变', '开启后按归一化年龄在多个颜色间插值。', toggleControl({
				value: useGradient, text: useGradient ? '开' : '关',
				onChange: (v) => {
					a.colorByAge = v ? [[0, a.color || '#FFFFFF'], [1, '#FF6B1A']] : null;
					refreshSections();
				},
			})).el);
			if (useGradient) {
				body.append(row('渐变点', '第一个点应从 u=0 开始，最后一个到 u=1。', gradientControl({
					value: a.colorByAge,
					onChange: (v) => { a.colorByAge = v; },
				})).el);
			}
			body.append(row('透明度曲线', '横轴 u，纵轴透明度 0..1；≤0.004 的粒子不写顶点。', curveControl({
				value: flatten(a.alphaByAge, [0, 1, 0.7, 1, 1, 0]),
				defaultPoints: [0, 1, 0.7, 1, 1, 0],
				onChange: (v) => { a.alphaByAge = unflatten(v); },
			})).el);
			body.append(row('混合', 'additive 叠加（自发光）；alpha 半透明（按距离排序）。', segmentedControl({
				value: a.blend,
				options: [['additive', 'additive'], ['alpha', 'alpha']],
				onChange: (v) => { a.blend = v; },
			})).el);
			body.append(row('全亮', '忽略世界光照直接输出原色。', toggleControl({
				value: a.fullbright, text: a.fullbright ? '开' : '关',
				onChange: (v) => { a.fullbright = v; },
			})).el);
			body.append(row('拉伸', '沿速度方向拉长的时长（秒），0 关闭；只对有速度的样式生效。', numControl({
				value: a.stretch, min: 0, max: 10, allowExpr: true, step: 0.01,
				onChange: (v) => { a.stretch = v; },
			})).el);
			body.append(row('自转', '面片绕视线方向的自转（度/秒）。', numControl({
				value: a.spin, allowExpr: true, step: 1,
				onChange: (v) => { a.spin = v; },
			})).el);
		},

		trail(body) {
			const def = state.def;
			const enabled = !!def.trail;
			body.append(row('启用', '写 trail 对象即启用；关闭则整段轨迹不生成。', toggleControl({
				value: enabled, text: enabled ? '开' : '关',
				onChange: (v) => {
					def.trail = v ? {
						mode: 'time', interval: 0.1, distance: 0.5, lag: 1, cap: 8, spread: 0,
						drift: 0, sizeScale: 0.8, alphaScale: 0.7, color: null,
					} : null;
					refreshSections();
				},
			})).el);
			if (!enabled) {
				body.append(h('div', { class: 'sub-note' }, '轨迹关闭。开启后每个源粒子按回溯方式补若干段历史位置。'));
				return;
			}
			const t = def.trail;
			body.append(row('回溯方式', 'time 按固定时间间隔取样；distance 按粒子沿路径走过的距离取点。', segmentedControl({
				value: t.mode,
				options: [['time', 'time · 时间'], ['distance', 'distance · 距离']],
				onChange: (v) => { t.mode = v; refreshSections(); },
			})).el);
			if (t.mode === 'time') {
				body.append(row('间隔', '每段的回溯间隔（秒，0.01..10）。', numControl({
					value: t.interval, min: 0.01, max: 10, step: 0.01,
					onChange: (v) => { t.interval = v; },
				})).el);
			} else {
				body.append(row('距离', '每段沿路径的距离（块，0.01..16）。', numControl({
					value: t.distance, min: 0.01, max: 16, step: 0.01,
					onChange: (v) => { t.distance = v; },
				})).el);
				body.append(row('回溯窗口', '最长回溯时间（秒），决定能追上多少段。', numControl({
					value: t.lag, min: 0.01, max: 16, step: 0.01,
					onChange: (v) => { t.lag = v; },
				})).el);
			}
			body.append(row('段数', '每颗源粒子最多几段（不含源粒子，1..32）。', numControl({
				value: t.cap, min: 1, max: 32, integer: true, step: 1,
				onChange: (v) => { t.cap = v; },
			})).el);
			body.append(row('横向偏移', '第 k 段相对源粒子的横向随机偏移半径（块，取 spread·k/cap）。', numControl({
				value: t.spread, min: 0, max: 8, step: 0.01,
				onChange: (v) => { t.spread = v; },
			})).el);
			body.append(row('飘散', '轨迹粒子沿随机方向的飘散速度（块/秒）；0 时沿历史路径跟随。', numControl({
				value: t.drift, min: 0, max: 16, step: 0.01,
				onChange: (v) => { t.drift = v; },
			})).el);
			body.append(row('尺寸倍率', '第 k 段乘 sizeScale^k（0..1）。', numControl({
				value: t.sizeScale, min: 0, max: 1, step: 0.01,
				onChange: (v) => { t.sizeScale = v; },
			})).el);
			body.append(row('透明倍率', '第 k 段乘 alphaScale^k（0..1）。', numControl({
				value: t.alphaScale, min: 0, max: 1, step: 0.01,
				onChange: (v) => { t.alphaScale = v; },
			})).el);
			const useColor = typeof t.color === 'string';
			body.append(row('独立颜色', '覆盖轨迹 RGB；alpha 仍按段衰减。', toggleControl({
				value: useColor, text: useColor ? '开' : '关',
				onChange: (v) => { t.color = v ? '#B1DC58' : null; refreshSections(); },
			})).el);
			if (useColor) {
				body.append(row('轨迹颜色', '', colorControl({
					value: t.color,
					onChange: (v) => { t.color = v; },
				})).el);
			}
			body.append(h('div', { class: 'sub-note' },
				h('b', {}, '预算 '), `轨迹段数 × 源粒子数，单特效上限 ${Fx.MAX_TRAIL_PARTICLES} 段。`));
		},

		splash(body) {
			const def = state.def;
			const enabled = !!def.splash;
			body.append(row('启用', '源粒子进入固体方块时在方块顶面生成一簇粒子。', toggleControl({
				value: enabled, text: enabled ? '开' : '关',
				onChange: (v) => {
					def.splash = v ? { color: null, count: 4, size: 0.12, speed: 1.2, life: 0.45 } : null;
					refreshSections();
				},
			})).el);
			if (!enabled) {
				body.append(h('div', { class: 'sub-note' }, '关闭。预览里需要打开「地面」开关才能看到碰撞。'));
				return;
			}
			const s = def.splash;
			const useColor = typeof s.color === 'string';
			body.append(row('落点颜色', '缺省金色 #FFC84D。', toggleControl({
				value: useColor, text: useColor ? '自定义' : '缺省',
				onChange: (v) => { s.color = v ? '#FFC84D' : null; refreshSections(); },
			})).el);
			if (useColor) {
				body.append(row('颜色', '', colorControl({ value: s.color, onChange: (v) => { s.color = v; } })).el);
			}
			body.append(row('数量', '每次碰撞生成几颗（1..16）。', numControl({
				value: s.count, min: 1, max: 16, integer: true, step: 1,
				onChange: (v) => { s.count = v; },
			})).el);
			body.append(row('尺寸', '单颗溅射面片边长（块，0.01..1）。', numControl({
				value: s.size, min: 0.01, max: 1, step: 0.01,
				onChange: (v) => { s.size = v; },
			})).el);
			body.append(row('初速度', '水平/向上初速度（块/秒，0..8）。', numControl({
				value: s.speed, min: 0, max: 8, step: 0.1,
				onChange: (v) => { s.speed = v; },
			})).el);
			body.append(row('寿命', '溅射粒子寿命（秒，0.05..3）。', numControl({
				value: s.life, min: 0.05, max: 3, step: 0.01,
				onChange: (v) => { s.life = v; },
			})).el);
			body.append(h('div', { class: 'sub-note' }, '预览用 y=0 的水平地面代替方块查询，需打开左上「地面」开关。'));
		},

		visibility(body) {
			const v = state.def.visibility;
			body.append(row('视距', '客户端可见距离（格，8..512），与场景视距取较小值。', numControl({
				value: v.viewDistance, min: 8, max: 512, step: 1,
				onChange: (val) => { v.viewDistance = val; },
			})).el);
			body.append(row('需要场景', '必须位于演唱会场景包围盒内才下发。', toggleControl({
				value: v.requireScene, text: v.requireScene ? '是' : '否',
				onChange: (val) => { v.requireScene = val; },
			})).el);
			body.append(row('包围球外扩', '包围球额外外扩（格，0..64），避免边缘被剔除。', numControl({
				value: v.boundsPadding, min: 0, max: 64, step: 0.5,
				onChange: (val) => { v.boundsPadding = val; },
			})).el);
			const radius = state.bound ? state.bound.radius : 0;
			body.append(h('div', { class: 'sub-note' },
				h('b', {}, '包围球 '), `${fmtNum(radius, 1)} 格（6 个年龄采样取最大距离 + 1）。`));
		},

		params(body) {
			const style = styleOf(state.def);
			const params = state.def.params;
			body.append(h('div', { class: 'sub-note' },
				h('b', {}, style.title), ` · ${style.id.replace('astrorbitsparticle:', '')}`,
				h('br'), '数值可写表达式：t 秒、d 时长、u=t/d、n 粒子数；函数 clamp/mix/smoothstep/fract/sign/step。'));

			for (const spec of style.schema.params) {
				const value = params[spec.name];
				const current = value === undefined || value === null ? defaultOf(spec) : value;
				let control;
				if (spec.kind === 'boolean') {
					control = toggleControl({
						value: !!current, text: current ? '开' : '关',
						onChange: (v) => setParam(spec.name, v),
					});
				} else if (spec.kind === 'vector') {
					control = vecControl({
						value: Array.isArray(current) ? current : spec.default,
						step: 0.1,
						onChange: (v) => setParam(spec.name, v),
					});
				} else if (spec.kind === 'numberList') {
					control = vecControl({
						value: Array.isArray(current) ? current : spec.default,
						step: 0.5,
						onChange: (v) => setParam(spec.name, v),
					});
				} else if (spec.kind === 'stringList') {
					const vals = Array.isArray(current) ? current.slice() : spec.default.slice();
					control = chipsControl({
						options: spec.default,
						value: vals,
						onChange: (v) => setParam(spec.name, v),
					});
				} else if (spec.kind === 'integer') {
					control = numControl({
						value: Number(current), min: spec.min, max: spec.max, integer: true, step: 1,
						onChange: (v) => setParam(spec.name, v),
					});
				} else if (spec.kind === 'color') {
					const inherit = current === undefined || current === null;
					control = h('div', { style: 'display:flex;flex-direction:column;gap:3px;width:100%' },
						h('label', { class: 'toggle' },
							h('input', {
								type: 'checkbox', checked: inherit ? '' : null,
								onchange: (ev) => {
									setParam(spec.name, ev.target.checked ? undefined : '#FFC84D');
									refreshSections();
								},
							}),
							h('span', {}, '继承外观颜色')),
						inherit ? h('div', { class: 'row-doc' }, '（未设置，使用 appearance.color）')
							: colorControl({ value: current, onChange: (v) => setParam(spec.name, v) }).el);
					control = { el: control };
				} else {
					control = numControl({
						value: typeof current === 'number' ? current : Number(current) || 0,
						min: spec.min, max: spec.max, allowExpr: true, step: stepFor(spec.min, spec.max, 0.1),
						onChange: (v) => setParam(spec.name, v),
					});
				}
				const doc = spec.doc + (spec.range ? `（${spec.range}）` : '');
				body.append(row(spec.name, doc, control).el);
			}
		},
	};

	function defaultOf(spec) {
		if (spec.kind === 'vector' || spec.kind === 'numberList' || spec.kind === 'stringList') return spec.default.slice();
		if (spec.kind === 'color') return undefined;
		return spec.default;
	}

	function setParam(name, value) {
		if (value === undefined) delete state.def.params[name];
		else state.def.params[name] = value;
	}

	function flatten(points, fallback) {
		if (!Array.isArray(points)) return fallback.slice();
		const out = [];
		for (const pair of points) {
			if (Array.isArray(pair) && pair.length >= 2) out.push(Number(pair[0]), Number(pair[1]));
		}
		return out.length >= 4 ? out : fallback.slice();
	}

	function unflatten(flat) {
		const out = [];
		for (let i = 0; i < flat.length; i += 2) {
			out.push([Math.round(flat[i] * 1000) / 1000, Math.round(flat[i + 1] * 1000) / 1000]);
		}
		return out;
	}

	// ───────────────────────── 顶部/状态刷新 ─────────────────────────
	function updateChrome() {
		const style = styleOf(state.def);
		$('#chipStyle').textContent = style.id.replace('astrorbitsparticle:', '');
		$('#chipCount').textContent = `${state.def.emission.count} 粒子`;
		$('#stageName').textContent = style.title;
		$('#stageId').textContent = style.id;

		for (const card of document.querySelectorAll('.style-card')) {
			card.classList.toggle('on', card.dataset.style === style.id);
		}
		updateNotes();
		updateModuleList();
		updateTransport();

		const dot = $('#diagDot');
		dot.hidden = !(state.problems.length > 0);
	}

	function updateTransport() {
		const range = $('#timeRange');
		const duration = state.def.duration;
		range.max = String(duration);
		range.value = String(clamp(state.age, 0, duration));
		$('#btnPlay').innerHTML = '';
		$('#btnPlay').append(icon(state.playing ? 'i-pause' : 'i-play'));
		updateTransportLabel();
	}

	function updateTransportLabel() {
		const range = $('#timeRange');
		const duration = state.def.duration;
		const value = clamp(state.age, 0, duration);
		if (!state.scrubbing) range.value = String(value);
		const fill = duration > 0 ? (value / duration) * 100 : 0;
		range.style.setProperty('--fill', fill.toFixed(1) + '%');
		$('#timeLabel').textContent = `${value.toFixed(2)} / ${duration.toFixed(2)} s`;
		const hint = $('#timeScaleHint');
		const bits = [];
		if (state.def.loop) bits.push('loop');
		if (state.def.reverse) bits.push('reverse');
		if (state.def.timeScale !== 1) bits.push('timeScale ' + state.def.timeScale);
		if (state.playback && state.playback.stopped) bits.push('已停止发射');
		hint.textContent = bits.join(' · ');
	}

	function updateModuleList() {
		const list = $('#moduleList');
		list.innerHTML = '';
		for (const spec of SECTIONS) {
			const entry = sectionNodes.get(spec.id);
			list.append(h('li', {
				onclick: () => {
					state.open[spec.id] = true;
					if (entry) {
						entry.node.classList.add('open');
						entry.node.scrollIntoView({ behavior: 'smooth', block: 'start' });
						entry.node.classList.remove('flash');
						void entry.node.offsetWidth;
						entry.node.classList.add('flash');
					}
				},
			}, icon(spec.icon), h('span', {}, spec.title), h('em', {}, entry && entry.spec.note ? entry.spec.note() : '')));
		}
	}

	// ───────────────────────── 输出区 ─────────────────────────
	function currentJson() {
		return Fx.definitionToJson(state.def);
	}

	function highlightJson(text) {
		const escaped = text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
		return escaped.replace(/("(?:[^"\\]|\\.)*")(\s*:)?|(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)|\b(true|false|null)\b/g,
			(match, str, colon, num, lit) => {
				if (str && colon) return `<span class="k">${str}</span>${colon}`;
				if (str) return `<span class="s">${str}</span>`;
				if (num) return `<span class="n">${num}</span>`;
				if (lit) return `<span class="n">${lit}</span>`;
				return match;
			});
	}

	function updateOutput() {
		const body = $('#outputBody');
		const raw = state.outputTab === 'raw';
		$('#rawPane').hidden = !raw;
		body.hidden = raw;
		if (raw) {
			if (state.rawPainted !== state.rawText) {
				$('#rawText').value = state.rawText;
				state.rawPainted = state.rawText;
			}
			refreshRawHint();
			return;
		}
		if (state.outputTab === 'json') {
			body.innerHTML = highlightJson(JSON.stringify(currentJson(), null, 2));
			return;
		}
		if (state.outputTab === 'command') {
			body.textContent = commandText();
			return;
		}
		body.textContent = diagnosticText();
	}

	function effectSlug() {
		return (state.effectName || 'my_effect').replace(/[^a-z0-9_\-]/gi, '_');
	}

	/** 当前定义与哪个内置测试预设完全一致（用于给出 /astrorticle test 命令）。 */
	function matchingTestPreset() {
		const target = JSON.stringify(currentJson());
		for (const preset of Presets.TEST) {
			try {
				if (JSON.stringify(Fx.definitionToJson(normalizeDef(Fx.parseDefinition(preset.json)))) === target) {
					return preset;
				}
			} catch (err) { /* 忽略单个预设的解析失败 */ }
		}
		return null;
	}

	function commandText() {
		const name = effectSlug();
		const minified = JSON.stringify(currentJson());
		const preset = matchingTestPreset();
		const lines = [];
		if (preset) {
			lines.push(`# 当前定义与内置测试预设 ${preset.id} 完全一致，最省事的播法：`);
			lines.push(`/astrorticle test ${preset.id}`);
			lines.push('');
		}
		lines.push(`# 1) 存为 config/astrorbitsparticle/effects/${name}.json，重载后按 id 播放`);
		lines.push('/astrorticle reload');
		lines.push(`/astrorticle effect play astrorbitsparticle:${name}`);
		lines.push('');
		lines.push('# 2) 或者把定义直接贴进命令（一行 JSON，需要 gamemaster 权限）');
		lines.push(`/astrorticle effect play ${minified}`);
		lines.push('');
		lines.push('# 3) 常用：查看样式参数 / 列出已加载特效');
		lines.push(`/astrorticle schema ${styleOf(state.def).id}`);
		lines.push('/astrorticle effect list');
		return lines.join('\n');
	}

	function diagnosticText() {
		const lines = [];
		const style = styleOf(state.def);
		lines.push(`样式        ${style.title}（${style.id}）`);
		lines.push(`粒子数      ${state.def.emission.count}`);
		if (state.bound && state.playback) {
			const playback = state.playback;
			lines.push(`预算上限    ${playback.maxParticles}（源 ${playback.sourceMaxParticles} + 轨迹 ${playback.trailMaxParticles} + 溅射 ${state.bound.splash ? 512 : 0}）`);
			lines.push(`包围球      ${fmtNum(state.bound.radius, 2)} 格`);
			const lifespan = state.def.emission.lifetime * (1 + Math.abs(state.def.emission.lifetimeJitter));
			lines.push(`回溯周期    ${clamp(Math.ceil(lifespan / state.def.duration), 1, 8)}`);
		}
		lines.push(`种子        ${state.seed}`);
		if (state.useGround) lines.push(`地面        y = ${state.groundY}（splash 检测面）`);
		const entry = SPRITE_ENTRIES.get(state.def.appearance.sprite);
		lines.push(`贴图        ${state.def.appearance.sprite} · ${entry && entry.files.length
			? entry.files.length + ' 张（' + entry.source + '）'
			: '程序化形状'}`);
		lines.push('');
		if (state.problems.length) {
			lines.push('参数问题（模组会拒绝该定义）：');
			for (const problem of state.problems) lines.push('  · ' + problem);
		} else {
			lines.push('参数问题    无');
		}
		return lines.join('\n');
	}

	function refreshRawHint() {
		const hint = $('#rawHint');
		let stale = false;
		try {
			// 语义比较：默认值与写法差异不算改动
			const parsed = Fx.definitionToJson(normalizeDef(Fx.parseDefinition($('#rawText').value)));
			stale = JSON.stringify(parsed) !== JSON.stringify(currentJson());
		} catch (err) {
			stale = true;
		}
		hint.className = stale ? 'stale' : '';
		hint.textContent = stale
			? '这份原文与当前状态不同（已从左侧改过参数）；点「重填当前」可换成当前定义。'
			: '与当前状态一致。改完点「应用」覆盖编辑器状态。';
	}

	function setOutputTab(tab) {
		state.outputTab = tab;
		document.querySelector('.stage').classList.toggle('raw-open', tab === 'raw');
		for (const button of document.querySelectorAll('#outputTabs button')) {
			button.classList.toggle('on', button.dataset.tab === tab);
		}
		updateOutput();
	}

	function downloadFile(filename, text, mime) {
		const blob = new Blob([text], { type: (mime || 'text/plain') + ';charset=utf-8' });
		const url = URL.createObjectURL(blob);
		const anchor = h('a', { href: url, download: filename });
		document.body.append(anchor);
		anchor.click();
		anchor.remove();
		setTimeout(() => URL.revokeObjectURL(url), 4000);
		toast('已下载 ' + filename);
	}

	function downloadActive() {
		const name = effectSlug();
		if (state.outputTab === 'command') {
			downloadFile('play_' + name + '.mcfunction', $('#outputBody').textContent, 'text/plain');
		} else if (state.outputTab === 'diag') {
			downloadFile(name + '-diag.txt', $('#outputBody').textContent, 'text/plain');
		} else if (state.outputTab === 'raw') {
			downloadFile(name + '.json', $('#rawText').value, 'application/json');
		} else {
			downloadFile(name + '.json', JSON.stringify(currentJson(), null, 2), 'application/json');
		}
	}

	function setRawText(text) {
		state.rawText = text;
		if (state.outputTab === 'raw') {
			$('#rawText').value = text;
			state.rawPainted = text;
			refreshRawHint();
		}
	}

	function applyRawText() {
		const text = $('#rawText').value;
		try {
			state.def = normalizeDef(Fx.parseDefinition(text));
			state.age = 0;
			state.playing = true;
			state.dirty = true;
			state.rawText = text;
			state.rawPainted = text;
			rebuildInspector();
			updateChrome();
			state.dirty = true;
			ensureBound();
			frameEffect();
			refreshRawHint();
			toast('已应用原始配置');
		} catch (err) {
			toast('JSON 解析失败：' + err.message, true);
		}
	}

	// ───────────────────────── 模板与说明 ─────────────────────────
	function openModal(title, bodyNodes, footNodes) {
		$('#modalTitle').textContent = title;
		const body = $('#modalBody');
		body.innerHTML = '';
		for (const node of bodyNodes) body.append(node);
		const foot = $('#modalFoot');
		foot.innerHTML = '';
		for (const node of footNodes || []) foot.append(node);
		$('#modal').hidden = false;
	}

	function closeModal() { $('#modal').hidden = true; }

	function showTemplates() {
		const groups = [
			['测试预设 · 与 golden 基线同源', Presets.TEST],
			['演唱会模板', Presets.SHOW],
		];
		const nodes = [];
		for (const [title, list] of groups) {
			nodes.push(h('h4', { style: 'margin:10px 0 6px;font-size:13px' }, title));
			nodes.push(h('div', { class: 'grid2' }, list.map((preset) => h('button', {
				class: 'tpl-card',
				onclick: () => { applyPreset(preset); closeModal(); },
			}, icon('s-' + preset.icon), h('b', {}, preset.name), h('small', {}, preset.desc),
				h('em', {}, preset.style)))));
		}
		openModal('模板库', nodes, [
			h('span', { class: 'hint' }, '模板会把样式、参数、外观与轨迹整体替换成该定义。'),
			h('button', { class: 'btn', onclick: closeModal }, '关闭'),
		]);
	}

	function showImport() {
		const area = h('textarea', { spellcheck: 'false' });
		area.value = JSON.stringify(currentJson(), null, 2);
		openModal('导入定义 JSON', [h('div', { style: 'font-size:12px;color:#74796a;margin-bottom:6px' }, '粘贴一个 effects/*.json 的内容，或编辑器里复制出来的定义。'), area], [
			h('span', { class: 'hint' }, '导入会整体替换当前定义。'),
			h('button', { class: 'btn', onclick: closeModal }, '取消'),
			h('button', {
				class: 'btn btn-primary',
				onclick: () => {
					try {
						state.def = normalizeDef(Fx.parseDefinition(area.value));
						state.age = 0;
						state.playing = true;
						state.dirty = true;
						rebuildInspector();
						updateChrome();
						state.dirty = true;
						ensureBound();
						setRawText(area.value);
						closeModal();
						notify('已导入定义');
					} catch (err) {
						toast('JSON 解析失败：' + err.message, true);
					}
				},
			}, '载入'),
		]);
	}

	function showHelp() {
		const doc = h('div', { class: 'doc', html: `
			<h4>这个预览是什么</h4>
			<p>把模组的客户端采样代码逐条移植成 JS：同一套 splitmix64 哈希、10 条随机流、9 个运动核、
			LUT 烘焙与轨迹/溅射逻辑，逐帧在 Canvas 2D 上画出来。参数、默认值、夹取范围与
			<code>effects/*.json</code> 完全一致，右下输出区就是可以直接存盘的 JSON。</p>
			<p>9 个测试预设与仓库 <code>golden/*.txt</code> 逐值比对：粒子数与 ARGB 完全一致，
			位置/尺寸偏差 ≤ 1e-5 格（float32 与 JS double 的末位舍入）。</p>

			<h4>快捷键</h4>
			<table>
				<tr><th>按键</th><th>作用</th></tr>
				<tr><td><span class="kbd">空格</span></td><td>播放 / 暂停</td></tr>
				<tr><td><span class="kbd">R</span></td><td>回到起点</td></tr>
				<tr><td><span class="kbd">←</span> <span class="kbd">→</span></td><td>逐帧后退 / 前进（1/60 秒）</td></tr>
				<tr><td><span class="kbd">1</span>…<span class="kbd">9</span></td><td>切换样式</td></tr>
				<tr><td><span class="kbd">G</span></td><td>网格开关</td></tr>
			</table>

			<h4>视图操作</h4>
			<ul>
				<li>左键拖拽旋转，滚轮缩放，Shift 拖拽平移</li>
				<li>「地面」开关在 y=0 放一块固体平面，只有打开后 splash 才会检测落点</li>
				<li>「全亮」关闭时预览不会模拟世界光照，颜色仍按原色显示</li>
			</ul>

			<h4>数值与表达式</h4>
			<p>样式参数与外观的 size / stretch / spin 都可以写表达式，变量
			<code>t</code> 秒、<code>d</code> 时长、<code>u=t/d</code>、<code>n</code> 粒子数；
			可用 exp4j 内置函数与模组追加的
			<code>clamp</code> <code>mix</code> <code>smoothstep</code> <code>fract</code>
			<code>sign</code> <code>step</code>。</p>

			<h4>已知差异</h4>
			<ul>
				<li>贴图：无法读取客户端图集，按 sprite 名映射成 6 种程序化形状（glow / star / spark / drop / rod / square）。</li>
				<li>世界光照、Iris 管线、Sodium 精灵活动不参与预览。</li>
				<li>溅射用水平地面代替逐方块查询；方块碰撞按每帧检测而不是 20 tick。</li>
				<li>渲染步长固定按 9000 个粒子封顶，模组里按距离与帧预算动态降级。</li>
			</ul>

			<h4>数值范围</h4>
			<p>输入超出声明范围会被夹到边界，与 Binder 的行为一致；未知参数会出现在「诊断」页，
			模组收到这种定义会拒绝并记日志。</p>
		` });
		openModal('说明', [doc], [h('button', { class: 'btn', onclick: closeModal }, '关闭')]);
	}

	function notify(text) { toast(text); }

	// ───────────────────────── 交互装配 ─────────────────────────
	function buildStyleGrid() {
		const grid = $('#styleGrid');
		grid.innerHTML = '';
		St.STYLES.forEach((style, index) => {
			const card = h('button', {
				class: 'style-card' + (style.id === state.def.style ? ' on' : ''),
				'data-style': style.id,
				title: `${style.title}（${index + 1}）`,
				onclick: () => selectStyle(style.id),
			}, icon('s-' + style.icon), h('span', {}, style.title));
			grid.append(card);
		});
	}

	function selectStyle(id) {
		if (state.def.style === id) return;
		const style = St.styleById(id);
		if (!style) return;
		state.def.style = id;
		state.def.params = St.defaultParams(style);
		state.age = 0;
		state.dirty = true;
		rebuildInspector();
		updateChrome();
		state.dirty = true;
		ensureBound();
		frameEffect();
		setRawText(JSON.stringify(currentJson(), null, 2));
	}

	function buildPresetList() {
		const list = $('#presetList');
		list.innerHTML = '';
		for (const preset of Presets.PRESETS) {
			list.append(h('button', {
				class: 'preset-card',
				onclick: () => applyPreset(preset),
				title: preset.desc,
			}, icon('s-' + preset.icon), h('b', {}, preset.name), h('small', {}, preset.desc)));
		}
		$('#presetCount').textContent = Presets.PRESETS.length + ' 个';
	}

	function bindRange(input, apply) {
		input.addEventListener('pointerdown', () => { state.scrubbing = true; });
		input.addEventListener('input', () => {
			apply(Number(input.value));
			state.playing = false;
			updateTransport();
		});
		input.addEventListener('change', () => { state.scrubbing = false; });
	}

	function setupEvents() {
		// 播放控制
		$('#btnPlay').addEventListener('click', () => {
			state.playing = !state.playing;
			if (state.playing && state.age >= state.def.duration && !state.def.loop) restart();
			updateTransport();
		});
		$('#btnReset').addEventListener('click', restart);
		$('#btnFit').addEventListener('click', frameEffect);
		$('#chkPreviewLoop').addEventListener('change', (event) => { state.previewLoop = event.target.checked; });

		bindRange($('#timeRange'), (value) => {
			state.age = value;
			if (state.playback) {
				state.playback.stopped = false;
				state.playback.resetSplash();
				if (!state.def.loop && value >= state.def.duration) state.playback.stop(state.def.duration);
			}
		});

		for (const btn of document.querySelectorAll('#speedSel button')) {
			btn.addEventListener('click', () => {
				state.speed = Number(btn.dataset.speed);
				for (const other of document.querySelectorAll('#speedSel button')) other.classList.toggle('on', other === btn);
			});
		}

		for (const btn of document.querySelectorAll('#camPresets button')) {
			btn.addEventListener('click', () => {
				const preset = btn.dataset.cam;
				for (const other of document.querySelectorAll('#camPresets button')) other.classList.toggle('on', other === btn);
				if (preset === 'front') { state.camera.yaw = 0; state.camera.pitch = 8; state.camera.dist = 22; }
				if (preset === 'side') { state.camera.yaw = 90; state.camera.pitch = 12; state.camera.dist = 22; }
				if (preset === 'top') { state.camera.yaw = 0; state.camera.pitch = 78; state.camera.dist = 26; }
				if (preset === 'free') { state.camera.yaw = 34; state.camera.pitch = 15; state.camera.dist = 22; }
			});
		}

		$('#chkGrid').addEventListener('change', (event) => { state.showGrid = event.target.checked; });
		for (const button of document.querySelectorAll('#spriteMode button')) {
			button.addEventListener('click', () => {
				state.spriteMode = button.dataset.mode;
				for (const other of document.querySelectorAll('#spriteMode button')) other.classList.toggle('on', other === button);
			});
		}
		$('#chkGround').addEventListener('change', (event) => {
			state.useGround = event.target.checked;
			// 地面放在原点下方，正好接住大多数样式下落的粒子
			state.groundY = state.useGround ? autoGroundY() : null;
			if (state.playback) {
				state.playback.groundY = state.groundY;
				state.playback.resetSplash();
			}
			updateOutput();
		});

		// 种子
		const seedInput = $('#seedInput');
		seedInput.addEventListener('change', () => {
			state.seed = seedInput.value.trim() || '12345';
			rebuildPlayback();
			updateOutput();
		});

		// 输出区
		for (const btn of document.querySelectorAll('#outputTabs button')) {
			btn.addEventListener('click', () => setOutputTab(btn.dataset.tab));
		}
		$('#btnDownload').addEventListener('click', downloadActive);
		$('#btnRawApply').addEventListener('click', applyRawText);
		$('#btnRawRefill').addEventListener('click', () => {
			setRawText(JSON.stringify(currentJson(), null, 2));
			toast('已填入当前定义');
		});
		$('#rawText').addEventListener('input', refreshRawHint);

		$('#effectName').addEventListener('input', (event) => {
			state.effectName = event.target.value;
			if (state.outputTab === 'command') updateOutput();
		});

		$('#btnCopy').addEventListener('click', async () => {
			let text;
			if (state.outputTab === 'json') text = JSON.stringify(currentJson(), null, 2);
			else if (state.outputTab === 'command') text = $('#outputBody').textContent;
			else text = $('#outputBody').textContent;
			try {
				await navigator.clipboard.writeText(text);
				toast('已复制到剪贴板');
			} catch (err) {
				const area = document.createElement('textarea');
				area.value = text;
				document.body.append(area);
				area.select();
				try { document.execCommand('copy'); toast('已复制到剪贴板'); }
				catch (e) { toast('复制失败，请手动选择文本', true); }
				area.remove();
			}
		});

		$('#btnImport').addEventListener('click', showImport);
		$('#btnTemplates').addEventListener('click', showTemplates);
		$('#btnHelp').addEventListener('click', showHelp);
		$('#modalClose').addEventListener('click', closeModal);
		$('#modal').addEventListener('click', (event) => { if (event.target.id === 'modal') closeModal(); });

		// 画布交互
		const wrap = canvas.parentElement;
		let dragging = null;
		canvas.addEventListener('pointerdown', (event) => {
			canvas.classList.add('dragging');
			canvas.setPointerCapture(event.pointerId);
			dragging = {
				x: event.clientX, y: event.clientY,
				yaw: state.camera.yaw, pitch: state.camera.pitch,
				target: state.camera.target.slice(),
				pan: event.shiftKey,
			};
		});
		canvas.addEventListener('pointermove', (event) => {
			if (!dragging) return;
			const dx = event.clientX - dragging.x;
			const dy = event.clientY - dragging.y;
			if (dragging.pan) {
				const cam = cameraBasis();
				const scale = state.camera.dist * 0.0016;
				state.camera.target[0] = dragging.target[0] - cam.right[0] * dx * scale + cam.up[0] * dy * scale;
				state.camera.target[1] = dragging.target[1] - cam.right[1] * dx * scale + cam.up[1] * dy * scale;
				state.camera.target[2] = dragging.target[2] - cam.right[2] * dx * scale + cam.up[2] * dy * scale;
			} else {
				state.camera.yaw = dragging.yaw - dx * 0.35;
				state.camera.pitch = clamp(dragging.pitch + dy * 0.3, -85, 85);
				for (const other of document.querySelectorAll('#camPresets button')) other.classList.remove('on');
			}
		});
		canvas.addEventListener('pointerup', () => { dragging = null; canvas.classList.remove('dragging'); });
		canvas.addEventListener('wheel', (event) => {
			event.preventDefault();
			state.camera.dist = clamp(state.camera.dist * (1 + Math.sign(event.deltaY) * 0.1), 3, 200);
		}, { passive: false });

		// 键盘
		window.addEventListener('keydown', (event) => {
			const target = event.target;
			if (target && target.matches && target.matches('input, textarea, select')) return;
			if (event.key === ' ') {
				event.preventDefault();
				$('#btnPlay').click();
			} else if (event.key === 'r' || event.key === 'R') {
				restart();
			} else if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
				event.preventDefault();
				const step = event.key === 'ArrowRight' ? 1 / 60 : -1 / 60;
				state.age = clamp(state.age + step, 0, state.def.duration);
				state.playing = false;
				updateTransport();
			} else if (event.key === 'g' || event.key === 'G') {
				state.showGrid = !state.showGrid;
				$('#chkGrid').checked = state.showGrid;
			} else if (event.key === 'f' || event.key === 'F') {
				frameEffect();
			} else if (/^[1-9]$/.test(event.key)) {
				const style = St.STYLES[Number(event.key) - 1];
				if (style) selectStyle(style.id);
			}
		});

		window.addEventListener('resize', resize);
	}

	// ───────────────────────── 启动 ─────────────────────────
	/** 支持 ?preset=spiral&age=2&seed=12345&paused=1，方便把某个状态发给别人看。 */
	function applyUrlParams() {
		const params = new URLSearchParams(location.search);
		const presetId = params.get('preset');
		if (presetId) {
			const preset = Presets.PRESETS.find((item) => item.id === presetId || item.style === presetId);
			if (preset) applyPreset(preset, true);
		}
		if (params.get('seed')) {
			state.seed = params.get('seed');
			$('#seedInput').value = state.seed;
			rebuildPlayback();
		}
		if (params.get('age')) {
			const age = clamp(Number(params.get('age')) || 0, 0, state.def.duration);
			state.age = age;
			state.playing = params.get('paused') !== '1';
			rebuildPlayback();
			updateTransport();
		}
		if (params.get('ground') === '1') {
			state.useGround = true;
			$('#chkGround').checked = true;
			state.groundY = autoGroundY();
			if (state.playback) {
				state.playback.groundY = state.groundY;
				state.playback.resetSplash();
			}
		}
		if (params.get('grid') === '0') {
			state.showGrid = false;
			$('#chkGrid').checked = false;
		}
	}

	/** 贴图清单是生成产物：没有时静默退回程序化形状，不阻塞启动。 */
	function loadSpriteManifestFile() {
		const script = document.createElement('script');
		script.src = 'assets/particles/manifest.js';
		script.onload = () => {
			loadSpriteManifest();
			if (sectionNodes.size) refreshSections();
			updateOutput();
		};
		script.onerror = () => { /* 还没跑 sync-assets，直接用程序化形状 */ };
		document.head.append(script);
	}

	function start() {
		loadSpriteManifestFile();
		state.def = normalizeDef(Fx.parseDefinition(Presets.PRESETS[0].json));
		state.seed = '12345';
		buildStyleGrid();
		buildPresetList();
		rebuildInspector();
		setupEvents();
		resize();
		ensureBound();
		updateChrome();
		setRawText(JSON.stringify(currentJson(), null, 2));
		applyUrlParams();
		requestAnimationFrame((t) => { state.lastFrame = t; requestAnimationFrame(loop); });
		// 调试入口：控制台里可以直接改状态、取当前缓冲
		window.__studio = {
			state,
			debugRender(count) {
				const buffer = state.playback ? state.playback.sample(state.age, false) : null;
				if (!buffer || !state.bound) return null;
				const appearance = state.bound.appearance;
				const plan = buildSpritePlan(appearance, state.age);
				const cam = cameraBasis();
				const rows = [];
				for (let i = 0; i < Math.min(count || 3, buffer.count); i++) {
					const depth = (buffer.x[i] - cam.pos[0]) * cam.fwd[0]
						+ (buffer.y[i] - cam.pos[1]) * cam.fwd[1]
						+ (buffer.z[i] - cam.pos[2]) * cam.fwd[2];
					const sprite = textureFor(plan.fixed !== null ? plan.fixed
						: (plan.files ? particleVariantFile(plan.files, i) : null), plan.shape, buffer.argb[i]);
					const sc = depth > 0 ? view.focal / depth : 0;
					const raw = buffer.size[i] * sc;
					const minSize = sprite.width < 64 ? sprite.width : 2;
					rows.push({
						depth: Math.round(depth),
						worldSize: +buffer.size[i].toFixed(3),
						rawPx: Math.round(raw),
						drawnPx: Math.round(snapTextureSize(sprite, Math.max(minSize, raw))),
						sprite: sprite.width + 'x' + sprite.height,
						alpha: +buffer.alpha[i].toFixed(2),
						file: plan.fixed || (plan.files ? plan.files[0] : null),
					});
				}
				return { focal: Math.round(view.focal), canvas: [view.w, view.h], rows };
			},
			debugTexture() {
				const appearance = state.def.appearance;
				const plan = buildSpritePlan(appearance, state.age);
				const file = plan.fixed !== null ? plan.fixed : (plan.files ? plan.files[0] : null);
				const record = file ? imageCache.get(file) : null;
				const canvas = textureFor(file, plan.shape, 0xffffffff);
				return {
					sprite: appearance.sprite,
					mode: state.spriteMode,
					plan: { fixed: plan.fixed, files: plan.files, shape: plan.shape },
					file,
					ready: record ? record.ready : null,
					failed: record ? record.failed : null,
					natural: record && record.img ? [record.img.naturalWidth, record.img.naturalHeight] : null,
					tinted: [canvas.width, canvas.height],
					entries: SPRITE_ENTRIES.size,
					images: imageCache.size,
				};
			},
			applyPreset,
			selectStyle,
			frameEffect,
			sample: (age) => (state.playback ? state.playback.frame(age === undefined ? state.age : age) : null),
			cameraBasis,
			commit,
		};
	}

	start();
})();
