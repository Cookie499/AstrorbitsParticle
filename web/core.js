/*
 * core.js — AstrorbitsParticle 客户端采样引擎（浏览器 / Node 通用）
 *
 * 逐项移植自：
 *   src/main/kotlin/net/astrorbits/math/HashRandom.kt          splitmix64 哈希与 10 条随机流
 *   src/main/kotlin/net/astrorbits/style/StyleMath.kt          球面/锥形采样、解析积分、抖动
 *   src/main/kotlin/net/astrorbits/style/StyleSupport.kt       统一 emit
 *   src/main/kotlin/net/astrorbits/style/ParticleStyle.kt      Binder / FloatCurve / ColorGradient / 缓冲区
 *   src/main/kotlin/net/astrorbits/expr/Exprs.kt               exp4j 子集、LUT 烘焙、梯形积分
 *
 * 运行期语义与模组一致：绑定期把表达式折成常量或 LUT，采样期只做查表与四则运算。
 */
(function (root, factory) {
	if (typeof module === 'object' && module.exports) module.exports = factory();
	else root.APCore = factory();
})(typeof self !== 'undefined' ? self : this, function () {
	'use strict';

	// ───────────────────────── u64 运算（分高低 32 位，避免 BigInt 热路径） ─────────────────────────
	const K1L = 0x7f4a7c15, K1H = 0x9e3779b9; // 0x9E3779B97F4A7C15
	const K2L = 0x27d4eb4f, K2H = 0xc2b2ae3d; // 0xC2B2AE3D27D4EB4F
	const M1L = 0x1ce4e5b9, M1H = 0xbf58476d; // 0xBF58476D1CE4E5B9
	const M2L = 0x133111eb, M2H = 0x94d049bb; // 0x94D049BB133111EB

	let ML = 0, MH = 0; // mul64 结果寄存器，避免每帧分配对象

	/** 低 64 位乘积，写进 ML/MH。16 位肢乘法，中间量全部落在 double 精确范围内。 */
	function mul64(xLo, xHi, yLo, yHi) {
		const x0 = xLo & 0xffff, x1 = xLo >>> 16, x2 = xHi & 0xffff, x3 = xHi >>> 16;
		const y0 = yLo & 0xffff, y1 = yLo >>> 16, y2 = yHi & 0xffff, y3 = yHi >>> 16;
		let p0 = x0 * y0;
		let p1 = x0 * y1 + x1 * y0;
		let p2 = x0 * y2 + x1 * y1 + x2 * y0;
		let p3 = x0 * y3 + x1 * y2 + x2 * y1 + x3 * y0;
		let c = Math.floor(p0 / 65536);
		const l0 = p0 - c * 65536;
		let t = p1 + c; c = Math.floor(t / 65536);
		const l1 = t - c * 65536;
		t = p2 + c; c = Math.floor(t / 65536);
		const l2 = t - c * 65536;
		t = p3 + c; c = Math.floor(t / 65536);
		const l3 = t - c * 65536;
		ML = ((l1 << 16) | l0) >>> 0;
		MH = ((l3 << 16) | l2) >>> 0;
	}

	let HL = 0, HH = 0; // mix 结果

	function mix(L, H) {
		let l = ((L >>> 30) | (H << 2)) >>> 0, h = H >>> 30;
		L = (L ^ l) >>> 0; H = (H ^ h) >>> 0;
		mul64(L, H, M1L, M1H); L = ML; H = MH;
		l = ((L >>> 27) | (H << 5)) >>> 0; h = H >>> 27;
		L = (L ^ l) >>> 0; H = (H ^ h) >>> 0;
		mul64(L, H, M2L, M2H); L = ML; H = MH;
		l = ((L >>> 31) | (H << 1)) >>> 0; h = H >>> 31;
		HL = (L ^ l) >>> 0; HH = (H ^ h) >>> 0;
	}

	/** seed 可以写成安全整数、十进制字符串或 BigInt，按无符号 64 位解释。 */
	function seedParts(seed) {
		let v;
		if (typeof seed === 'bigint') v = seed;
		else if (typeof seed === 'number') v = BigInt(Math.trunc(seed));
		else v = BigInt(String(seed).trim());
		const masked = BigInt.asUintN(64, v);
		return { lo: Number(masked & 0xffffffffn), hi: Number((masked >> 32n) & 0xffffffffn) };
	}

	/** 随机流编号，与 HashRandom.Streams 一一对应；改动会改变回放结果。 */
	const Streams = Object.freeze({
		DIR: 0, SPEED: 1, RADIUS: 2, PHASE: 3, LIFE: 4,
		SIZE: 5, COLOR: 6, TIME: 7, JITTER: 8, SPRITE: 9,
	});

	/**
	 * 绑定到具体 seed 的随机源。哈希只取决于 (seed, stream, index, draw)，
	 * 因此按 (stream, draw) 预建整张表，采样期退化成一次数组读取，结果与逐次求哈希完全相同。
	 */
	class Rand {
		constructor(seed) {
			this.seed = seedParts(seed);
			this.seedText = String(seed);
			this.tables = new Map();
			this.count = 0;
		}

		/** 直接求值：`hash >>> 40` 即 hash 高 24 位，乘 2^-24 得到 [0,1)。 */
		hashLo(stream, index, draw) {
			const s = this.seed;
			mul64(stream, 0, K1L, K1H);
			const aL = ML, aH = MH;
			mul64(index, 0, K2L, K2H);
			mix((s.lo ^ aL ^ ML) >>> 0, (s.hi ^ aH ^ MH ^ draw) >>> 0);
			return HL;
		}

		unitRaw(stream, index, draw) {
			const s = this.seed;
			mul64(stream, 0, K1L, K1H);
			const aL = ML, aH = MH;
			mul64(index, 0, K2L, K2H);
			mix((s.lo ^ aL ^ ML) >>> 0, (s.hi ^ aH ^ MH ^ (draw >>> 0)) >>> 0);
			return (HH >>> 8) * 5.960464477539063e-8;
		}

		/**
		 * [0,1)。index 落在 0..count-1 时读预建表，否则现算；
		 * 哈希只取决于 (seed, stream, index, draw)，两条路径结果逐位相同。
		 */
		unit(stream, index, draw) {
			const d = draw === undefined ? 0 : draw >>> 0;
			if (index >= 0 && index < this.count) {
				const key = stream * 2097152 + (d & 0x1fffff);
				let t = this.tables.get(key);
				if (t === undefined) {
					t = new Float32Array(this.count);
					for (let i = 0; i < this.count; i++) t[i] = this.unitRaw(stream, i, d);
					this.tables.set(key, t);
				}
				return t[index];
			}
			return this.unitRaw(stream, index, d);
		}

		/** [-1,1)。 */
		signed(stream, index, draw) {
			return this.unit(stream, index, draw) * 2 - 1;
		}

		/** 声明要服务的粒子数；变大时清空旧表，按新长度重建。 */
		ensureCount(count) {
			if (count <= this.count) return;
			this.count = count;
			this.tables.clear();
		}
	}

	const HashRandom = { mix, unit: (r, s, i, d) => r.unit(s, i, d), signed: (r, s, i, d) => r.signed(s, i, d) };

	// ───────────────────────── 向量与采样 ─────────────────────────
	const TAU = Math.PI * 2;
	const DEG = Math.PI / 180;

	/**
	 * JVM 的 float 运算是每步舍入到 32 位；JS 全是 double。
	 * 关键路径上按 float 逐步舍入，采样结果才能与模组逐位一致。
	 */
	const f32 = Math.fround;

	function vlen(x, y, z) { return Math.sqrt(x * x + y * y + z * z); }

	function vnorm(x, y, z) {
		const l = vlen(x, y, z);
		if (l < 1e-9) return [0, 0, 0];
		return [x / l, y / l, z / l];
	}

	/** 球面均匀方向，等价于 StyleMath.sphereInto。draw 顺序固定：0 取 z、1 取 phi。 */
	function sphereInto(out, rand, index) {
		const z = rand.signed(Streams.DIR, index, 0);
		const phi = rand.unit(Streams.DIR, index, 1) * TAU;
		const r = Math.sqrt(Math.max(0, 1 - z * z));
		out[0] = Math.fround(r * Math.cos(phi));
		out[1] = Math.fround(r * Math.sin(phi));
		out[2] = z;
	}

	/** 轴向正交基，等价于 StyleMath.orthoBasis，写入 out[0..5]。 */
	function orthoBasis(axis, out) {
		const n = vlen(axis[0], axis[1], axis[2]) < 1e-9 ? [0, 1, 0] : vnorm(axis[0], axis[1], axis[2]);
		const helper = Math.abs(n[1]) < 0.9 ? [0, 1, 0] : [1, 0, 0];
		let e1 = [n[1] * helper[2] - n[2] * helper[1], n[2] * helper[0] - n[0] * helper[2], n[0] * helper[1] - n[1] * helper[0]];
		e1 = vnorm(e1[0], e1[1], e1[2]);
		const e2 = vnorm(
			n[1] * e1[2] - n[2] * e1[1],
			n[2] * e1[0] - n[0] * e1[2],
			n[0] * e1[1] - n[1] * e1[0],
		);
		out[0] = e1[0]; out[1] = e1[1]; out[2] = e1[2];
		out[3] = e2[0]; out[4] = e2[1]; out[5] = e2[2];
	}

	function coneInto(out, axis, halfAngleRad, rand, index) {
		if (halfAngleRad >= Math.PI - 0.001) { sphereInto(out, rand, index); return; }
		const cosLimit = Math.cos(halfAngleRad);
		const cosTheta = 1 + rand.unit(Streams.DIR, index, 0) * (cosLimit - 1);
		const theta = Math.acos(Math.min(1, Math.max(-1, cosTheta)));
		const phi = rand.unit(Streams.DIR, index, 1) * TAU;
		const basis = [0, 0, 0, 0, 0, 0];
		orthoBasis(axis, basis);
		const sinTheta = Math.sin(theta);
		const cx = Math.cos(phi) * sinTheta;
		const sx = Math.sin(phi) * sinTheta;
		out[0] = Math.fround(axis[0] * cosTheta + basis[0] * cx + basis[3] * sx);
		out[1] = Math.fround(axis[1] * cosTheta + basis[1] * cx + basis[4] * sx);
		out[2] = Math.fround(axis[2] * cosTheta + basis[2] * cx + basis[5] * sx);
	}

	/**
	 * 解析积分 dv/dt = g - k(v - w)，等价于 StyleMath.posInto。
	 * 阻尼为 0 时退化成抛物线；否则取闭式解，不做逐步积分。
	 */
	function posInto(out, ox, oy, oz, vx, vy, vz, gravity, drag, wind, t) {
		const f = Math.fround;
		ox = f(ox); oy = f(oy); oz = f(oz);
		vx = f(vx); vy = f(vy); vz = f(vz);
		if (t <= 0) { out[0] = ox; out[1] = oy; out[2] = oz; return; }
		if (drag <= 1e-5) {
			const half = f(f(0.5 * t) * t);
			const ax = f(f(ox + f(vx * t)) + f(f(gravity[0]) * half));
			const ay = f(f(oy + f(vy * t)) + f(f(gravity[1]) * half));
			const az = f(f(oz + f(vz * t)) + f(f(gravity[2]) * half));
			out[0] = ax; out[1] = ay; out[2] = az;
			return;
		}
		const k = drag;
		const decay = Math.exp(-k * t);
		const tx = gravity[0] / k + wind[0];
		const ty = gravity[1] / k + wind[1];
		const tz = gravity[2] / k + wind[2];
		const scale = f((1 - decay) / k);
		out[0] = f(ox + tx * t + (vx - tx) * scale);
		out[1] = f(oy + ty * t + (vy - ty) * scale);
		out[2] = f(oz + tz * t + (vz - tz) * scale);
	}

	function jitterInto(out, radius, rand, index) {
		if (radius <= 0) { out[0] = 0; out[1] = 0; out[2] = 0; return; }
		sphereInto(out, rand, index);
		const r = Math.fround(radius * rand.unit(Streams.JITTER, index, 2));
		out[0] = Math.fround(out[0] * r);
		out[1] = Math.fround(out[1] * r);
		out[2] = Math.fround(out[2] * r);
	}

	function fract(x) { return x - Math.floor(x); }

	function smoothstep(e0, e1, x) {
		const t = Math.min(1, Math.max(0, (x - e0) / (e1 - e0)));
		return t * t * (3 - 2 * t);
	}

	// ───────────────────────── 颜色 ─────────────────────────
	function lerpColor(from, to, t) {
		const a = ((from >>> 24) & 0xff) + (((to >>> 24) & 0xff) - ((from >>> 24) & 0xff)) * t;
		const r = ((from >>> 16) & 0xff) + (((to >>> 16) & 0xff) - ((from >>> 16) & 0xff)) * t;
		const g = ((from >>> 8) & 0xff) + (((to >>> 8) & 0xff) - ((from >>> 8) & 0xff)) * t;
		const b = (from & 0xff) + ((to & 0xff) - (from & 0xff)) * t;
		return ((Math.min(255, Math.max(0, Math.trunc(a))) << 24) |
			(Math.min(255, Math.max(0, Math.trunc(r))) << 16) |
			(Math.min(255, Math.max(0, Math.trunc(g))) << 8) |
			Math.min(255, Math.max(0, Math.trunc(b)))) >>> 0;
	}

	function scaleRgb(color, factor) {
		const r = Math.min(255, Math.max(0, Math.trunc(((color >>> 16) & 0xff) * factor)));
		const g = Math.min(255, Math.max(0, Math.trunc(((color >>> 8) & 0xff) * factor)));
		const b = Math.min(255, Math.max(0, Math.trunc((color & 0xff) * factor)));
		return ((color & 0xff000000) | (r << 16) | (g << 8) | b) >>> 0;
	}

	/** 与 parseColor 一致：#RRGGBB / #AARRGGBB / 整数 / [r,g,b] / [r,g,b,a]（0..1 浮点）。 */
	function parseColor(el, dflt) {
		if (el === undefined || el === null) return dflt;
		if (typeof el === 'number') return el >>> 0;
		if (typeof el === 'string') {
			const raw = el.replace(/^#/, '');
			if (!/^[0-9a-fA-F]+$/.test(raw)) return dflt;
			const value = parseInt(raw, 16);
			if (raw.length === 6) return (0xff000000 | value) >>> 0;
			if (raw.length === 8) return value >>> 0;
			return dflt;
		}
		if (Array.isArray(el)) {
			if (el.length < 3) return dflt;
			const r = Math.min(255, Math.max(0, Math.trunc(Number(el[0]) * 255)));
			const g = Math.min(255, Math.max(0, Math.trunc(Number(el[1]) * 255)));
			const b = Math.min(255, Math.max(0, Math.trunc(Number(el[2]) * 255)));
			const a = el.length > 3 ? Math.min(255, Math.max(0, Math.trunc(Number(el[3]) * 255))) : 255;
			return ((a << 24) | (r << 16) | (g << 8) | b) >>> 0;
		}
		return dflt;
	}

	function colorToHex(argb) { return '#' + (argb & 0xffffff).toString(16).padStart(6, '0'); }
	function argbHex(argb) { return (argb >>> 0).toString(16); }

	function withAlpha(argb, alpha) {
		const byte = Math.min(255, Math.max(0, Math.trunc(Math.min(1, Math.max(0, alpha)) * 255)));
		return ((argb & 0x00ffffff) | (byte << 24)) >>> 0;
	}

	// ───────────────────────── 曲线 ─────────────────────────
	const CURVE_DEFAULT = [0, 1, 1, 0];

	class FloatCurve {
		constructor(points) {
			this.points = points && points.length >= 4 ? points : CURVE_DEFAULT.slice();
		}

		at(u) {
			const p = this.points;
			const x = Math.min(1, Math.max(0, u));
			let i = 0;
			while (i + 3 < p.length && x > p[i + 2]) i += 2;
			const u0 = p[i], v0 = p[i + 1], u1 = p[i + 2], v1 = p[i + 3];
			if (u1 <= u0) return v1;
			const f = Math.fround;
			return f(v0 + f(f(f(v1 - v0) * f(x - u0)) / f(u1 - u0)));
		}
	}

	class ColorGradient {
		constructor(stops, colors) { this.stops = stops; this.colors = colors; }

		at(u) {
			const s = this.stops, c = this.colors;
			const x = Math.min(1, Math.max(0, u));
			let i = 0;
			while (i + 1 < s.length - 1 && x > s[i + 1]) i++;
			const u0 = s[i], u1 = s[i + 1];
			const t = u1 <= u0 ? 1 : Math.min(1, Math.max(0, (x - u0) / (u1 - u0)));
			return lerpColor(c[i], c[i + 1], t);
		}
	}

	// ───────────────────────── 表达式（exp4j 子集） ─────────────────────────
	const TIME_VARS = /(?<![A-Za-z0-9_])[tu](?![A-Za-z0-9_])/;

	const MATH_FUNCTIONS = {
		abs: [1, Math.abs], acos: [1, Math.acos], asin: [1, Math.asin], atan: [1, Math.atan],
		cbrt: [1, Math.cbrt], ceil: [1, Math.ceil], cos: [1, Math.cos], cosh: [1, Math.cosh],
		exp: [1, Math.exp], floor: [1, Math.floor], log: [1, Math.log], log10: [1, Math.log10],
		log2: [1, Math.log2], sin: [1, Math.sin], sinh: [1, Math.sinh], sqrt: [1, Math.sqrt],
		tan: [1, Math.tan], tanh: [1, Math.tanh],
		clamp: [3, (a, b, c) => Math.min(Math.max(a, b), c)],
		mix: [3, (a, b, k) => a + (b - a) * k],
		smoothstep: [3, (a, b, x) => { const t = Math.min(1, Math.max(0, (x - a) / (b - a))); return t * t * (3 - 2 * t); }],
		fract: [1, (x) => x - Math.floor(x)],
		sign: [1, Math.sign],
		step: [2, (a, x) => (x >= a ? 1 : 0)],
	};

	const CONSTANTS = { pi: Math.PI, e: Math.E };
	const VARS = { t: 0, d: 1, u: 2, n: 3 };

	function tokenize(src) {
		const tokens = [];
		let i = 0;
		while (i < src.length) {
			const ch = src[i];
			if (/\s/.test(ch)) { i++; continue; }
			if (/[0-9.]/.test(ch)) {
				let j = i;
				while (j < src.length && /[0-9.]/.test(src[j])) j++;
				if (src[j] === 'e' || src[j] === 'E') {
					let k = j + 1;
					if (src[k] === '+' || src[k] === '-') k++;
					if (/[0-9]/.test(src[k] || '')) {
						k++;
						while (k < src.length && /[0-9]/.test(src[k])) k++;
						j = k;
					}
				}
				const text = src.slice(i, j);
				const value = Number(text);
				if (!Number.isFinite(value)) throw new Error('bad number: ' + text);
				tokens.push({ t: 'num', v: value });
				i = j;
				continue;
			}
			if (/[A-Za-z_]/.test(ch)) {
				let j = i;
				while (j < src.length && /[A-Za-z0-9_]/.test(src[j])) j++;
				tokens.push({ t: 'id', v: src.slice(i, j) });
				i = j;
				continue;
			}
			if ('+-*/%^(),'.includes(ch)) { tokens.push({ t: ch }); i++; continue; }
			throw new Error("unexpected character '" + ch + "' at " + i);
		}
		return tokens;
	}

	/** 递归下降解析；`^` 右结合且优先级高于一元负号（-2^2 = -4），与 exp4j 一致。 */
	function parseExpr(src) {
		if (src.length > 256) throw new Error('expression too long: ' + src.length);
		const tokens = tokenize(src);
		let p = 0;
		const peek = () => tokens[p];
		const eat = (t) => { if (tokens[p] && tokens[p].t === t) { p++; return true; } return false; };

		function expression() {
			let node = term();
			for (; ;) {
				if (eat('+')) node = { op: '+', a: node, b: term() };
				else if (eat('-')) node = { op: '-', a: node, b: term() };
				else return node;
			}
		}
		function term() {
			let node = unary();
			for (; ;) {
				if (eat('*')) node = { op: '*', a: node, b: unary() };
				else if (eat('/')) node = { op: '/', a: node, b: unary() };
				else if (eat('%')) node = { op: '%', a: node, b: unary() };
				else return node;
			}
		}
		function unary() {
			if (eat('-')) return { op: 'neg', a: unary() };
			if (eat('+')) return unary();
			return power();
		}
		function power() {
			const base = primary();
			if (eat('^')) return { op: '^', a: base, b: unary() };
			return base;
		}
		function primary() {
			const tok = peek();
			if (!tok) throw new Error('unexpected end of expression');
			if (tok.t === 'num') { p++; return { op: 'num', v: tok.v }; }
			if (tok.t === 'id') {
				p++;
				if (eat('(')) {
					const args = [];
					if (!eat(')')) {
						args.push(expression());
						while (eat(',')) args.push(expression());
						if (!eat(')')) throw new Error('missing ) after ' + tok.v);
					}
					const spec = MATH_FUNCTIONS[tok.v];
					if (!spec) throw new Error('unknown function: ' + tok.v);
					if (spec[0] !== args.length) throw new Error(tok.v + ' expects ' + spec[0] + ' argument(s)');
					return { op: 'call', fn: tok.v, args };
				}
				if (tok.v in VARS) return { op: 'var', i: VARS[tok.v] };
				if (tok.v in CONSTANTS) return { op: 'num', v: CONSTANTS[tok.v] };
				throw new Error('unknown identifier: ' + tok.v);
			}
			if (tok.t === '(') { p++; const node = expression(); if (!eat(')')) throw new Error('missing )'); return node; }
			throw new Error("unexpected token '" + tok.t + "'");
		}

		const ast = expression();
		if (p < tokens.length) throw new Error('unexpected trailing token');
		return ast;
	}

	function evalAst(node, vars) {
		switch (node.op) {
			case 'num': return node.v;
			case 'var': return vars[node.i];
			case 'neg': return -evalAst(node.a, vars);
			case '+': return evalAst(node.a, vars) + evalAst(node.b, vars);
			case '-': return evalAst(node.a, vars) - evalAst(node.b, vars);
			case '*': return evalAst(node.a, vars) * evalAst(node.b, vars);
			case '/': return evalAst(node.a, vars) / evalAst(node.b, vars);
			case '%': return evalAst(node.a, vars) % evalAst(node.b, vars);
			case '^': return Math.pow(evalAst(node.a, vars), evalAst(node.b, vars));
			case 'call': {
				const spec = MATH_FUNCTIONS[node.fn];
				const args = node.args.map((a) => evalAst(a, vars));
				return spec[1].apply(null, args);
			}
			default: throw new Error('bad node ' + node.op);
		}
	}

	const LUT_SAMPLES = (duration) => Math.min(256, Math.max(16, Math.trunc(duration * 5)));
	const exprCache = new Map();

	function compileExpr(src) {
		if (typeof src !== 'string') throw new Error('expression is not a string');
		const cached = exprCache.get(src);
		if (cached) { if (cached.error) throw new Error(cached.error); return cached.compiled; }
		try {
			const ast = parseExpr(src);
			const compiled = {
				source: src,
				ast,
				timeDependent: TIME_VARS.test(src),
				eval(t, d) {
					const n = 0; // 变量 n 单独由调用方注入
					return evalAst(ast, [t, d, d > 0 ? t / d : 0, n]);
				},
			};
			exprCache.set(src, { compiled });
			return compiled;
		} catch (err) {
			exprCache.set(src, { error: err.message });
			throw err;
		}
	}

	/** 变量容器：LUT 烘焙时 n 需要真实粒子数，因此单独保留一份带 n 的求值入口。 */
	function evalWith(compiled, t, d, count) {
		return evalAst(compiled.ast, [t, d, d > 0 ? t / d : 0, count]);
	}

	/** 绑定期求值，等价于 CompiledExpr.bake。 */
	function bakeExpr(src, duration, count) {
		const compiled = compileExpr(src);
		if (!compiled.timeDependent) return { kind: 'const', value: Math.fround(evalWith(compiled, 0, duration, count)) };
		const samples = LUT_SAMPLES(duration);
		const values = new Float32Array(samples);
		for (let i = 0; i < samples; i++) {
			values[i] = evalWith(compiled, duration * i / (samples - 1), duration, count);
		}
		return { kind: 'lut', values, duration };
	}

	/** 预积分成累积表，等价于 CompiledExpr.bakeIntegral。 */
	function bakeIntegral(src, duration, count) {
		const compiled = compileExpr(src);
		if (!compiled.timeDependent) {
			const v = evalWith(compiled, 0, duration, count);
			return { kind: 'lut', values: new Float32Array([0, v * duration]), duration };
		}
		const samples = LUT_SAMPLES(duration);
		const step = duration / (samples - 1);
		const values = new Float32Array(samples);
		let acc = 0;
		let prev = evalWith(compiled, 0, duration, count);
		for (let i = 0; i < samples; i++) {
			const t = duration * i / (samples - 1);
			const v = evalWith(compiled, t, duration, count);
			if (i > 0) acc += (prev + v) * 0.5 * step;
			values[i] = acc;
			prev = v;
		}
		return { kind: 'lut', values, duration };
	}

	// ───────────────────────── 绑定值 ─────────────────────────
	const ConstNumber = (value) => ({ kind: 'const', value, at() { return this.value; } });
	const LutNumber = (values, duration) => ({
		kind: 'lut', values, duration,
		at(t) {
			const v = this.values;
			if (v.length === 1) return v[0];
			const x = Math.min(1, Math.max(0, this.duration > 0 ? t / this.duration : 0)) * (v.length - 1);
			let i = Math.trunc(x);
			if (i > v.length - 2) i = v.length - 2;
			const frac = x - i;
			return Math.fround(v[i] + Math.fround(Math.fround(v[i + 1] - v[i]) * frac));
		},
	});

	function boundNumberFromBaked(baked) {
		return baked.kind === 'const' ? ConstNumber(baked.value) : LutNumber(baked.values, baked.duration);
	}

	// ───────────────────────── 粒子缓冲 ─────────────────────────
	class ParticleBuffer {
		constructor() {
			this.count = 0;
			this.x = new Float32Array(0);
			this.y = new Float32Array(0);
			this.z = new Float32Array(0);
			this.size = new Float32Array(0);
			this.alpha = new Float32Array(0);
			this.argb = new Uint32Array(0);
			this.ids = new Int32Array(0);
		}

		reserve(n) {
			if (this.x.length >= n) return;
			const next = Math.max(n, 16);
			const grow = (arr, Ctor) => { const out = new Ctor(next); out.set(arr); return out; };
			this.x = grow(this.x, Float32Array);
			this.y = grow(this.y, Float32Array);
			this.z = grow(this.z, Float32Array);
			this.size = grow(this.size, Float32Array);
			this.alpha = grow(this.alpha, Float32Array);
			this.argb = grow(this.argb, Uint32Array);
			this.ids = grow(this.ids, Int32Array);
		}

		reset(n) { this.reserve(n); this.count = 0; }

		push(px, py, pz, pSize, pAlpha, color, id) {
			const i = this.count;
			if (i >= this.x.length) this.reserve(Math.max(16, this.x.length * 2));
			this.x[i] = px; this.y[i] = py; this.z[i] = pz;
			this.size[i] = pSize; this.alpha[i] = pAlpha; this.argb[i] = color;
			this.ids[i] = id === undefined ? -1 : id;
			this.count = i + 1;
		}

		append(other) {
			if (other.count === 0) return;
			const start = this.count;
			const total = start + other.count;
			this.reserve(total);
			this.x.set(other.x.subarray(0, other.count), start);
			this.y.set(other.y.subarray(0, other.count), start);
			this.z.set(other.z.subarray(0, other.count), start);
			this.size.set(other.size.subarray(0, other.count), start);
			this.alpha.set(other.alpha.subarray(0, other.count), start);
			this.argb.set(other.argb.subarray(0, other.count), start);
			this.ids.set(other.ids.subarray(0, other.count), start);
			this.count = total;
		}

		retain(keep) {
			let write = 0;
			for (let i = 0; i < this.count; i++) {
				if (!keep[i]) continue;
				if (write !== i) {
					this.x[write] = this.x[i]; this.y[write] = this.y[i]; this.z[write] = this.z[i];
					this.size[write] = this.size[i]; this.alpha[write] = this.alpha[i];
					this.argb[write] = this.argb[i]; this.ids[write] = this.ids[i];
				}
				write++;
			}
			this.count = write;
		}
	}

	// ───────────────────────── emit ─────────────────────────
	const _emitRand = { rand: null };

	/**
	 * 统一的外观求值，等价于 StyleSupport.emit。
	 * 尺寸/透明度过小直接丢弃；颜色按 override > 渐变 > 基色，再叠亮度抖动。
	 */
	function emit(effect, rand, index, age, u, px, py, pz, out, sizeScale, alphaScale, colorOverride, emissionIndex) {
		const appearance = effect.appearance;
		sizeScale = sizeScale === undefined ? 1 : sizeScale;
		alphaScale = alphaScale === undefined ? 1 : alphaScale;
		colorOverride = colorOverride === undefined ? 0 : colorOverride;
		const f = Math.fround;
		const size = f(f(f(f(appearance.size.at(age)) *
			f(1 + f(appearance.sizeJitter * rand.signed(Streams.SIZE, index, 0)))) *
			f(appearance.sizeByAge.at(u))) * f(sizeScale));
		if (size <= 0) return;
		const alpha = Math.fround(f(appearance.alphaByAge.at(u)) * f(alphaScale));
		if (alpha <= 0.004) return;
		let color;
		if (colorOverride !== 0) color = colorOverride;
		else if (appearance.gradient) color = appearance.gradient.at(u);
		else color = appearance.color;
		if (appearance.colorJitter > 0) color = scaleRgb(color, 1 + appearance.colorJitter * rand.signed(Streams.COLOR, index, 0));
		out.push(
			px, py, pz, size, alpha,
			withAlpha(color, alpha),
			emissionIndex === undefined ? index : emissionIndex,
		);
	}

	// ───────────────────────── 发射时序 ─────────────────────────
	const EmissionMath = {
		birth(emission, rand, index, count) {
			let base;
			if (emission.mode === 'stream') {
				base = count <= 1
					? emission.windowStart
					: emission.windowStart + (emission.windowEnd - emission.windowStart) * index / (count - 1);
			} else if (emission.mode === 'random') {
				const span = emission.windowEnd - emission.windowStart;
				base = emission.windowStart + span * rand.unit(Streams.TIME, index, 0);
			} else {
				base = 0;
			}
			return base + emission.jitter * rand.signed(Streams.TIME, index, 1);
		},
		lifetime(emission, rand, index) {
			return Math.max(0.05, emission.lifetime * (1 + emission.lifetimeJitter * rand.signed(Streams.LIFE, index, 0)));
		},
	};

	return {
		Rand, Streams, HashRandom, seedParts,
		vlen, vnorm, sphereInto, coneInto, orthoBasis, posInto, jitterInto, fract, smoothstep,
		lerpColor, scaleRgb, parseColor, colorToHex, argbHex, withAlpha,
		FloatCurve, ColorGradient, CURVE_DEFAULT,
		compileExpr, bakeExpr, bakeIntegral, boundNumberFromBaked, ConstNumber, LutNumber,
		ParticleBuffer, emit, EmissionMath, TAU, DEG,
	};
});
