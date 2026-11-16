/*
 * presets.js — 编辑器里的模板库
 *
 * category=test 的 9 条与 src/main/kotlin/net/astrorbits/server/TestPresets.kt 逐字节一致，
 * tools/golden-check.js 直接用它们和 golden/*.txt 对数。
 */
(function (root, factory) {
	if (typeof module === 'object' && module.exports) module.exports = factory();
	else root.APPresets = factory();
})(typeof self !== 'undefined' ? self : this, function () {
	'use strict';

	const TEST = [
		{
			id: 'radial', name: '星辉爆发', icon: 'radial', category: 'test', style: 'radial_burst',
			desc: '500 颗全向炸开，白→金→橙的年龄渐变',
			json: `{
  "style": "astrorbitsparticle:radial_burst",
  "duration": 3.0,
  "emission": {"count": 500, "lifetime": 2.0, "lifetimeJitter": 0.15},
  "physics": {"gravity": 0.0},
  "appearance": {
    "sprite": "minecraft:glitter_0",
    "size": 0.3,
    "color": "#FFD36E",
    "colorByAge": [[0, "#FFFFFF"], [0.3, "#FFD36E"], [1, "#FF5A1F"]],
    "alphaByAge": [[0, 1.0], [0.7, 0.9], [1, 0.0]]
  },
  "visibility": {"requireScene": false, "viewDistance": 128.0},
  "params": {"speed": 8.0, "speedJitter": 0.15}
}`,
		},
		{
			id: 'spherical', name: '球状扩散', icon: 'sphere', category: 'test', style: 'spherical_burst',
			desc: '实心球扩散并轻微下坠，冷蓝色',
			json: `{
  "style": "astrorbitsparticle:spherical_burst",
  "duration": 3.0,
  "emission": {"count": 700, "lifetime": 2.4, "lifetimeJitter": 0.2},
  "physics": {"gravity": -2.5, "drag": 0.15},
  "appearance": {
    "sprite": "minecraft:glow",
    "size": 0.32,
    "color": "#7FD4FF",
    "alphaByAge": [[0, 0.9], [0.5, 0.6], [1, 0.0]]
  },
  "visibility": {"requireScene": false, "viewDistance": 128.0},
  "params": {"speed": 5.5, "fill": 1.0, "flatten": 0.85}
}`,
		},
		{
			id: 'ring', name: '紫环爆', icon: 'ring', category: 'test', style: 'ring_burst',
			desc: '水平圆环水平扩张，带一圈厚度',
			json: `{
  "style": "astrorbitsparticle:ring_burst",
  "duration": 3.0,
  "emission": {"count": 400, "lifetime": 2.4, "lifetimeJitter": 0.1},
  "physics": {"gravity": -1.5},
  "appearance": {
    "sprite": "minecraft:spark_0",
    "size": 0.24,
    "color": "#B388FF",
    "alphaByAge": [[0, 1.0], [0.75, 0.8], [1, 0.0]]
  },
  "visibility": {"requireScene": false, "viewDistance": 128.0},
  "params": {"planeNormal": [0, 1, 0], "speed": 8.0, "thickness": 0.2}
}`,
		},
		{
			id: 'fountain', name: '喷泉', icon: 'fountain', category: 'test', style: 'fountain',
			desc: '流式发射的锥形水柱，重力让粒子回落',
			json: `{
  "style": "astrorbitsparticle:fountain",
  "duration": 8.0,
  "emission": {
    "mode": "stream",
    "count": 400,
    "window": [0.0, 6.0],
    "lifetime": 2.0,
    "lifetimeJitter": 0.12
  },
  "physics": {"gravity": -9.8},
  "appearance": {
    "sprite": "minecraft:splash_0",
    "size": 0.18,
    "color": "#9EE7FF",
    "alphaByAge": [[0, 0.9], [0.8, 0.7], [1, 0.0]]
  },
  "visibility": {"requireScene": false, "viewDistance": 128.0},
  "params": {"axis": [0, 1, 0], "spread": 12.0, "speed": 8.0}
}`,
		},
		{
			id: 'spiral', name: '双螺旋', icon: 'spiral', category: 'test', style: 'spiral',
			desc: '两股粒子绕轴旋转成环，随时间整体上升',
			json: `{
  "style": "astrorbitsparticle:spiral",
  "duration": 6.0,
  "emission": {"count": 1000, "lifetime": 5.0, "lifetimeJitter": 0.06},
  "physics": {"gravity": 0.0},
  "appearance": {
    "sprite": "minecraft:end_rod",
    "size": 0.16,
    "color": "#8AF0FF",
    "alphaByAge": [[0, 0.0], [0.08, 1.0], [0.75, 0.9], [1, 0.0]]
  },
  "visibility": {"requireScene": false, "viewDistance": 128.0},
  "params": {
    "axis": [0, 1, 0],
    "radius": 1.8,
    "angularSpeed": 170.0,
    "axialSpeed": 1.1,
    "strands": 2,
    "phaseJitter": 2.0,
    "scatter": 0.15,
    "scatterGrowth": 1.5
  }
}`,
		},
		{
			id: 'wave', name: '波面', icon: 'wave', category: 'test', style: 'wave',
			desc: '三行粒子组成的正弦波面，整体向前推进',
			json: `{
  "style": "astrorbitsparticle:wave",
  "duration": 8.0,
  "emission": {"count": 400, "lifetime": 8.0, "lifetimeJitter": 0.0},
  "physics": {"gravity": 0.0},
  "appearance": {
    "sprite": "minecraft:glow",
    "size": 0.22,
    "color": "#66E0FF",
    "alphaByAge": [[0, 0.0], [0.1, 0.9], [0.9, 0.9], [1, 0.0]]
  },
  "visibility": {"requireScene": false, "viewDistance": 128.0},
  "params": {
    "travelAxis": [1, 0, 0],
    "amplitude": 1.2,
    "wavelength": 6.0,
    "flowSpeed": 4.0,
    "driftSpeed": 4.0,
    "width": 18.0,
    "rows": 3,
    "depth": 4.0
  }
}`,
		},
		{
			id: 'comet', name: '彗星', icon: 'comet', category: 'test', style: 'comet',
			desc: '主体下坠，100 段尾迹按滞后展开',
			json: `{
  "style": "astrorbitsparticle:comet",
  "duration": 4.0,
  "emission": {"count": 1, "lifetime": 4.0, "lifetimeJitter": 0.0},
  "physics": {"gravity": -6.0},
  "appearance": {
    "sprite": "minecraft:glow",
    "size": 0.3,
    "color": "#FFE9A8",
    "alphaByAge": [[0, 1.0], [0.85, 1.0], [1, 0.0]]
  },
  "visibility": {"requireScene": false, "viewDistance": 128.0},
  "params": {
    "velocity": [0, 3, 10],
    "headScale": 3.5,
    "headColor": "#FFFFFF",
    "tailCount": 100,
    "tailLag": 0.8,
    "tailSpread": 0.35
  }
}`,
		},
		{
			id: 'ambience', name: '夜场氛围', icon: 'ambience', category: 'test', style: 'ambience',
			desc: '16×8×16 体积内的飘浮光尘，明灭闪烁',
			json: `{
  "style": "astrorbitsparticle:ambience",
  "duration": 15.0,
  "emission": {"count": 160, "window": [0.0, 6.0], "lifetime": 15.0, "lifetimeJitter": 0.0},
  "physics": {"gravity": 0.0},
  "appearance": {
    "sprite": "minecraft:glow",
    "size": 0.12,
    "sizeJitter": 0.35,
    "color": "#B7C9FF",
    "alphaByAge": [[0, 0.0], [0.05, 1.0], [0.95, 1.0], [1, 0.0]]
  },
  "visibility": {"requireScene": false, "viewDistance": 128.0},
  "params": {
    "box": [16, 8, 16],
    "mode": ["drift", "bob", "flicker"],
    "driftSpeed": 0.15,
    "driftAmplitude": 0.4,
    "bobAmplitude": 0.35,
    "flickerRate": 0.12,
    "visibleFraction": 0.5
  }
}`,
		},
		{
			id: 'rain', name: '雨幕', icon: 'rain', category: 'test', style: 'rain',
			desc: '20×14×20 体积内循环下落，速度差分层',
			json: `{
  "style": "astrorbitsparticle:rain",
  "duration": 15.0,
  "emission": {"count": 800, "lifetime": 15.0, "lifetimeJitter": 0.0},
  "physics": {"gravity": 0.0},
  "appearance": {
    "sprite": "minecraft:splash_0",
    "size": 0.14,
    "color": "#8FB8FF",
    "alphaByAge": [[0, 0.0], [0.05, 0.55], [0.95, 0.55], [1, 0.0]]
  },
  "visibility": {"requireScene": false, "viewDistance": 128.0},
  "params": {
    "direction": [0.12, -1, 0.0],
    "speedRange": [14.0, 22.0],
    "area": [20, 14, 20]
  }
}`,
		},
	];

	// 演唱会模板：套用现成参数，改颜色/规模就能用
	const SHOW = [
		{
			id: 'show-golden-fountain', name: '金色喷泉', icon: 'fountain', category: 'show', style: 'fountain',
			desc: '带拉伸与金色渐变的喷泉，适合舞台中轴',
			json: `{
  "style": "astrorbitsparticle:fountain",
  "duration": 10.0,
  "loop": true,
  "emission": {"mode": "stream", "count": 600, "window": [0.0, 8.0], "lifetime": 2.4, "lifetimeJitter": 0.2},
  "physics": {"gravity": -12.0, "drag": 0.1},
  "appearance": {
    "sprite": "minecraft:glow",
    "size": 0.22,
    "sizeByAge": [[0, 1.0], [1, 0.4]],
    "color": "#FFB347",
    "colorByAge": [[0, "#FFF3C4"], [0.4, "#FFC84D"], [1, "#FF6B1A"]],
    "alphaByAge": [[0, 1.0], [0.8, 0.8], [1, 0.0]],
    "stretch": 0.4
  },
  "visibility": {"requireScene": false, "viewDistance": 128.0},
  "params": {"axis": [0, 1, 0], "spread": 10.0, "speed": 13.0, "nozzleRadius": 0.4}
}`,
		},
		{
			id: 'show-double-helix', name: '旋转光环', icon: 'spiral', category: 'show', style: 'spiral',
			desc: '四股粒子在圆环上错相旋转并上升，带轨迹',
			json: `{
  "style": "astrorbitsparticle:spiral",
  "duration": 8.0,
  "loop": true,
  "emission": {"mode": "stream", "count": 1200, "window": [0.0, 6.0], "lifetime": 3.0, "lifetimeJitter": 0.1},
  "physics": {"gravity": 0.0},
  "appearance": {
    "sprite": "minecraft:end_rod",
    "size": 0.18,
    "color": "#66E0FF",
    "alphaByAge": [[0, 0.0], [0.1, 1.0], [0.8, 0.9], [1, 0.0]]
  },
  "trail": {"mode": "time", "interval": 0.05, "cap": 6, "sizeScale": 0.75, "alphaScale": 0.6},
  "visibility": {"requireScene": false, "viewDistance": 128.0},
  "params": {
    "axis": [0, 1, 0],
    "radius": "2.2*(1-0.35*u)",
    "angularSpeed": 150.0,
    "axialSpeed": 1.6,
    "strands": 4,
    "phaseJitter": 3.0
  }
}`,
		},
		{
			id: 'show-impact-rain', name: '雨幕落地', icon: 'rain', category: 'show', style: 'rain',
			desc: '带地面碰撞溅射的雨，打开预览地面可见',
			json: `{
  "style": "astrorbitsparticle:rain",
  "duration": 20.0,
  "loop": true,
  "emission": {"mode": "stream", "count": 700, "window": [0.0, 16.0], "lifetime": 4.0, "lifetimeJitter": 0.1},
  "physics": {"gravity": -4.0},
  "appearance": {
    "sprite": "minecraft:splash_0",
    "size": 0.16,
    "color": "#8FB8FF",
    "alphaByAge": [[0, 0.0], [0.06, 0.7], [0.94, 0.7], [1, 0.0]],
    "stretch": 0.12
  },
  "splash": {"enabled": true, "color": "#BBD8FF", "count": 4, "size": 0.1, "speed": 1.6, "life": 0.5},
  "visibility": {"requireScene": false, "viewDistance": 128.0},
  "params": {"direction": [0.15, -1, 0.0], "speedRange": [10.0, 16.0], "area": [24, 12, 24], "wrap": true}
}`,
		},
		{
			id: 'show-burst-stack', name: '三层环爆', icon: 'ring', category: 'show', style: 'ring_burst',
			desc: '大半径扇环向外扩张，叠金色渐变',
			json: `{
  "style": "astrorbitsparticle:ring_burst",
  "duration": 2.4,
  "emission": {"count": 900, "lifetime": 1.8, "lifetimeJitter": 0.25},
  "physics": {"gravity": -2.0, "drag": 0.4},
  "appearance": {
    "sprite": "minecraft:spark_0",
    "size": 0.26,
    "color": "#FFD36E",
    "colorByAge": [[0, "#FFFFFF"], [0.35, "#FFC84D"], [1, "#C2410C"]],
    "alphaByAge": [[0, 1.0], [0.6, 0.85], [1, 0.0]]
  },
  "visibility": {"requireScene": false, "viewDistance": 160.0},
  "params": {
    "planeNormal": [0, 1, 0],
    "speed": 11.0,
    "thickness": 0.6,
    "radius0": 2.0,
    "even": true,
    "angleJitter": 0.6,
    "arc": 360.0
  }
}`,
		},
	];

	return { PRESETS: TEST.concat(SHOW), TEST, SHOW };
});
