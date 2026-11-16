/*
 * styles.js — 9 个内置样式的参数声明与运动核
 *
 * 参数声明逐条抄自 src/main/kotlin/net/astrorbits/style/{BurstStyles,FlowStyles,AmbientStyles}.kt
 * 的 StyleSchema；sample() 是这些文件里 sample() 的逐行移植。随机流的取用顺序不能改，
 * 改了就与模组的回放结果不一致（rngVersion 1）。
 */
(function (root, factory) {
	if (typeof module === 'object' && module.exports) module.exports = factory(require('./core.js'));
	else root.APStyles = factory(root.APCore);
})(typeof self !== 'undefined' ? self : this, function (C) {
	'use strict';

	// ───────────────────────── 参数声明 ─────────────────────────
	const num = (name, dflt, doc, range, min, max) => ({ name, kind: 'number', default: dflt, doc, range, min, max });
	const int = (name, dflt, doc, range, min, max) => ({ name, kind: 'integer', default: dflt, doc, range, min, max });
	const bool = (name, dflt, doc) => ({ name, kind: 'boolean', default: dflt, doc, range: '' });
	const vec = (name, x, y, z, doc) => ({ name, kind: 'vector', default: [x, y, z], doc, range: '' });
	const numbers = (name, values, doc) => ({ name, kind: 'numberList', default: values, doc, range: '' });
	const texts = (name, values, doc) => ({ name, kind: 'stringList', default: values, doc, range: '' });
	const color = (name, doc) => ({ name, kind: 'color', default: null, doc, range: '' });

	function schemaOf(list) {
		const map = new Map();
		for (const spec of list) map.set(spec.name, spec);
		return { params: list, spec: (n) => map.get(n) || null };
	}

	// ───────────────────────── Binder ─────────────────────────
	class Binder {
		constructor(params, duration, count, schema) {
			this.params = params || {};
			this.duration = duration;
			this.count = count;
			this.schema = schema || null;
			this.problems = [];
			if (this.schema) {
				for (const key of Object.keys(this.params)) {
					if (!this.schema.spec(key)) this.problems.push('unknown parameter: ' + key);
				}
			}
		}

		spec(name) { return this.schema ? this.schema.spec(name) : null; }

		clamp(name, value) {
			const spec = this.spec(name);
			if (!spec) return value;
			const min = spec.min === undefined || spec.min === null ? -Infinity : spec.min;
			const max = spec.max === undefined || spec.max === null ? Infinity : spec.max;
			return Math.min(max, Math.max(min, value));
		}

		bindNumber(element, dflt, path) {
			path = path || '';
			if (element === undefined || element === null) return C.ConstNumber(dflt);
			if (typeof element === 'string') {
				try {
					return C.boundNumberFromBaked(C.bakeExpr(element, this.duration, this.count));
				} catch (err) {
					this.problems.push(path + ': ' + err.message);
					return C.ConstNumber(dflt);
				}
			}
			if (typeof element === 'number') return C.ConstNumber(this.clamp(path, element));
			this.problems.push(path + ': expected number');
			return C.ConstNumber(dflt);
		}

		num(name, dflt) {
			const spec = this.spec(name);
			const fallback = dflt !== undefined ? dflt : (spec && spec.kind === 'number' ? spec.default : 0);
			return this.bindNumber(this.params[name], fallback, name);
		}

		number(name, dflt) {
			const spec = this.spec(name);
			const fallback = dflt !== undefined ? dflt : (spec && spec.kind === 'number' ? spec.default : 0);
			return this.clamp(name, this.num(name, fallback).at(0));
		}

		vec3(name) {
			const spec = this.spec(name);
			const dflt = spec && Array.isArray(spec.default) ? spec.default : [0, 0, 0];
			return toVec(this.params[name], dflt);
		}

		bool(name, dflt) {
			const v = this.params[name];
			if (typeof v === 'boolean') return v;
			if (dflt !== undefined) return dflt;
			const spec = this.spec(name);
			return spec && spec.kind === 'boolean' ? spec.default : false;
		}

		int(name, dflt) {
			const v = this.params[name];
			const spec = this.spec(name);
			const fallback = dflt !== undefined ? dflt : (spec && spec.kind === 'integer' ? spec.default : 0);
			if (typeof v !== 'number' || !Number.isFinite(v)) return fallback;
			let value = Math.trunc(v);
			if (!spec) return value;
			const min = spec.min === undefined || spec.min === null ? -Infinity : Math.trunc(spec.min);
			const max = spec.max === undefined || spec.max === null ? Infinity : Math.trunc(spec.max);
			value = Math.min(max, Math.max(min, value));
			return value;
		}

		strings(name) {
			const v = this.params[name];
			const spec = this.spec(name);
			if (Array.isArray(v)) return v.filter((s) => typeof s === 'string');
			return spec && spec.kind === 'stringList' ? spec.default.slice() : [];
		}

		floats(name) {
			const v = this.params[name];
			const spec = this.spec(name);
			if (Array.isArray(v)) return Float32Array.from(v.map(Number));
			return spec && spec.kind === 'numberList' ? Float32Array.from(spec.default) : null;
		}

		colorOrNull(name) {
			const v = this.params[name];
			return v === undefined || v === null ? null : C.parseColor(v, 0);
		}

		integral(name, dflt) {
			const spec = this.spec(name);
			const fallback = dflt !== undefined ? dflt : (spec && typeof spec.default === 'number' ? spec.default : 0);
			const element = this.params[name];
			if (element === undefined || element === null) {
				return C.LutNumber(Float32Array.from([0, fallback * this.duration]), this.duration);
			}
			if (typeof element !== 'string') {
				return C.LutNumber(Float32Array.from([0, fallback * this.duration]), this.duration);
			}
			try {
				return C.boundNumberFromBaked(C.bakeIntegral(element, this.duration, this.count));
			} catch (err) {
				this.problems.push(name + ': ' + err.message);
				return C.LutNumber(Float32Array.from([0, fallback * this.duration]), this.duration);
			}
		}

		curve(name, dfltPoints) { return parseCurve(this.params[name], dfltPoints); }

		gradient(name) { return parseGradient(this.params[name]); }
	}

	function toVec(element, dflt) {
		if (element === undefined || element === null) return dflt.slice();
		if (typeof element === 'number') return [0, element, 0];
		if (!Array.isArray(element) || element.length < 3) return dflt.slice();
		return [Number(element[0]), Number(element[1]), Number(element[2])];
	}

	function parseCurve(element, dfltPoints) {
		if (!Array.isArray(element)) return new C.FloatCurve(dfltPoints);
		const points = [];
		for (const pair of element) {
			if (!Array.isArray(pair) || pair.length < 2) return new C.FloatCurve(dfltPoints);
			points.push(Number(pair[0]), Number(pair[1]));
		}
		if (points.length < 4) return new C.FloatCurve(dfltPoints);
		return new C.FloatCurve(points);
	}

	function parseGradient(element) {
		if (!Array.isArray(element)) return null;
		const stops = [], colors = [];
		for (const pair of element) {
			if (!Array.isArray(pair) || pair.length < 2) return null;
			stops.push(Number(pair[0]));
			colors.push(C.parseColor(pair[1], 0xffffffff));
		}
		if (stops.length < 2) return null;
		return new C.ColorGradient(stops, colors);
	}

	// ───────────────────────── 9 个样式 ─────────────────────────
	const _dir = [0, 0, 0];
	const _pos = [0, 0, 0];
	const _off = [0, 0, 0];
	const _basis = [0, 0, 0, 0, 0, 0];

	const STYLES = [];

	// 1. 放射状爆发
	STYLES.push({
		id: 'astrorbitsparticle:radial_burst',
		title: '放射状爆发',
		icon: 'radial',
		accent: '#ffb347',
		summary: '从一点向四周高速炸开，可收成锥形喷射',
		schema: schemaOf([
			num('speed', 6, '基准速度（块/秒），可写含 t 的表达式', '0..64', 0, 64),
			num('speedJitter', 0.12, '粒子之间的速度差异比例', '0..1', 0, 1),
			num('originJitter', 0, '原点抖动半径（块）', '0..8', 0, 8),
			vec('axis', 0, 1, 0, '锥形喷射主方向，只在 cone 小于 180 时生效'),
			num('cone', 180, '喷射半角（度），180 为全向', '0..180', 0, 180),
			num('shellBias', 0, '速度分布向快端偏移的比例', '0..1', 0, 1),
			num('bobAmplitude', 0, '沿世界 Y 轴的上下波动幅度（块），0 关闭', '0..8', 0, 8),
			num('bobPeriod', 1.2, '上下波动周期（秒），按粒子自身年龄计', '0.1..120', 0.1, 120),
		]),
		bind(b) {
			return {
				speed: b.num('speed'), speedJitter: b.number('speedJitter'), originJitter: b.number('originJitter'),
				axis: b.vec3('axis'), cone: b.number('cone'), shellBias: b.number('shellBias'),
				bobAmplitude: b.number('bobAmplitude'), bobPeriod: b.number('bobPeriod'),
			};
		},
		sample(effect, origin, rand, age, out) {
			const p = effect.styleParams;
			const halfAngle = p.cone * C.DEG;
			const speedBase = p.speed.at(age);
			for (let i = 0; i < effect.count; i++) {
				const life = C.EmissionMath.lifetime(effect.emission, rand, i);
				const local = age - C.EmissionMath.birth(effect.emission, rand, i, effect.count);
				if (local <= 0 || local >= life) continue;
				if (p.cone >= 179.9) C.sphereInto(_dir, rand, i);
				else C.coneInto(_dir, p.axis, halfAngle, rand, i);
				const speed = speedBase *
					(1 + p.speedJitter * rand.signed(C.Streams.SPEED, i)) *
					(1 + p.shellBias * rand.unit(C.Streams.SPEED, i, 2));
				C.posInto(_pos, origin[0], origin[1], origin[2], _dir[0] * speed, _dir[1] * speed, _dir[2] * speed,
					effect.physics.gravity, effect.physics.drag, effect.physics.wind, local);
				C.jitterInto(_off, p.originJitter, rand, i);
				let bob = 0;
				if (p.bobAmplitude !== 0) {
					const phase = rand.unit(C.Streams.PHASE, i) * C.TAU;
					bob = p.bobAmplitude * Math.sin(C.TAU * local / p.bobPeriod + phase);
				}
				C.emit(effect, rand, i, age, local / life,
					_pos[0] + _off[0], _pos[1] + _off[1] + bob, _pos[2] + _off[2], out);
			}
		},
	});

	// 2. 球状爆发
	STYLES.push({
		id: 'astrorbitsparticle:spherical_burst',
		title: '球状爆发',
		icon: 'sphere',
		accent: '#7fd4ff',
		summary: '空心壳到实心球的均匀扩散，可压扁成盘',
		schema: schemaOf([
			num('speed', 5, '球面最大速度（块/秒）', '0..64', 0, 64),
			num('fill', 0, '0 空心壳，1 实心球，中间值介于两者', '0..1', 0, 1),
			num('shellJitter', 0.03, '壳层厚度抖动比例，fill 为 0 时生效', '0..1', 0, 1),
			num('flatten', 1, 'Y 轴压扁系数，小于 1 得到扁球', '0.1..4', 0.1, 4),
			num('originJitter', 0, '原点抖动半径（块）', '0..8', 0, 8),
		]),
		bind(b) {
			return {
				speed: b.num('speed'), fill: b.number('fill'), shellJitter: b.number('shellJitter'),
				flatten: b.number('flatten'), originJitter: b.number('originJitter'),
			};
		},
		sample(effect, origin, rand, age, out) {
			const p = effect.styleParams;
			const speedBase = p.speed.at(age);
			for (let i = 0; i < effect.count; i++) {
				const life = C.EmissionMath.lifetime(effect.emission, rand, i);
				const local = age - C.EmissionMath.birth(effect.emission, rand, i, effect.count);
				if (local <= 0 || local >= life) continue;
				C.sphereInto(_dir, rand, i);
				const c = rand.unit(C.Streams.SPEED, i);
				const shell = 1 + p.shellJitter * (2 * c - 1);
				const filled = Math.cbrt(c);
				const speed = speedBase * (shell + (filled - shell) * p.fill);
				C.posInto(_pos, origin[0], origin[1], origin[2], _dir[0] * speed, _dir[1] * speed, _dir[2] * speed,
					effect.physics.gravity, effect.physics.drag, effect.physics.wind, local);
				C.jitterInto(_off, p.originJitter, rand, i);
				const y = origin[1] + (_pos[1] - origin[1]) * p.flatten;
				C.emit(effect, rand, i, age, local / life,
					_pos[0] + _off[0], y + _off[1], _pos[2] + _off[2], out);
			}
		},
	});

	// 3. 环形爆发
	STYLES.push({
		id: 'astrorbitsparticle:ring_burst',
		title: '环形爆发',
		icon: 'ring',
		accent: '#b388ff',
		summary: '沿平面圆环向外扩张，可切成扇环',
		schema: schemaOf([
			vec('planeNormal', 0, 1, 0, '环所在平面的法线'),
			num('speed', 6, '径向速度（块/秒）', '0..64', 0, 64),
			num('speedJitter', 0.08, '粒子之间的速度差异比例', '0..1', 0, 1),
			num('thickness', 0, '沿法线的环厚度（块）', '0..8', 0, 8),
			num('radius0', 0, '初始半径，大于 0 时从既有圆环向外扩张', '0..64', 0, 64),
			bool('even', true, '是否等角度分布；false 为随机角度'),
			num('angleJitter', 0, '等角度分布上叠加的角度抖动（度）', '0..90', 0, 90),
			num('arc', 360, '圆弧张角（度），小于 360 得到扇环', '1..360', 1, 360),
		]),
		bind(b) {
			return {
				planeNormal: b.vec3('planeNormal'), speed: b.num('speed'), speedJitter: b.number('speedJitter'),
				thickness: b.number('thickness'), radius0: b.number('radius0'), even: b.bool('even'),
				angleJitter: b.number('angleJitter'), arc: b.number('arc'),
			};
		},
		sample(effect, origin, rand, age, out) {
			const p = effect.styleParams;
			const normal = C.vlen(p.planeNormal[0], p.planeNormal[1], p.planeNormal[2]) < 1e-9
				? [0, 1, 0] : C.vnorm(p.planeNormal[0], p.planeNormal[1], p.planeNormal[2]);
			C.orthoBasis(normal, _basis);
			const arcRad = p.arc * C.DEG;
			const speedBase = p.speed.at(age);
			const last = Math.max(effect.count - 1, 1);
			for (let i = 0; i < effect.count; i++) {
				const life = C.EmissionMath.lifetime(effect.emission, rand, i);
				const local = age - C.EmissionMath.birth(effect.emission, rand, i, effect.count);
				if (local <= 0 || local >= life) continue;
				const baseAngle = p.even ? arcRad * i / last : arcRad * rand.unit(C.Streams.DIR, i);
				const angle = baseAngle + p.angleJitter * C.DEG * (rand.unit(C.Streams.PHASE, i) - 0.5);
				const dx = _basis[0] * Math.cos(angle) + _basis[3] * Math.sin(angle);
				const dy = _basis[1] * Math.cos(angle) + _basis[4] * Math.sin(angle);
				const dz = _basis[2] * Math.cos(angle) + _basis[5] * Math.sin(angle);
				const speed = speedBase * (1 + p.speedJitter * rand.signed(C.Streams.SPEED, i));
				const thickness = p.thickness * (rand.unit(C.Streams.RADIUS, i) - 0.5);
				const sx = origin[0] + dx * p.radius0 + normal[0] * thickness;
				const sy = origin[1] + dy * p.radius0 + normal[1] * thickness;
				const sz = origin[2] + dz * p.radius0 + normal[2] * thickness;
				C.posInto(_pos, sx, sy, sz, dx * speed, dy * speed, dz * speed,
					effect.physics.gravity, effect.physics.drag, effect.physics.wind, local);
				C.emit(effect, rand, i, age, local / life, _pos[0], _pos[1], _pos[2], out);
			}
		},
	});

	// 4. 喷泉
	STYLES.push({
		id: 'astrorbitsparticle:fountain',
		title: '喷泉',
		icon: 'fountain',
		accent: '#9ee7ff',
		summary: '持续向上喷洒的锥形流，靠重力回落',
		schema: schemaOf([
			vec('axis', 0, 1, 0, '喷射主方向'),
			num('spread', 18, '圆锥半角（度）', '0..90', 0, 90),
			num('speed', 8, '初速度（块/秒），可写含 t 的表达式，按出生时刻求值', '0..64', 0, 64),
			num('speedJitter', 0.12, '粒子之间的速度差异比例', '0..1', 0, 1),
			num('nozzleRadius', 0.1, '喷口半径（块），粒子在喷口球内取起点', '0..4', 0, 4),
		]),
		bind(b) {
			return {
				axis: b.vec3('axis'), spread: b.number('spread'), speed: b.num('speed'),
				speedJitter: b.number('speedJitter'), nozzleRadius: b.number('nozzleRadius'),
			};
		},
		sample(effect, origin, rand, age, out) {
			const p = effect.styleParams;
			const axis = C.vlen(p.axis[0], p.axis[1], p.axis[2]) < 1e-9 ? [0, 1, 0] : C.vnorm(p.axis[0], p.axis[1], p.axis[2]);
			const halfAngle = p.spread * C.DEG;
			for (let i = 0; i < effect.count; i++) {
				const birth = C.EmissionMath.birth(effect.emission, rand, i, effect.count);
				const local = age - birth;
				if (local <= 0) continue;
				const life = C.EmissionMath.lifetime(effect.emission, rand, i);
				if (local >= life) continue;
				C.coneInto(_dir, axis, halfAngle, rand, i);
				const speed = p.speed.at(birth) * (1 + p.speedJitter * rand.signed(C.Streams.SPEED, i));
				C.jitterInto(_off, p.nozzleRadius, rand, i);
				C.posInto(_pos, origin[0] + _off[0], origin[1] + _off[1], origin[2] + _off[2],
					_dir[0] * speed, _dir[1] * speed, _dir[2] * speed,
					effect.physics.gravity, effect.physics.drag, effect.physics.wind, local);
				C.emit(effect, rand, i, age, local / life, _pos[0], _pos[1], _pos[2], out);
			}
		},
	});

	// 5. 螺旋
	STYLES.push({
		id: 'astrorbitsparticle:spiral',
		title: '螺旋',
		icon: 'spiral',
		accent: '#8af0ff',
		summary: '绕轴旋转并上升的多股螺旋，可加径向扩散',
		schema: schemaOf([
			vec('axis', 0, 1, 0, '螺旋轴'),
			num('radius', 1.6, '旋转半径（块），可写含 t 的表达式', '0..32', 0, 32),
			num('radiusJitter', 0.04, '半径随机抖动比例', '0..1', 0, 1),
			num('angularSpeed', 180, '角速度（度/秒），可写表达式，负值反向旋转'),
			num('axialSpeed', 0.9, '轴向速度（块/秒），可写表达式'),
			num('phase', 0, '全局初相位（度）'),
			num('phaseJitter', 6, '每粒子相位抖动（度）', '0..90', 0, 90),
			int('strands', 1, '螺旋股数，2 即双螺旋', '1..16', 1, 16),
			num('scatter', 0, '径向随机扩散速度（块/秒）', '0..16', 0, 16),
			num('scatterGrowth', 1, '扩散随年龄的指数', '0..4', 0, 4),
		]),
		bind(b) {
			return {
				axis: b.vec3('axis'), radius: b.num('radius'), radiusJitter: b.number('radiusJitter'),
				angular: b.integral('angularSpeed'), axial: b.integral('axialSpeed'),
				phase: b.number('phase'), phaseJitter: b.number('phaseJitter'), strands: Math.max(1, b.int('strands')),
				scatter: b.number('scatter'), scatterGrowth: b.number('scatterGrowth'),
			};
		},
		sample(effect, origin, rand, age, out) {
			const p = effect.styleParams;
			const axis = C.vlen(p.axis[0], p.axis[1], p.axis[2]) < 1e-9 ? [0, 1, 0] : C.vnorm(p.axis[0], p.axis[1], p.axis[2]);
			C.orthoBasis(axis, _basis);
			const radiusBase = p.radius.at(age);
			const axial = p.axial.at(age);
			const strands = Math.max(1, p.strands);
			for (let i = 0; i < effect.count; i++) {
				const life = C.EmissionMath.lifetime(effect.emission, rand, i);
				const local = age - C.EmissionMath.birth(effect.emission, rand, i, effect.count);
				if (local <= 0 || local >= life) continue;
				const strand = i % strands;
				const baseAngle = (p.phase + 360 * strand / strands +
					p.phaseJitter * (rand.unit(C.Streams.PHASE, i) - 0.5)) * C.DEG;
				const angle = baseAngle + p.angular.at(local) * C.DEG;
				const radius = radiusBase * (1 + p.radiusJitter * rand.signed(C.Streams.RADIUS, i));
				const cos = Math.cos(angle) * radius;
				const sin = Math.sin(angle) * radius;
				const x = _basis[0] * cos + _basis[3] * sin;
				const y = _basis[1] * cos + _basis[4] * sin;
				const z = _basis[2] * cos + _basis[5] * sin;
				let sx = 0, sy = 0, sz = 0;
				if (p.scatter > 0) {
					C.sphereInto(_dir, rand, i);
					const distance = p.scatter * Math.pow(local / effect.duration, p.scatterGrowth);
					sx = _dir[0] * distance; sy = _dir[1] * distance; sz = _dir[2] * distance;
				}
				C.emit(effect, rand, i, age, local / life,
					origin[0] + x + axis[0] * axial + sx,
					origin[1] + y + axis[1] * axial + sy,
					origin[2] + z + axis[2] * axial + sz, out);
			}
		},
	});

	// 6. 波浪
	STYLES.push({
		id: 'astrorbitsparticle:wave',
		title: '波浪',
		icon: 'wave',
		accent: '#66e0ff',
		summary: '沿方向传播的正弦波带，可排成多行波面',
		schema: schemaOf([
			vec('travelAxis', 1, 0, 0, '波的传播方向'),
			num('amplitude', 0.9, '振幅（块），可写含 t 的表达式', '0..32', 0, 32),
			num('wavelength', 5, '波长（块）', '0.1..64', 0.1, 64),
			num('flowSpeed', 3, '粒子沿曲线流动的速度（块/秒）'),
			num('driftSpeed', 0, '整条曲线沿传播方向的平移速度（块/秒）'),
			num('phase', 0, '初相位（度）'),
			num('width', 16, '波带长度（块）', '0.1..256', 0.1, 256),
			num('depth', 1, '波带宽度（块），rows 大于 1 时生效', '0..64', 0, 64),
			int('rows', 1, '横向排数，大于 1 得到波面', '1..64', 1, 64),
			num('jitter', 0.05, '位置随机抖动（块）', '0..4', 0, 4),
		]),
		bind(b) {
			return {
				travelAxis: b.vec3('travelAxis'), amplitude: b.num('amplitude'), wavelength: b.number('wavelength'),
				flowSpeed: b.number('flowSpeed'), driftSpeed: b.number('driftSpeed'), phase: b.number('phase'),
				width: Math.max(1e-3, b.number('width')), depth: b.number('depth'), rows: Math.max(1, b.int('rows')),
				jitter: b.number('jitter'),
			};
		},
		sample(effect, origin, rand, age, out) {
			const p = effect.styleParams;
			const travel = C.vlen(p.travelAxis[0], p.travelAxis[1], p.travelAxis[2]) < 1e-9
				? [1, 0, 0] : C.vnorm(p.travelAxis[0], p.travelAxis[1], p.travelAxis[2]);
			let side = [travel[1] * 0 - travel[2] * 1, travel[2] * 0 - travel[0] * 0, travel[0] * 1 - travel[1] * 0];
			side = C.vlen(side[0], side[1], side[2]) < 1e-9 ? [0, 0, 1] : C.vnorm(side[0], side[1], side[2]);
			const rows = Math.max(1, p.rows);
			const cols = Math.max(1, Math.ceil(effect.count / rows));
			const amplitude = p.amplitude.at(age);
			const k = C.TAU / p.wavelength;
			for (let i = 0; i < effect.count; i++) {
				const life = C.EmissionMath.lifetime(effect.emission, rand, i);
				const local = age - C.EmissionMath.birth(effect.emission, rand, i, effect.count);
				if (local <= 0 || local >= life) continue;
				const row = i % rows;
				const col = Math.floor(i / rows);
				const s = col / Math.max(cols - 1, 1);
				const along = p.width * C.fract(s + p.flowSpeed * age / p.width);
				const rowPhase = rows > 1 ? Math.PI * row / (rows - 1) : 0;
				const y = amplitude * Math.sin(k * (along - p.driftSpeed * age) + p.phase * C.DEG + rowPhase);
				const rowDepth = rows > 1 ? p.depth * (row / (rows - 1) - 0.5) : 0;
				const jitter = p.jitter * rand.signed(C.Streams.JITTER, i);
				C.emit(effect, rand, i, age, local / life,
					origin[0] + travel[0] * along + side[0] * (rowDepth + jitter),
					origin[1] + y,
					origin[2] + travel[2] * along + side[2] * (rowDepth + jitter), out);
			}
		},
	});

	// 7. 彗星
	STYLES.push({
		id: 'astrorbitsparticle:comet',
		title: '彗星',
		icon: 'comet',
		accent: '#ffe9a8',
		summary: '一颗主体拖出衰减尾迹，整条轨迹连续求出',
		cyclicEmission: false,
		schema: schemaOf([
			vec('velocity', 0, 5, 0, '主体初速度（块/秒）'),
			num('headScale', 3, '主体尺寸相对 appearance.size 的倍数', '0.1..16', 0.1, 16),
			color('headColor', '主体颜色，缺省用 appearance.color'),
			int('tailCount', 90, '尾迹粒子数，总粒子数为 1 加该值', '0..1024', 0, 1024),
			num('tailLag', 0.9, '尾迹最长滞后时间（秒）', '0.01..16', 0.01, 16),
			num('tailSpread', 0.4, '最大扩散半径（块）', '0..16', 0, 16),
			num('tailSpreadGrowth', 1.6, '扩散随滞后的指数', '0..4', 0, 4),
			num('tailWobble', 0.06, '尾迹低频抖动幅度（块）', '0..4', 0, 4),
			num('tailFade', 1.4, '尾部亮度衰减指数', '0..8', 0, 8),
		]),
		bind(b) {
			return {
				velocity: b.vec3('velocity'), headScale: b.number('headScale'),
				headColor: b.colorOrNull('headColor') || 0,
				tailCount: Math.max(0, b.int('tailCount')), tailLag: b.number('tailLag'),
				tailSpread: b.number('tailSpread'), tailSpreadGrowth: b.number('tailSpreadGrowth'),
				tailWobble: b.number('tailWobble'), tailFade: b.number('tailFade'),
			};
		},
		particleCapacity(effect) { return 1 + effect.styleParams.tailCount; },
		stretchVector(effect, age) {
			const v = effect.styleParams.velocity;
			const g = effect.physics.gravity;
			return [v[0] + g[0] * age, v[1] + g[1] * age, v[2] + g[2] * age];
		},
		sample(effect, origin, rand, age, out) {
			const p = effect.styleParams;
			const u = Math.min(1, Math.max(0, age / effect.duration));
			C.posInto(_pos, origin[0], origin[1], origin[2], p.velocity[0], p.velocity[1], p.velocity[2],
				effect.physics.gravity, effect.physics.drag, effect.physics.wind, age);
			C.emit(effect, rand, 0, age, u, _pos[0], _pos[1], _pos[2], out, p.headScale, 1, p.headColor);
			for (let j = 1; j <= p.tailCount; j++) {
				const tau = p.tailLag * j / p.tailCount;
				const past = age - tau;
				if (past <= 0) continue;
				C.posInto(_pos, origin[0], origin[1], origin[2], p.velocity[0], p.velocity[1], p.velocity[2],
					effect.physics.gravity, effect.physics.drag, effect.physics.wind, past);
				const ratio = tau / p.tailLag;
				const spread = p.tailSpread * Math.pow(ratio, p.tailSpreadGrowth);
				C.sphereInto(_dir, rand, j);
				const phase0 = rand.unit(C.Streams.PHASE, j, 0) * C.TAU;
				const phase1 = rand.unit(C.Streams.PHASE, j, 1) * C.TAU;
				const phase2 = rand.unit(C.Streams.PHASE, j, 2) * C.TAU;
				const wobble = p.tailWobble;
				const alpha = Math.pow(Math.max(0, 1 - ratio), p.tailFade);
				C.emit(effect, rand, j, age, 0.35,
					_pos[0] + _dir[0] * spread + wobble * Math.sin(3.1 * age + phase0),
					_pos[1] + _dir[1] * spread + wobble * Math.sin(2.7 * age + phase1),
					_pos[2] + _dir[2] * spread + wobble * Math.sin(3.7 * age + phase2),
					out, 1, alpha);
			}
		},
	});

	// 8. 氛围
	STYLES.push({
		id: 'astrorbitsparticle:ambience',
		title: '氛围',
		icon: 'ambience',
		accent: '#b7c9ff',
		summary: '体积内散布的飘浮光点，可漂移、浮动、明灭',
		schema: schemaOf([
			vec('box', 18, 8, 18, '分布体积（块）'),
			texts('mode', ['drift', 'bob', 'flicker'], '行为列表：drift 随机游走、bob 上下浮动、flicker 明灭'),
			num('driftSpeed', 0.15, '漂移频率（Hz）', '0..4', 0, 4),
			num('driftAmplitude', 1.2, '漂移幅度（块）', '0..16', 0, 16),
			num('bobAmplitude', 0.5, '浮动幅度（块）', '0..16', 0, 16),
			num('bobPeriod', 7, '浮动周期（秒）', '0.1..120', 0.1, 120),
			num('flickerRate', 0.08, '明灭频率（Hz）', '0..4', 0, 4),
			num('visibleFraction', 0.65, '同时可见的粒子比例', '0..1', 0, 1),
		]),
		bind(b) {
			const modes = b.strings('mode').map((s) => s.toLowerCase());
			const fallback = modes.length === 0;
			return {
				box: b.vec3('box'),
				drift: fallback || modes.includes('drift'),
				bob: fallback || modes.includes('bob'),
				flicker: fallback || modes.includes('flicker'),
				driftSpeed: b.number('driftSpeed'), driftAmplitude: b.number('driftAmplitude'),
				bobAmplitude: b.number('bobAmplitude'), bobPeriod: Math.max(0.1, b.number('bobPeriod')),
				flickerRate: b.number('flickerRate'), visibleFraction: b.number('visibleFraction'),
			};
		},
		birthAge(effect, rand, index) {
			const emission = effect.emission;
			if (emission.mode !== 'burst') return C.EmissionMath.birth(emission, rand, index, effect.count);
			const start = emission.windowStart;
			const end = emission.windowEnd > start ? emission.windowEnd : effect.duration;
			return start + rand.unit(C.Streams.TIME, index, 10) * Math.max(0.05, end - start);
		},
		sample(effect, origin, rand, age, out) {
			const p = effect.styleParams;
			const emission = effect.emission;
			for (let i = 0; i < effect.count; i++) {
				const birth = this.birthAge(effect, rand, i);
				const life = C.EmissionMath.lifetime(emission, rand, i);
				const local = age - birth;
				if (local <= 0 || local >= life) continue;
				const u = local / life;
				const baseX = (rand.unit(C.Streams.JITTER, i, 0) - 0.5) * p.box[0];
				const baseY = (rand.unit(C.Streams.JITTER, i, 1) - 0.5) * p.box[1];
				const baseZ = (rand.unit(C.Streams.JITTER, i, 2) - 0.5) * p.box[2];
				let alphaScale = 1;
				if (p.flicker) {
					const gate = C.fract(rand.unit(C.Streams.TIME, i) + local * p.flickerRate);
					if (gate >= p.visibleFraction) continue;
					alphaScale = C.smoothstep(0, 0.08, gate) * C.smoothstep(0, 0.08, p.visibleFraction - gate);
				}
				let driftX = 0, driftY = 0, driftZ = 0;
				if (p.drift && p.driftAmplitude !== 0) {
					const f1 = p.driftSpeed * (0.6 + 0.8 * rand.unit(C.Streams.PHASE, i, 0));
					const f2 = p.driftSpeed * (0.6 + 0.8 * rand.unit(C.Streams.PHASE, i, 1));
					const f3 = p.driftSpeed * (0.6 + 0.8 * rand.unit(C.Streams.PHASE, i, 2));
					const p1 = rand.unit(C.Streams.PHASE, i, 3) * C.TAU;
					const p2 = rand.unit(C.Streams.PHASE, i, 4) * C.TAU;
					const p3 = rand.unit(C.Streams.PHASE, i, 5) * C.TAU;
					const amp = p.driftAmplitude;
					driftX = amp * Math.sin(C.TAU * f1 * local + p1);
					driftY = amp * Math.sin(C.TAU * f2 * local + p2);
					driftZ = amp * Math.sin(C.TAU * f3 * local + p3);
				}
				let bob = 0;
				if (p.bob && p.bobAmplitude !== 0) {
					const phase = rand.unit(C.Streams.PHASE, i, 6) * C.TAU;
					bob = p.bobAmplitude * Math.sin(C.TAU * local / p.bobPeriod + phase);
				}
				C.emit(effect, rand, i, age, u,
					origin[0] + baseX + driftX,
					origin[1] + baseY + driftY + bob,
					origin[2] + baseZ + driftZ, out, 1, alphaScale);
			}
		},
	});

	// 9. 雨
	STYLES.push({
		id: 'astrorbitsparticle:rain',
		title: '雨',
		icon: 'rain',
		accent: '#8fb8ff',
		summary: '体积内循环下落的雨丝，可按速度差分层',
		schema: schemaOf([
			vec('direction', 0.12, -1, 0, '下落方向，内部归一化'),
			numbers('speedRange', [14, 22], '速度区间 [最小, 最大]（块/秒），差速来源'),
			vec('area', 26, 18, 26, '降雨体积（块）'),
			num('streakLength', 0.8, '保留未使用；拖尾用 appearance.stretch', '0..10', 0, 10),
			int('trailCount', 0, '每条雨丝额外挂的尾迹粒子数', '0..16', 0, 16),
			num('trailSpread', 0.08, '尾迹逐段横向扩散（块）', '0..4', 0, 4),
			bool('wrap', true, '是否在体积内循环'),
		]),
		bind(b) {
			const range = b.floats('speedRange') || Float32Array.from([14, 22]);
			const lo = range.length > 0 ? range[0] : 14;
			const hi = range.length > 1 ? range[1] : 22;
			return {
				direction: b.vec3('direction'), speedMin: Math.min(lo, hi), speedMax: Math.max(lo, hi),
				area: b.vec3('area'), streakLength: b.number('streakLength'),
				trailCount: Math.max(0, b.int('trailCount')), trailSpread: b.number('trailSpread'), wrap: b.bool('wrap'),
			};
		},
		particleCapacity(effect) { return effect.count * (1 + effect.styleParams.trailCount); },
		stretchVector(effect, age) {
			const p = effect.styleParams;
			const dir = C.vlen(p.direction[0], p.direction[1], p.direction[2]) < 1e-9
				? [0, -1, 0] : C.vnorm(p.direction[0], p.direction[1], p.direction[2]);
			const speed = (p.speedMin + p.speedMax) * 0.5;
			const g = effect.physics.gravity;
			return [dir[0] * speed + g[0] * age, dir[1] * speed + g[1] * age, dir[2] * speed + g[2] * age];
		},
		sample(effect, origin, rand, age, out) {
			const p = effect.styleParams;
			const dir = C.vlen(p.direction[0], p.direction[1], p.direction[2]) < 1e-9
				? [0, -1, 0] : C.vnorm(p.direction[0], p.direction[1], p.direction[2]);
			const perDrop = 1 + p.trailCount;
			for (let drop = 0; drop < effect.count; drop++) {
				const life = C.EmissionMath.lifetime(effect.emission, rand, drop);
				const local = age - C.EmissionMath.birth(effect.emission, rand, drop, effect.count);
				if (local <= 0 || local >= life) continue;
				const speed = p.speedMin + (p.speedMax - p.speedMin) * rand.unit(C.Streams.SPEED, drop);
				const baseX = origin[0] + (rand.unit(C.Streams.JITTER, drop, 100) - 0.5) * p.area[0];
				const baseY = origin[1] + (rand.unit(C.Streams.JITTER, drop, 101) - 0.5) * p.area[1];
				const baseZ = origin[2] + (rand.unit(C.Streams.JITTER, drop, 102) - 0.5) * p.area[2];
				C.posInto(_pos, baseX, baseY, baseZ, dir[0] * speed, dir[1] * speed, dir[2] * speed,
					effect.physics.gravity, effect.physics.drag, effect.physics.wind, local);
				let x = _pos[0], y = _pos[1], z = _pos[2];
				if (p.wrap) {
					x = wrapPos(x, origin[0], p.area[0]);
					y = wrapPos(y, origin[1], p.area[1]);
					z = wrapPos(z, origin[2], p.area[2]);
				}
				for (let segment = 0; segment < perDrop; segment++) {
					const index = drop * perDrop + segment;
					const back = segment * 0.18;
					const spread = p.trailSpread * segment;
					const jx = spread > 0 ? spread * rand.signed(C.Streams.JITTER, index, 0) : 0;
					const jy = spread > 0 ? spread * rand.signed(C.Streams.JITTER, index, 1) : 0;
					const jz = spread > 0 ? spread * rand.signed(C.Streams.JITTER, index, 2) : 0;
					const alphaScale = 1 - 0.6 * segment / perDrop;
					C.emit(effect, rand, index, age, local / life,
						x - dir[0] * back + jx, y - dir[1] * back + jy, z - dir[2] * back + jz,
						out, 1, alphaScale, 0, drop);
				}
			}
		},
	});

	function wrapPos(value, center, size) {
		if (size <= 1e-4) return value;
		const offset = mod(value - center + size * 0.5, size);
		return center + offset - size * 0.5;
	}

	function mod(a, b) { const r = a % b; return r < 0 ? r + b : r; }

	const STYLE_BY_ID = new Map(STYLES.map((s) => [s.id, s]));

	function styleById(id) { return STYLE_BY_ID.get(id) || null; }

	function defaultParams(style) {
		const out = {};
		for (const spec of style.schema.params) {
			if (spec.kind === 'color') continue;
			if (spec.kind === 'vector') { out[spec.name] = spec.default.slice(); continue; }
			if (spec.kind === 'numberList') { out[spec.name] = spec.default.slice(); continue; }
			if (spec.kind === 'stringList') { out[spec.name] = spec.default.slice(); continue; }
			out[spec.name] = spec.default;
		}
		return out;
	}

	return {
		STYLES, STYLE_BY_ID, styleById, defaultParams,
		Binder, toVec, parseCurve, parseGradient, schemaOf,
		num, int, bool, vec, numbers, texts, color,
	};
});
