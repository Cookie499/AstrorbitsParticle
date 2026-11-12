package com.example.astrorbitsparticle.server

import com.example.astrorbitsparticle.model.EffectDefinition
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3

/**
 * 内置测试预设：不读配置文件，`requireScene` 关闭，便于在任意位置验证样式。
 */
object TestPresets {
	private val presets: Map<String, String> = linkedMapOf(
		"radial" to """
			{
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
			}
		""".trimIndent(),

		"spherical" to """
			{
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
			}
		""".trimIndent(),

		"ring" to """
			{
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
			}
		""".trimIndent(),

		"fountain" to """
			{
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
			}
		""".trimIndent(),

		"spiral" to """
			{
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
			}
		""".trimIndent(),

		"wave" to """
			{
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
			}
		""".trimIndent(),

		"comet" to """
			{
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
			}
		""".trimIndent(),

		"ambience" to """
			{
			  "style": "astrorbitsparticle:ambience",
			  "duration": 15.0,
			  "emission": {"count": 160, "lifetime": 15.0, "lifetimeJitter": 0.0},
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
			}
		""".trimIndent(),

		"rain" to """
			{
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
			}
		""".trimIndent(),
	)

	private val parsed = HashMap<String, EffectDefinition>()

	fun names(): Set<String> = presets.keys

	fun resolve(name: String): EffectDefinition? = parsed.getOrPut(name) {
		val json = presets[name] ?: return null
		return EffectDefinition.parse(json).getOrElse {
			com.example.astrorbitsparticle.AstrorbitsParticle.logger.warn(
				"[effect] invalid test preset {}: {}", name, it.message,
			)
			return null
		}
	}

	fun play(name: String, level: ServerLevel, origin: Vec3): ServerEffect? {
		val definition = resolve(name) ?: return null
		return ServerEffects.play(definition, level, origin)
	}
}
