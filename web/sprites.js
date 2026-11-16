/*
 * sprites.js — 程序化粒子贴图（无 PNG 时的兜底，也是 astro/ 贴图的生成源）
 *
 * 原版粒子贴图大多是「中间接近不透明、边缘 1–2 像素衰减」的白图，
 * 这里按同样的观感画 6 种形状，尺寸 64×64，颜色纯白（上色在渲染时做）。
 *
 * 浏览器里 window.APShapes；Node 里 require('./sprites.js') 拿不到 canvas，
 * 生成 PNG 走 tools/gen-astro-textures.js（用 headless Chrome 的 canvas）。
 */
(function (root, factory) {
	if (typeof module === 'object' && module.exports) module.exports = factory();
	else root.APShapes = factory();
})(typeof self !== 'undefined' ? self : this, function () {
	'use strict';

	const SIZE = 64;

	// sprite 名 → 形状。命中顺序即优先级，未命中用 glow。
	const RULES = [
		[/glitter/, 'star'],
		[/spark|crit|firework|enchanted_hit/, 'spark'],
		[/splash|drop|bubble|rain|drip/, 'drop'],
		[/end_rod|rod|flame|smoke|scrape/, 'rod'],
		[/generic|cloud|dust|heart/, 'square'],
		[/glow|flash|note|portal/, 'glow'],
	];

	function shapeFor(sprite) {
		const name = String(sprite || '').toLowerCase();
		for (const [pattern, shape] of RULES) if (pattern.test(name)) return shape;
		return 'glow';
	}

	function makeSprite(shape, size) {
		const px = size || SIZE;
		const c = document.createElement('canvas');
		c.width = c.height = px;
		const g = c.getContext('2d');
		const k = px / 2;

		const radial = (stops, radiusScale) => {
			const grad = g.createRadialGradient(k, k, 0, k, k, k * (radiusScale || 1));
			for (const [offset, alpha] of stops) grad.addColorStop(offset, `rgba(255,255,255,${alpha})`);
			return grad;
		};

		if (shape === 'glow') {
			g.fillStyle = radial([[0, 1], [0.32, 0.98], [0.55, 0.62], [0.78, 0.2], [1, 0]]);
			g.fillRect(0, 0, px, px);
		} else if (shape === 'star') {
			for (let i = 0; i < 4; i++) {
				g.save();
				g.translate(k, k);
				g.rotate(i * Math.PI / 4);
				const arm = g.createLinearGradient(-k, 0, k, 0);
				arm.addColorStop(0, 'rgba(255,255,255,0)');
				arm.addColorStop(0.28, 'rgba(255,255,255,.55)');
				arm.addColorStop(0.5, 'rgba(255,255,255,1)');
				arm.addColorStop(0.72, 'rgba(255,255,255,.55)');
				arm.addColorStop(1, 'rgba(255,255,255,0)');
				g.fillStyle = arm;
				g.fillRect(-k, -k * 0.09, px, px * 0.18);
				g.restore();
			}
			g.fillStyle = radial([[0, 1], [0.4, 0.95], [0.7, 0.35], [1, 0]], 0.62);
			g.fillRect(0, 0, px, px);
		} else if (shape === 'spark') {
			g.fillStyle = radial([[0, 1], [0.42, 0.95], [0.72, 0.35], [1, 0]], 0.55);
			g.fillRect(0, 0, px, px);
			const streak = g.createLinearGradient(0, k, px, k);
			streak.addColorStop(0, 'rgba(255,255,255,0)');
			streak.addColorStop(0.32, 'rgba(255,255,255,.5)');
			streak.addColorStop(0.5, 'rgba(255,255,255,.9)');
			streak.addColorStop(0.68, 'rgba(255,255,255,.5)');
			streak.addColorStop(1, 'rgba(255,255,255,0)');
			g.fillStyle = streak;
			g.fillRect(0, k - k * 0.09, px, px * 0.18);
		} else if (shape === 'drop') {
			const drop = g.createRadialGradient(k, k * 0.78, 0, k, k, k * 0.92);
			drop.addColorStop(0, 'rgba(255,255,255,1)');
			drop.addColorStop(0.55, 'rgba(255,255,255,.92)');
			drop.addColorStop(0.85, 'rgba(255,255,255,.3)');
			drop.addColorStop(1, 'rgba(255,255,255,0)');
			g.fillStyle = drop;
			g.beginPath();
			g.ellipse(k, k, k * 0.46, k * 0.8, 0, 0, Math.PI * 2);
			g.fill();
		} else if (shape === 'rod') {
			g.save();
			g.beginPath();
			if (g.roundRect) g.roundRect(k * 0.22, k * 0.72, px - k * 0.44, k * 0.6, k * 0.3);
			else g.rect(k * 0.22, k * 0.72, px - k * 0.44, k * 0.6);
			g.clip();
			g.fillStyle = radial([[0, 1], [0.5, 0.95], [1, 0]]);
			g.fillRect(0, 0, px, px);
			g.restore();
			g.fillStyle = radial([[0, 0.85], [0.45, 0.5], [1, 0]], 0.7);
			g.fillRect(0, 0, px, px);
		} else {
			// square：接近原版 generic 贴图的柔边方块
			const pad = px * 0.14;
			g.save();
			g.beginPath();
			if (g.roundRect) g.roundRect(pad, pad, px - pad * 2, px - pad * 2, px * 0.16);
			else g.rect(pad, pad, px - pad * 2, px - pad * 2);
			g.clip();
			g.fillStyle = radial([[0, 1], [0.55, 0.95], [0.85, 0.55], [1, 0]], 1.9);
			g.fillRect(0, 0, px, px);
			g.restore();
		}
		return c;
	}

	return { SIZE, SHAPES: ['glow', 'star', 'spark', 'drop', 'rod', 'square'], shapeFor, makeSprite };
});
