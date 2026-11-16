/*
 * effect.js — 定义解析、绑定与客户端播放状态机
 *
 * 对应源码：
 *   src/main/kotlin/net/astrorbits/model/Model.kt              EffectDefinition.parse 的默认值与夹取
 *   src/main/kotlin/net/astrorbits/style/ParticleStyle.kt       EffectBinder / BoundEffect / 包围球
 *   src/client/kotlin/net/astrorbits/client/ClientEffects.kt   周期回溯、轨迹、溅射、停止
 */
(function (root, factory) {
	if (typeof module === 'object' && module.exports) module.exports = factory(require('./core.js'), require('./styles.js'));
	else root.APEffect = factory(root.APCore, root.APStyles);
})(typeof self !== 'undefined' ? self : this, function (C, S) {
	'use strict';

	const MOD_CONFIG = {
		maxParticlesPerEffect: 8192,
		maxVisibleParticles: 20000,
		maxVisibleEffects: 64,
		frameBudgetMs: 4,
	};

	const MAX_LOOP_LOOKBACK = 8;
	const MAX_TRAIL_PARTICLES = 16384;
	const MAX_SPLASH_PARTICLES = 512;
	const SPLASH_GRAVITY = -6;
	const TRAIL_SPREAD_DRAW = 1000;
	const TRAIL_SIZE_SEGMENTS = 3; // 渲染期沿速度方向额外画的拉伸段数

	// ───────────────────────── 小工具：与 Gson 读取语义对齐 ─────────────────────────
	const isObj = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);
	const objOr = (v) => (isObj(v) ? v : null);
	const str = (o, k, dflt) => (typeof o[k] === 'string' ? o[k] : dflt);
	const flt = (o, k, dflt) => (typeof o[k] === 'number' && Number.isFinite(o[k]) ? o[k] : dflt);
	const integer = (o, k, dflt) => (typeof o[k] === 'number' && Number.isFinite(o[k]) ? Math.trunc(o[k]) : dflt);
	const boolv = (o, k, dflt) => (typeof o[k] === 'boolean' ? o[k] : dflt);
	const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));

	function enumOf(raw, values, dflt) {
		const key = String(raw).replace(/-/g, '_').toLowerCase();
		for (const v of values) if (v.toLowerCase() === key) return v;
		return dflt;
	}

	function vec3(o, k, dflt) {
		const v = o[k];
		if (v === undefined || v === null) return dflt.slice();
		if (typeof v === 'number') return [0, v, 0];
		if (!Array.isArray(v) || v.length < 3) return dflt.slice();
		return [Number(v[0]), Number(v[1]), Number(v[2])];
	}

	// ───────────────────────── 定义解析 ─────────────────────────
	function parseDefinition(input) {
		const obj = typeof input === 'string' ? JSON.parse(input) : input;
		if (!isObj(obj)) throw new Error('definition is not a JSON object');

		const duration = clamp(flt(obj, 'duration', 4), 0.05, 3600);
		const emissionRaw = objOr(obj.emission) || {};
		const window = Array.isArray(emissionRaw.window) ? emissionRaw.window : null;

		const emission = {
			mode: enumOf(str(emissionRaw, 'mode', 'burst'), ['burst', 'stream', 'random'], 'burst'),
			count: clamp(integer(emissionRaw, 'count', 200), 1, MOD_CONFIG.maxParticlesPerEffect),
			windowStart: window && window.length > 0 ? Number(window[0]) : 0,
			windowEnd: window && window.length > 1 ? Number(window[1]) : 0,
			jitter: flt(emissionRaw, 'jitter', 0),
			lifetime: flt(emissionRaw, 'lifetime', duration),
			lifetimeJitter: flt(emissionRaw, 'lifetimeJitter', 0.1),
		};

		const physicsRaw = objOr(obj.physics) || {};
		const physics = {
			gravity: vec3(physicsRaw, 'gravity', [0, -9.8, 0]),
			drag: flt(physicsRaw, 'drag', 0),
			wind: vec3(physicsRaw, 'wind', [0, 0, 0]),
		};

		const a = objOr(obj.appearance) || {};
		const appearance = {
			sprite: str(a, 'sprite', 'minecraft:generic_0'),
			spriteVariation: enumOf(str(a, 'spriteVariation', 'particle'), ['particle', 'effect', 'cycle'], 'particle'),
			spriteCycleTicks: clamp(integer(a, 'spriteCycleTicks', 2), 1, 200),
			size: a.size === undefined ? null : a.size,
			sizeJitter: flt(a, 'sizeJitter', 0.15),
			sizeByAge: a.sizeByAge === undefined ? null : a.sizeByAge,
			color: a.color === undefined ? null : a.color,
			colorJitter: flt(a, 'colorJitter', 0),
			colorByAge: a.colorByAge === undefined ? null : a.colorByAge,
			alphaByAge: a.alphaByAge === undefined ? null : a.alphaByAge,
			blend: enumOf(str(a, 'blend', 'additive'), ['additive', 'alpha'], 'additive'),
			fullbright: boolv(a, 'fullbright', true),
			stretch: a.stretch === undefined ? null : a.stretch,
			spin: a.spin === undefined ? null : a.spin,
		};

		let trail = null;
		const t = objOr(obj.trail);
		if (t && boolv(t, 'enabled', true)) {
			trail = {
				mode: enumOf(str(t, 'mode', 'time'), ['time', 'distance'], 'time'),
				interval: clamp(flt(t, 'interval', 0.1), 0.01, 10),
				distance: clamp(flt(t, 'distance', 0.5), 0.01, 16),
				lag: clamp(flt(t, 'lag', 1), 0.01, 16),
				cap: clamp(integer(t, 'cap', 8), 1, 32),
				spread: clamp(flt(t, 'spread', 0), 0, 8),
				drift: clamp(flt(t, 'drift', 0), 0, 16),
				sizeScale: clamp(flt(t, 'sizeScale', 0.8), 0, 1),
				alphaScale: clamp(flt(t, 'alphaScale', 0.7), 0, 1),
				color: t.color === undefined ? null : t.color,
			};
		}

		let splash = null;
		const sp = objOr(obj.splash);
		if (sp && boolv(sp, 'enabled', true)) {
			splash = {
				color: sp.color === undefined ? null : sp.color,
				count: clamp(integer(sp, 'count', 4), 1, 16),
				size: clamp(flt(sp, 'size', 0.12), 0.01, 1),
				speed: clamp(flt(sp, 'speed', 1.2), 0, 8),
				life: clamp(flt(sp, 'life', 0.45), 0.05, 3),
			};
		}

		const v = objOr(obj.visibility) || {};
		const visibility = {
			viewDistance: clamp(flt(v, 'viewDistance', 96), 8, 512),
			requireScene: boolv(v, 'requireScene', true),
			boundsPadding: clamp(flt(v, 'boundsPadding', 1), 0, 64),
		};

		return {
			style: str(obj, 'style', 'astrorbitsparticle:radial_burst'),
			duration,
			loop: boolv(obj, 'loop', false),
			orientation: enumOf(str(obj, 'orientation', 'world'), ['world', 'view'], 'world'),
			reverse: boolv(obj, 'reverse', false),
			timeScale: clamp(flt(obj, 'timeScale', 1), -8, 8),
			subEffect: typeof obj.subEffect === 'string' && obj.subEffect ? obj.subEffect : null,
			subEffectTrigger: enumOf(str(obj, 'subEffectTrigger', 'end'), ['end', 'impact', 'particle_end'], 'end'),
			emission,
			physics,
			appearance,
			trail,
			splash,
			visibility,
			params: objOr(obj.params) || {},
		};
	}

	/** 把定义序列化回 effects/*.json 的形状；省略等于默认值的字段，保留表达式原文。 */
	function definitionToJson(def) {
		const out = {
			style: def.style,
			duration: def.duration,
		};
		if (def.loop) out.loop = true;
		if (def.orientation !== 'world') out.orientation = def.orientation;
		if (def.reverse) out.reverse = true;
		if (def.timeScale !== 1) out.timeScale = def.timeScale;
		if (def.subEffect) {
			out.subEffect = def.subEffect;
			if (def.subEffectTrigger !== 'end') out.subEffectTrigger = def.subEffectTrigger;
		}

		const emission = { mode: def.emission.mode, count: def.emission.count };
		if (def.emission.windowStart !== 0 || def.emission.windowEnd !== 0) {
			emission.window = [def.emission.windowStart, def.emission.windowEnd];
		}
		if (def.emission.jitter !== 0) emission.jitter = def.emission.jitter;
		emission.lifetime = def.emission.lifetime;
		if (def.emission.lifetimeJitter !== 0.1) emission.lifetimeJitter = def.emission.lifetimeJitter;
		out.emission = emission;

		out.physics = {
			gravity: def.physics.gravity,
			drag: def.physics.drag,
			wind: def.physics.wind,
		};

		const a = def.appearance;
		const appearance = { sprite: a.sprite };
		if (a.spriteVariation !== 'particle') appearance.spriteVariation = a.spriteVariation;
		if (a.spriteCycleTicks !== 2) appearance.spriteCycleTicks = a.spriteCycleTicks;
		if (a.size !== null) appearance.size = a.size;
		appearance.sizeJitter = a.sizeJitter;
		if (a.sizeByAge !== null) appearance.sizeByAge = a.sizeByAge;
		appearance.color = a.color === null ? '#FFFFFF' : a.color;
		if (a.colorJitter !== 0) appearance.colorJitter = a.colorJitter;
		if (a.colorByAge !== null) appearance.colorByAge = a.colorByAge;
		if (a.alphaByAge !== null) appearance.alphaByAge = a.alphaByAge;
		appearance.blend = a.blend;
		appearance.fullbright = a.fullbright;
		if (a.stretch !== null) appearance.stretch = a.stretch;
		if (a.spin !== null) appearance.spin = a.spin;
		out.appearance = appearance;

		if (def.trail) {
			out.trail = { mode: def.trail.mode, interval: def.trail.interval, distance: def.trail.distance,
				lag: def.trail.lag, cap: def.trail.cap, spread: def.trail.spread, drift: def.trail.drift,
				sizeScale: def.trail.sizeScale, alphaScale: def.trail.alphaScale };
			if (def.trail.color !== null) out.trail.color = def.trail.color;
		}
		if (def.splash) {
			out.splash = { enabled: true, count: def.splash.count, size: def.splash.size,
				speed: def.splash.speed, life: def.splash.life };
			if (def.splash.color !== null) out.splash.color = def.splash.color;
		}
		if (def.visibility.viewDistance !== 96 || !def.visibility.requireScene || def.visibility.boundsPadding !== 1) {
			out.visibility = Object.assign({}, def.visibility);
		}
		out.params = Object.assign({}, def.params);
		return out;
	}

	/**
	 * 内联命令用的最小定义：省略与解析默认值相同的字段，语义与原定义等价。
	 * 默认值取自 Model.parse：缺省字段由解析器补齐，因此可以安全省略。
	 */
	const PARSE_DEFAULTS = parseDefinition('{"style":"astrorbitsparticle:radial_burst"}');

	const sameValue = (a, b) => JSON.stringify(a) === JSON.stringify(b);
	const sameNumber = (a, b) => typeof a === 'number' && Math.abs(a - b) < 1e-9;

	function minimalDefinition(def) {
		const d = PARSE_DEFAULTS;
		const out = { style: def.style };
		if (!sameNumber(def.duration, d.duration)) out.duration = def.duration;
		if (def.loop) out.loop = true;
		if (def.orientation !== d.orientation) out.orientation = def.orientation;
		if (def.reverse) out.reverse = true;
		if (!sameNumber(def.timeScale, d.timeScale)) out.timeScale = def.timeScale;
		if (def.subEffect) {
			out.subEffect = def.subEffect;
			if (def.subEffectTrigger !== d.subEffectTrigger) out.subEffectTrigger = def.subEffectTrigger;
		}

		const e = def.emission;
		const emission = {};
		if (e.mode !== d.emission.mode) emission.mode = e.mode;
		if (e.count !== d.emission.count) emission.count = e.count;
		if (!sameNumber(e.windowStart, 0) || !sameNumber(e.windowEnd, 0)) emission.window = [e.windowStart, e.windowEnd];
		if (!sameNumber(e.jitter, 0)) emission.jitter = e.jitter;
		// lifetime 的默认值是 duration，不是常量
		if (!sameNumber(e.lifetime, def.duration)) emission.lifetime = e.lifetime;
		if (!sameNumber(e.lifetimeJitter, d.emission.lifetimeJitter)) emission.lifetimeJitter = e.lifetimeJitter;
		if (Object.keys(emission).length) out.emission = emission;

		const p = def.physics;
		const physics = {};
		if (!sameValue(p.gravity, d.physics.gravity)) physics.gravity = p.gravity;
		if (!sameNumber(p.drag, 0)) physics.drag = p.drag;
		if (!sameValue(p.wind, [0, 0, 0])) physics.wind = p.wind;
		if (Object.keys(physics).length) out.physics = physics;

		const a = def.appearance;
		const appearance = {};
		if (a.sprite !== d.appearance.sprite) appearance.sprite = a.sprite;
		if (a.spriteVariation !== d.appearance.spriteVariation) appearance.spriteVariation = a.spriteVariation;
		if (a.spriteCycleTicks !== d.appearance.spriteCycleTicks) appearance.spriteCycleTicks = a.spriteCycleTicks;
		// size / sizeByAge / alphaByAge / stretch / spin 在解析期是 null，绑定期的默认值如下
		if (a.size !== null && !sameNumber(a.size, 0.2)) appearance.size = a.size;
		if (!sameNumber(a.sizeJitter, d.appearance.sizeJitter)) appearance.sizeJitter = a.sizeJitter;
		if (a.sizeByAge !== null && !sameValue(a.sizeByAge, [[0, 1], [1, 0]])) appearance.sizeByAge = a.sizeByAge;
		if (a.color !== null && !sameValue(String(a.color).toUpperCase(), '#FFFFFF')) appearance.color = a.color;
		if (!sameNumber(a.colorJitter, 0)) appearance.colorJitter = a.colorJitter;
		if (a.colorByAge !== null) appearance.colorByAge = a.colorByAge;
		if (a.alphaByAge !== null && !sameValue(a.alphaByAge, [[0, 1], [0.7, 1], [1, 0]])) appearance.alphaByAge = a.alphaByAge;
		if (a.blend !== d.appearance.blend) appearance.blend = a.blend;
		if (!a.fullbright) appearance.fullbright = false;
		if (a.stretch !== null && !sameNumber(a.stretch, 0)) appearance.stretch = a.stretch;
		if (a.spin !== null && !sameNumber(a.spin, 0)) appearance.spin = a.spin;
		if (Object.keys(appearance).length) out.appearance = appearance;

		if (def.trail) {
			const t = def.trail;
			const trail = { enabled: true };
			if (t.mode !== 'time') trail.mode = t.mode;
			if (!sameNumber(t.interval, 0.1)) trail.interval = t.interval;
			if (!sameNumber(t.distance, 0.5)) trail.distance = t.distance;
			if (!sameNumber(t.lag, 1)) trail.lag = t.lag;
			if (t.cap !== 8) trail.cap = t.cap;
			if (!sameNumber(t.spread, 0)) trail.spread = t.spread;
			if (!sameNumber(t.drift, 0)) trail.drift = t.drift;
			if (!sameNumber(t.sizeScale, 0.8)) trail.sizeScale = t.sizeScale;
			if (!sameNumber(t.alphaScale, 0.7)) trail.alphaScale = t.alphaScale;
			if (t.color !== null) trail.color = t.color;
			out.trail = trail;
		}

		if (def.splash) {
			const sp = def.splash;
			const splash = { enabled: true };
			if (sp.color !== null) splash.color = sp.color;
			if (sp.count !== 4) splash.count = sp.count;
			if (!sameNumber(sp.size, 0.12)) splash.size = sp.size;
			if (!sameNumber(sp.speed, 1.2)) splash.speed = sp.speed;
			if (!sameNumber(sp.life, 0.45)) splash.life = sp.life;
			out.splash = splash;
		}

		const v = def.visibility;
		const visibility = {};
		if (!sameNumber(v.viewDistance, 96)) visibility.viewDistance = v.viewDistance;
		if (!v.requireScene) visibility.requireScene = false;
		if (!sameNumber(v.boundsPadding, 1)) visibility.boundsPadding = v.boundsPadding;
		if (Object.keys(visibility).length) out.visibility = visibility;

		if (Object.keys(def.params).length) out.params = def.params;
		return out;
	}

	// ───────────────────────── 绑定 ─────────────────────────
	const DEFAULT_SIZE_CURVE = [0, 1, 1, 0];
	const DEFAULT_ALPHA_CURVE = [0, 1, 0.7, 1, 1, 0];

	function bindAppearance(appearance, binder) {
		return {
			sprite: appearance.sprite,
			spriteVariation: appearance.spriteVariation,
			spriteCycleTicks: appearance.spriteCycleTicks,
			size: binder.bindNumber(appearance.size, 0.2, 'appearance.size'),
			sizeJitter: appearance.sizeJitter,
			sizeByAge: S.parseCurve(appearance.sizeByAge, DEFAULT_SIZE_CURVE),
			color: appearance.color === null ? 0xffffffff : C.parseColor(appearance.color, 0xffffffff),
			colorJitter: appearance.colorJitter,
			gradient: S.parseGradient(appearance.colorByAge),
			alphaByAge: S.parseCurve(appearance.alphaByAge, DEFAULT_ALPHA_CURVE),
			blend: appearance.blend,
			fullbright: appearance.fullbright,
			stretch: binder.bindNumber(appearance.stretch, 0, 'appearance.stretch'),
			spin: binder.bindNumber(appearance.spin, 0, 'appearance.spin'),
		};
	}

	/**
	 * 绑定一个定义。返回 { bound, problems }；problems 非空时模组会拒绝该定义，
	 * 编辑器仍然渲染当前能算出的部分，方便边改边看。
	 */
	function bind(definition) {
		const style = S.styleById(definition.style);
		if (!style) {
			return { bound: null, problems: ['unknown style: ' + definition.style] };
		}
		const binder = new S.Binder(definition.params, definition.duration, definition.emission.count, style.schema);
		const styleParams = style.bind(binder);
		const appearance = bindAppearance(definition.appearance, binder);
		const bound = {
			definition,
			style,
			count: definition.emission.count,
			duration: definition.duration,
			emission: definition.emission,
			appearance,
			physics: definition.physics,
			styleParams,
			trail: definition.trail,
			trailColor: definition.trail && definition.trail.color !== null ? C.parseColor(definition.trail.color, 0) : 0,
			splash: definition.splash ? {
				color: definition.splash.color === null ? 0xffffc84d : C.parseColor(definition.splash.color, 0xffffc84d),
				count: definition.splash.count,
				size: definition.splash.size,
				speed: definition.splash.speed,
				life: definition.splash.life,
			} : null,
		};
		bound.radius = undefined;
		Object.defineProperty(bound, 'radius', {
			get() {
				if (this._radius === undefined) this._radius = boundsRadius(this);
				return this._radius;
			},
			configurable: true,
		});
		return { bound, problems: binder.problems.slice() };
	}

	/** 采样各年龄的粒子位置，取最大距离外扩 1 格（BoundEffect.boundsRadius）。 */
	function boundsRadius(bound) {
		const buffer = new C.ParticleBuffer();
		const rand = new C.Rand(0);
		let maxSq = 0;
		const samples = 6;
		const stride = Math.max(1, Math.floor(bound.count / 2048));
		const origin = [0, 0, 0];
		for (let step = 0; step <= samples; step++) {
			const age = bound.duration * step / samples;
			buffer.reset(bound.count);
			bound.style.sample(bound, origin, rand, age, buffer);
			for (let i = 0; i < buffer.count; i += stride) {
				const d = buffer.x[i] * buffer.x[i] + buffer.y[i] * buffer.y[i] + buffer.z[i] * buffer.z[i];
				if (d > maxSq) maxSq = d;
			}
		}
		return clamp(Math.sqrt(maxSq) + 1, 1, 256);
	}

	function particleCapacity(bound) {
		const style = bound.style;
		return style.particleCapacity ? style.particleCapacity(bound) : bound.count;
	}

	function birthAge(bound, rand, index) {
		const style = bound.style;
		if (style.birthAge) return style.birthAge(bound, rand, index);
		return C.EmissionMath.birth(bound.emission, rand, index, bound.count);
	}

	// ───────────────────────── 客户端播放实例 ─────────────────────────
	/**
	 * 与 ClientEffect 等价。时间由外部给定（秒），不依赖 gameTime：
	 * 播放、拖拽时间轴、逐帧推进都只是传入不同的 age。
	 */
	class Playback {
		constructor(bound, seed, origin, opts) {
			opts = opts || {};
			this.bound = bound;
			this.origin = origin || [0, 0, 0];
			this.scale = opts.scale === undefined ? 1 : opts.scale;
			this.rand = new C.Rand(seed);
			this.rand.ensureCount(bound.count);
			this.seedText = String(seed);
			this.viewYaw = opts.viewYaw || 0;
			this.viewPitch = opts.viewPitch || 0;
			this.groundY = opts.groundY === undefined ? null : opts.groundY;

			this.playScale = bound.definition.timeScale * (bound.definition.reverse ? -1 : 1);
			const emission = bound.emission;
			const lifespan = emission.lifetime * (1 + Math.abs(emission.lifetimeJitter));
			this.loopLookback = clamp(Math.ceil(Math.max(lifespan, 0) / bound.duration), 1, MAX_LOOP_LOOKBACK);
			this.cyclesForward = bound.definition.loop && bound.style.cyclicEmission !== false && this.playScale >= 0;
			this.sourcePerCycle = particleCapacity(bound);
			this.sourceMaxParticles = this.sourcePerCycle * (this.cyclesForward ? this.loopLookback + 1 : 1);
			this.trail = bound.trail;
			this.trailCap = bound.trail ? bound.trail.cap : 0;
			this.trailMaxParticles = Math.min(this.sourceMaxParticles * this.trailCap, MAX_TRAIL_PARTICLES);
			this.maxParticles = this.sourceMaxParticles + this.trailMaxParticles +
				(bound.splash ? MAX_SPLASH_PARTICLES : 0);

			this.trailSizeMul = new Float32Array(this.trailCap + 1);
			this.trailAlphaMul = new Float32Array(this.trailCap + 1);
			let size = 1, alpha = 1;
			for (let segment = 1; segment <= this.trailCap; segment++) {
				size *= bound.trail ? bound.trail.sizeScale : 1;
				alpha *= bound.trail ? bound.trail.alphaScale : 1;
				this.trailSizeMul[segment] = size;
				this.trailAlphaMul[segment] = alpha;
			}

			this.buffer = new C.ParticleBuffer();
			this.cycle = new C.ParticleBuffer();
			this.trailScratch = new C.ParticleBuffer();
			this.trailCycle = new C.ParticleBuffer();
			this.splashSample = new C.ParticleBuffer();
			this.splashCycle = new C.ParticleBuffer();
			this.keepFlags = new Uint8Array(0);
			this.birthKeep = new Uint8Array(0);
			this.trailOffset = [0, 0, 0];

			// 轨迹 distance 模式的状态
			this.trailSourceIndex = new Map();
			this.trailPrev = new Float32Array(0);
			this.trailPrevY = new Float32Array(0);
			this.trailPrevZ = new Float32Array(0);
			this.trailAccum = new Float32Array(0);
			this.trailEmitted = new Int32Array(0);
			this.trailActive = new Uint8Array(0);
			this.trailSeen = new Int32Array(0);

			// 溅射状态
			if (bound.splash) {
				this.splashX = new Float32Array(MAX_SPLASH_PARTICLES);
				this.splashY = new Float32Array(MAX_SPLASH_PARTICLES);
				this.splashZ = new Float32Array(MAX_SPLASH_PARTICLES);
				this.splashVX = new Float32Array(MAX_SPLASH_PARTICLES);
				this.splashVY = new Float32Array(MAX_SPLASH_PARTICLES);
				this.splashVZ = new Float32Array(MAX_SPLASH_PARTICLES);
				this.splashSpawn = new Float32Array(MAX_SPLASH_PARTICLES);
				this.splashActive = new Uint8Array(MAX_SPLASH_PARTICLES);
				this.splashInside = new Uint8Array(bound.count);
				this.splashHidden = new Uint8Array(bound.count);
				this.splashCursor = 0;
				this.splashSerial = 0;
			}

			const def = bound.definition;
			this.rotates = def.orientation === 'view' && (this.viewYaw !== 0 || this.viewPitch !== 0);
			this.cosYaw = Math.cos(this.viewYaw * C.DEG);
			this.sinYaw = Math.sin(this.viewYaw * C.DEG);
			this.cosPitch = Math.cos(this.viewPitch * C.DEG);
			this.sinPitch = Math.sin(this.viewPitch * C.DEG);

			this.stopped = false;
			this.stopAge = 0;
			this.stopScaled = 0;
			this.stopCycle = 0;
			this.stopPhase = 0;
			this.stopContinuation = 0;
			this.stopTail = 0;
		}

		/** 停止发射：过滤停止后出生的粒子，已有粒子按寿命跑完。 */
		stop(age) {
			if (this.stopped) return;
			this.stopped = true;
			this.stopAge = age;
			this.stopScaled = age * this.playScale;
			const duration = this.bound.duration;
			const def = this.bound.definition;
			if (def.loop && this.cyclesForward && this.playScale > 0) {
				this.stopCycle = Math.floor(this.stopScaled / duration);
				this.stopPhase = this.stopScaled - this.stopCycle * duration;
			} else if (def.loop) {
				const wrapped = this.stopScaled % duration;
				this.stopPhase = wrapped < 0 ? wrapped + duration : wrapped;
				this.stopContinuation = this.stopPhase;
			} else {
				this.stopPhase = this.playScale < 0
					? clamp(duration + this.stopScaled, 0, duration)
					: this.stopScaled;
				this.stopContinuation = this.stopPhase;
			}
			const emission = this.bound.emission;
			const trailSpan = this.trail && this.trail.mode === 'time' ? this.trailCap * this.trail.interval : 0;
			const splashSpan = this.bound.splash ? this.bound.splash.life : 0;
			this.stopTail = Math.max(0.05, emission.lifetime * (1 + Math.abs(emission.lifetimeJitter)))
				+ trailSpan + splashSpan;
			this.stopTail /= Math.max(0.01, Math.abs(this.playScale));
		}

		resetSplash() {
			if (!this.bound.splash) return;
			this.splashActive.fill(0);
			this.splashInside.fill(0);
			this.splashHidden.fill(0);
			this.splashCursor = 0;
			this.splashSerial = 0;
		}

		// ── 采样 ──
		sampleRawInto(out, cycle, age) {
			const def = this.bound.definition;
			const duration = this.bound.duration;
			const scaled = age * this.playScale;
			if (!def.loop) {
				const sampleAge = this.playScale < 0 ? clamp(duration + scaled, 0, duration) : scaled;
				out.reset(this.sourceMaxParticles);
				this.bound.style.sample(this.bound, this.origin, this.rand, sampleAge, out);
				return;
			}
			if (!this.cyclesForward) {
				const wrapped = scaled % duration;
				out.reset(this.sourceMaxParticles);
				this.bound.style.sample(this.bound, this.origin, this.rand, wrapped < 0 ? wrapped + duration : wrapped, out);
				return;
			}
			const cycleIndex = Math.floor(scaled / duration);
			const phase = scaled - cycleIndex * duration;
			out.reset(this.sourceMaxParticles);
			const backMax = Math.min(cycleIndex, this.loopLookback);
			for (let back = backMax; back >= 0; back--) {
				cycle.reset(this.sourcePerCycle);
				this.bound.style.sample(this.bound, this.origin, this.rand, phase + back * duration, cycle);
				out.append(cycle);
			}
		}

		sampleStoppedInto(out, cycle, age) {
			const def = this.bound.definition;
			const duration = this.bound.duration;
			const scaled = age * this.playScale;
			out.reset(this.sourceMaxParticles);
			if (def.loop && this.cyclesForward && this.playScale > 0) {
				let cycleIndex = this.stopCycle;
				const oldest = Math.max(0, this.stopCycle - this.loopLookback);
				while (cycleIndex >= oldest) {
					const sampleAge = scaled - cycleIndex * duration;
					cycle.reset(this.sourcePerCycle);
					this.bound.style.sample(this.bound, this.origin, this.rand, sampleAge, cycle);
					this.retainBirths(cycle, cycleIndex === this.stopCycle ? this.stopPhase : Infinity);
					out.append(cycle);
					cycleIndex--;
				}
				return;
			}
			const sampleAge = this.stopContinuation + (age - this.stopAge) * this.playScale;
			cycle.reset(this.sourcePerCycle);
			this.bound.style.sample(this.bound, this.origin, this.rand, sampleAge, cycle);
			const cutoff = def.loop ? this.stopPhase : (this.playScale >= 0 ? this.stopPhase : Infinity);
			this.retainBirths(cycle, cutoff);
			out.append(cycle);
		}

		retainBirths(hist, cutoff) {
			if (cutoff === Infinity) return;
			if (this.birthKeep.length < hist.count) this.birthKeep = new Uint8Array(Math.max(hist.count, this.birthKeep.length * 2));
			for (let i = 0; i < hist.count; i++) {
				const id = hist.ids[i];
				const index = id >= 0 && id < this.bound.count ? id : 0;
				this.birthKeep[i] = birthAge(this.bound, this.rand, index) <= cutoff + 1e-4 ? 1 : 0;
			}
			const keep = this.birthKeep;
			let write = 0;
			for (let i = 0; i < hist.count; i++) {
				if (!keep[i]) continue;
				if (write !== i) {
					hist.x[write] = hist.x[i]; hist.y[write] = hist.y[i]; hist.z[write] = hist.z[i];
					hist.size[write] = hist.size[i]; hist.alpha[write] = hist.alpha[i];
					hist.argb[write] = hist.argb[i]; hist.ids[write] = hist.ids[i];
				}
				write++;
			}
			hist.count = write;
		}

		/** 已落地的雨滴源粒子不再绘制；轨迹仍按历史事件生成。 */
		hideCollided(out) {
			if (!this.splashHidden) return;
			if (this.keepFlags.length < out.count) this.keepFlags = new Uint8Array(Math.max(out.count, this.keepFlags.length * 2));
			for (let i = 0; i < out.count; i++) {
				const id = out.ids[i];
				this.keepFlags[i] = id >= 0 && id < this.splashHidden.length && this.splashHidden[id] ? 0 : 1;
			}
			const keep = this.keepFlags;
			let write = 0;
			for (let i = 0; i < out.count; i++) {
				if (!keep[i]) continue;
				if (write !== i) {
					out.x[write] = out.x[i]; out.y[write] = out.y[i]; out.z[write] = out.z[i];
					out.size[write] = out.size[i]; out.alpha[write] = out.alpha[i];
					out.argb[write] = out.argb[i]; out.ids[write] = out.ids[i];
				}
				write++;
			}
			out.count = write;
		}

		sample(age, withTrail) {
			if (this.stopped) this.sampleStoppedInto(this.buffer, this.cycle, age);
			else this.sampleRawInto(this.buffer, this.cycle, age);
			this.hideCollided(this.buffer);
			if (withTrail !== false) this.appendTrails(this.buffer, age);
			if (this.rotates) this.rotate(this.buffer);
			return this.buffer;
		}

		// ── 轨迹 ──
		appendTrails(out, age) {
			const trail = this.trail;
			if (!trail || this.trailCap === 0) return;
			if (this.stopped && trail.mode !== 'time') return;
			const sourceCount = out.count;
			if (trail.mode === 'distance' && sourceCount === 0 && !this.stopped) return;
			const baseCount = this.sourceMaxParticles;
			const limit = baseCount + Math.min(baseCount * this.trailCap, MAX_TRAIL_PARTICLES);
			if (trail.mode === 'distance') this.appendDistanceTrails(out, age, sourceCount, limit);
			else this.appendTimeTrails(out, age, sourceCount, baseCount, limit);
		}

		appendTimeTrails(out, age, sourceCount, strideBase, limit) {
			const trail = this.trail;
			if (trail.drift > 0) { this.appendDriftingTrails(out, age, strideBase, limit); return; }
			if (trail.spread > 0) this.indexSource(out, sourceCount);
			for (let segment = 1; segment <= this.trailCap; segment++) {
				const pastAge = age - segment * trail.interval;
				if (!this.bound.definition.loop && pastAge <= 0) break;
				if (this.stopped && pastAge > this.stopAge) continue;
				this.sampleRawInto(this.trailScratch, this.trailCycle, pastAge);
				if (this.trailScratch.count > 0) {
					const stride = Math.max(1, Math.ceil(this.trailScratch.count / strideBase));
					this.appendSegment(out, this.trailScratch, segment, stride, trail.spread * segment / this.trailCap, limit);
					if (out.count >= limit) break;
				}
			}
		}

		appendDriftingTrails(out, age, sourceCount, limit) {
			const trail = this.trail;
			const step = trail.interval;
			if (step <= 0) return;
			const rawLatest = Math.floor(age / step + 1e-4);
			const latest = this.stopped ? Math.min(rawLatest, Math.floor(this.stopAge / step + 1e-4)) : rawLatest;
			const oldest = Math.max(0, rawLatest - this.trailCap + 1);
			for (let eventId = latest; eventId >= oldest && out.count < limit; eventId--) {
				const eventAge = age - eventId * step;
				const segment = clamp(Math.trunc(eventAge / step) + 1, 1, this.trailCap);
				this.sampleRawInto(this.trailScratch, this.trailCycle, eventId * step);
				if (this.trailScratch.count > 0) {
					const stride = Math.max(1, Math.ceil(this.trailScratch.count / sourceCount));
					this.appendDriftSegment(out, this.trailScratch, eventId, eventAge, segment, stride, limit);
					if (out.count >= limit) break;
				}
			}
		}

		appendDriftSegment(out, hist, eventId, eventAge, segment, stride, limit) {
			const trail = this.trail;
			const sizeScale = this.trailSizeMul[segment];
			const alphaScale = this.trailAlphaMul[segment];
			const spreadRadius = trail.spread * clamp(eventAge / (this.trailCap * trail.interval), 0, 1);
			const driftRadius = trail.drift * eventAge;
			const eventKey = (eventId ^ (eventId >>> 32)) | 0;
			for (let i = 0; i < hist.count && out.count < limit; i += stride) {
				const alpha = hist.alpha[i] * alphaScale;
				if (alpha > 0.004) {
					const id = hist.ids[i];
					const key = (id ^ eventKey) | 0;
					this.driftDirection(key);
					const radius = spreadRadius + driftRadius;
					out.push(
						hist.x[i] + this.trailOffset[0] * radius,
						hist.y[i] + this.trailOffset[1] * radius,
						hist.z[i] + this.trailOffset[2] * radius,
						hist.size[i] * sizeScale, alpha,
						C.withAlpha(this.trailColorOr(hist.argb[i]), alpha), id,
					);
				}
			}
		}

		driftDirection(key) {
			let dx = this.rand.signed(C.Streams.JITTER, key, 3000);
			let dy = this.rand.signed(C.Streams.JITTER, key, 3001);
			let dz = this.rand.signed(C.Streams.JITTER, key, 3002);
			const length = Math.sqrt(dx * dx + dy * dy + dz * dz);
			if (length < 1e-4) { dx = 0; dy = 1; dz = 0; }
			else { dx /= length; dy /= length; dz /= length; }
			this.trailOffset[0] = dx; this.trailOffset[1] = dy; this.trailOffset[2] = dz;
		}

		appendDistanceTrails(out, age, sourceCount, limit) {
			const trail = this.trail;
			const probes = this.trailCap * 2;
			const probe = trail.lag / probes;
			if (probe <= 0) return;
			this.ensureTrailState(this.sourceMaxParticles);
			this.indexSource(out, sourceCount);
			for (let slot = 0; slot < sourceCount; slot++) {
				this.trailPrev[slot] = out.x[slot];
				this.trailPrevY[slot] = out.y[slot];
				this.trailPrevZ[slot] = out.z[slot];
				this.trailAccum[slot] = 0;
				this.trailEmitted[slot] = 0;
				this.trailActive[slot] = 1;
				this.trailSeen[slot] = 0;
			}

			let stamp = 0;
			let previousCycle = this.cycleIndex(age);
			for (let j = 1; j <= probes && out.count < limit; j++) {
				const pastAge = age - j * probe;
				if (!this.bound.definition.loop && pastAge <= 0) break;
				const cycleNow = this.cycleIndex(pastAge);
				if (this.bound.definition.loop && cycleNow !== previousCycle) {
					previousCycle = cycleNow;
					for (let s = 0; s < sourceCount; s++) this.trailActive[s] = 0;
					continue;
				}
				previousCycle = cycleNow;
				this.sampleRawInto(this.trailScratch, this.trailCycle, pastAge);
				stamp++;
				let hitLimit = false;
				for (let i = this.trailScratch.count - 1; i >= 0; i--) {
					const id = this.trailScratch.ids[i];
					const slot = id >= 0 ? (this.trailSourceIndex.has(id) ? this.trailSourceIndex.get(id) : -1) : -1;
					if (slot < 0 || slot >= sourceCount || !this.trailActive[slot] || this.trailSeen[slot] === stamp) continue;
					this.trailSeen[slot] = stamp;
					const cx = this.trailScratch.x[i], cy = this.trailScratch.y[i], cz = this.trailScratch.z[i];
					const dx = cx - this.trailPrev[slot];
					const dy = cy - this.trailPrevY[slot];
					const dz = cz - this.trailPrevZ[slot];
					const distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
					if (distance > 1e-6) {
						const start = this.trailAccum[slot];
						const end = start + distance;
						let segment = this.trailEmitted[slot];
						while (segment < this.trailCap) {
							const target = trail.distance * (segment + 1);
							if (target > end) break;
							const u = clamp((target - start) / distance, 0, 1);
							const px = this.trailPrev[slot] + dx * u;
							const py = this.trailPrevY[slot] + dy * u;
							const pz = this.trailPrevZ[slot] + dz * u;
							const alpha = this.trailScratch.alpha[i] * this.trailAlphaMul[segment + 1];
							if (alpha > 0.004) {
								let ox = 0, oy = 0, oz = 0;
								if (trail.spread > 0) {
									this.lateralOffset(out, px, py, pz, id, segment + 1, trail.spread * (segment + 1) / this.trailCap);
									ox = this.trailOffset[0]; oy = this.trailOffset[1]; oz = this.trailOffset[2];
								}
								out.push(px + ox, py + oy, pz + oz,
									this.trailScratch.size[i] * this.trailSizeMul[segment + 1], alpha,
									C.withAlpha(this.trailColorOr(this.trailScratch.argb[i]), alpha), id);
							}
							segment++;
							if (out.count >= limit) { this.trailEmitted[slot] = segment; hitLimit = true; break; }
						}
						this.trailEmitted[slot] = segment;
						this.trailAccum[slot] = end;
					}
					this.trailPrev[slot] = cx;
					this.trailPrevY[slot] = cy;
					this.trailPrevZ[slot] = cz;
					if (hitLimit) break;
				}
				for (let s = 0; s < sourceCount; s++) {
					if (this.trailActive[s] && this.trailSeen[s] !== stamp) this.trailActive[s] = 0;
				}
			}
		}

		appendSegment(out, hist, segment, stride, spreadRadius, limit) {
			const sizeScale = this.trailSizeMul[segment];
			const alphaScale = this.trailAlphaMul[segment];
			for (let i = 0; i < hist.count && out.count < limit; i += stride) {
				const alpha = hist.alpha[i] * alphaScale;
				if (alpha > 0.004) {
					let px = hist.x[i], py = hist.y[i], pz = hist.z[i];
					if (spreadRadius > 0) {
						this.lateralOffset(out, px, py, pz, hist.ids[i], segment, spreadRadius);
						px += this.trailOffset[0]; py += this.trailOffset[1]; pz += this.trailOffset[2];
					}
					out.push(px, py, pz, hist.size[i] * sizeScale, alpha,
						C.withAlpha(this.trailColorOr(hist.argb[i]), alpha), hist.ids[i]);
				}
			}
		}

		indexSource(out, sourceCount) {
			this.trailSourceIndex.clear();
			for (let i = 0; i < sourceCount; i++) {
				const id = out.ids[i];
				if (id >= 0) this.trailSourceIndex.set(id, i);
			}
		}

		lateralOffset(out, px, py, pz, id, segment, radius) {
			const slot = id >= 0 && this.trailSourceIndex.has(id) ? this.trailSourceIndex.get(id) : -1;
			let tx = 0, ty = 0, tz = 0;
			if (slot >= 0) {
				tx = out.x[slot] - px; ty = out.y[slot] - py; tz = out.z[slot] - pz;
			}
			let e1x = 1, e1y = 0, e1z = 0, e2x = 0, e2y = 0, e2z = 1;
			const length = Math.sqrt(tx * tx + ty * ty + tz * tz);
			if (length > 1e-4) {
				tx /= length; ty /= length; tz /= length;
				const rx = Math.abs(ty) < 0.9 ? 0 : 1;
				const ry = Math.abs(ty) < 0.9 ? 1 : 0;
				let nx = ty * 0 - tz * ry;
				let ny = tz * rx - tx * 0;
				let nz = tx * ry - ty * rx;
				const nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
				if (nl > 1e-4) {
					nx /= nl; ny /= nl; nz /= nl;
					e1x = nx; e1y = ny; e1z = nz;
					e2x = ty * nz - tz * ny;
					e2y = tz * nx - tx * nz;
					e2z = tx * ny - ty * nx;
				}
			}
			const a = this.rand.signed(C.Streams.JITTER, id, TRAIL_SPREAD_DRAW + segment * 2);
			const b = this.rand.signed(C.Streams.JITTER, id, TRAIL_SPREAD_DRAW + segment * 2 + 1);
			const unit = Math.sqrt(a * a + b * b);
			const scale = unit > 1e-4 ? radius / unit : 0;
			this.trailOffset[0] = (e1x * a + e2x * b) * scale;
			this.trailOffset[1] = (e1y * a + e2y * b) * scale;
			this.trailOffset[2] = (e1z * a + e2z * b) * scale;
		}

		ensureTrailState(size) {
			if (this.trailPrev.length >= size) return;
			const capacity = Math.max(size, 64);
			this.trailPrev = new Float32Array(capacity);
			this.trailPrevY = new Float32Array(capacity);
			this.trailPrevZ = new Float32Array(capacity);
			this.trailAccum = new Float32Array(capacity);
			this.trailEmitted = new Int32Array(capacity);
			this.trailActive = new Uint8Array(capacity);
			this.trailSeen = new Int32Array(capacity);
		}

		cycleIndex(age) {
			if (!this.bound.definition.loop) return 0;
			return Math.floor(age * this.playScale / this.bound.duration);
		}

		trailColorOr(source) {
			return this.bound.trailColor === 0 ? source : ((this.bound.trailColor & 0x00ffffff) | 0xff000000) >>> 0;
		}

		rotate(out) {
			const ox = this.origin[0], oy = this.origin[1], oz = this.origin[2];
			for (let i = 0; i < out.count; i++) {
				const dx = out.x[i] - ox;
				const dy = out.y[i] - oy;
				const dz = out.z[i] - oz;
				const y = dy * this.cosPitch - dz * this.sinPitch;
				const z = dy * this.sinPitch + dz * this.cosPitch;
				out.x[i] = ox + dx * this.cosYaw - z * this.sinYaw;
				out.y[i] = oy + y;
				out.z[i] = oz + dx * this.sinYaw + z * this.cosYaw;
			}
		}

		/** orientation=view 时把样式给出的速度向量转到实际朝向，供拉伸使用。 */
		stretchVector(age) {
			const style = this.bound.style;
			if (!style.stretchVector) return null;
			const vector = style.stretchVector(this.bound, age);
			if (!vector || !this.rotates) return vector;
			const y = vector[1] * this.cosPitch - vector[2] * this.sinPitch;
			const z = vector[1] * this.sinPitch + vector[2] * this.cosPitch;
			return [
				vector[0] * this.cosYaw - z * this.sinYaw,
				y,
				vector[0] * this.sinYaw + z * this.cosYaw,
			];
		}

		// ── 溅射（预览用水平地面代替方块查询） ──
		isSolidAt(y) {
			return this.groundY !== null && y < this.groundY;
		}

		tickSplash(age) {
			const splash = this.bound.splash;
			if (!splash || this.stopped || this.groundY === null) return;
			this.sampleRawInto(this.splashSample, this.splashCycle, age);
			const scaleFactor = Math.abs(this.scale) > 1e-4 ? this.scale : 1;
			for (let i = 0; i < this.splashSample.count; i++) {
				const wx = this.origin[0] + (this.splashSample.x[i] - this.origin[0]) * scaleFactor;
				const wy = this.origin[1] + (this.splashSample.y[i] - this.origin[1]) * scaleFactor;
				const wz = this.origin[2] + (this.splashSample.z[i] - this.origin[2]) * scaleFactor;
				const id = this.splashSample.ids[i];
				const slot = id >= 0 && id < this.bound.count ? id : 0;
				if (!this.isSolidAt(wy)) {
					this.splashInside[slot] = 0;
					this.splashHidden[slot] = 0;
					continue;
				}
				if (this.splashInside[slot]) continue;
				this.splashInside[slot] = 1;
				this.splashHidden[slot] = 1;
				this.spawnSplash(Math.floor(wx) + 0.5, this.groundY, Math.floor(wz) + 0.5, age, scaleFactor);
			}
		}

		spawnSplash(worldX, worldY, worldZ, age, scaleFactor) {
			const splash = this.bound.splash;
			if (!splash) return;
			const localX = this.origin[0] + (worldX - this.origin[0]) / scaleFactor;
			const localY = this.origin[1] + (worldY - this.origin[1]) / scaleFactor;
			const localZ = this.origin[2] + (worldZ - this.origin[2]) / scaleFactor;
			const serial = this.splashSerial++;
			for (let n = 0; n < splash.count; n++) {
				const angle = this.rand.unit(C.Streams.JITTER, serial, n * 4) * C.TAU;
				const radial = splash.speed * (0.35 + 0.65 * this.rand.unit(C.Streams.JITTER, serial, n * 4 + 1));
				const up = splash.speed * (0.9 + 0.7 * this.rand.unit(C.Streams.JITTER, serial, n * 4 + 2));
				const slot = this.splashCursor;
				this.splashCursor = (this.splashCursor + 1) % MAX_SPLASH_PARTICLES;
				this.splashX[slot] = localX + Math.cos(angle) * 0.05;
				this.splashY[slot] = localY;
				this.splashZ[slot] = localZ + Math.sin(angle) * 0.05;
				this.splashVX[slot] = Math.cos(angle) * radial / scaleFactor;
				this.splashVY[slot] = up / scaleFactor;
				this.splashVZ[slot] = Math.sin(angle) * radial / scaleFactor;
				this.splashSpawn[slot] = age;
				this.splashActive[slot] = 1;
			}
		}

		appendSplashes(out, age) {
			const splash = this.bound.splash;
			if (!splash) return;
			for (let slot = 0; slot < MAX_SPLASH_PARTICLES; slot++) {
				if (!this.splashActive[slot]) continue;
				const a = age - this.splashSpawn[slot];
				if (a < 0 || a >= splash.life) { this.splashActive[slot] = 0; continue; }
				const alpha = clamp(1 - a / splash.life, 0, 1);
				out.push(
					this.splashX[slot] + this.splashVX[slot] * a,
					this.splashY[slot] + this.splashVY[slot] * a + 0.5 * SPLASH_GRAVITY * a * a,
					this.splashZ[slot] + this.splashVZ[slot] * a,
					splash.size * (1 - 0.25 * a / splash.life),
					alpha,
					C.withAlpha(splash.color, alpha),
					-1,
				);
			}
		}

		/** 渲染一帧：源粒子 + 轨迹 + 溅射。 */
		frame(age) {
			this.sample(age, true);
			this.appendSplashes(this.buffer, age);
			return this.buffer;
		}
	}

	/** 黄金采样入口：直接调用样式的 sample，origin 固定为原点。 */
	function sampleRaw(bound, rand, age, origin) {
		rand.ensureCount(bound.count);
		const out = new C.ParticleBuffer();
		out.reset(bound.count);
		bound.style.sample(bound, origin || [0, 0, 0], rand, age, out);
		return out;
	}

	return {
		MOD_CONFIG, MAX_LOOP_LOOKBACK, MAX_TRAIL_PARTICLES, MAX_SPLASH_PARTICLES,
		TRAIL_SIZE_SEGMENTS,
		parseDefinition, definitionToJson, minimalDefinition, bind, boundsRadius, particleCapacity, birthAge,
		Playback, sampleRaw,
	};
});
