package net.astrorbits.model

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3

enum class EmissionMode {
	BURST, STREAM, RANDOM;

	companion object {
		fun parse(raw: String): EmissionMode =
			entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: BURST
	}
}

enum class Blend {
	ADDITIVE, ALPHA;

	companion object {
		fun parse(raw: String): Blend =
			entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: ADDITIVE
	}
}

/**
 * 多贴图变体的选择方式：`PARTICLE` 每颗粒子各取一张（与原版 SpriteSet 一致），
 * `EFFECT` 整个特效按 seed 共用一张，`CYCLE` 按游戏时间在变体间轮换。
 */
enum class SpriteVariation {
	PARTICLE, EFFECT, CYCLE;

	companion object {
		fun parse(raw: String): SpriteVariation =
			entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: PARTICLE
	}
}

/** 轨迹回溯方式：`TIME` 按固定时间间隔，`DISTANCE` 按每颗粒子沿路径走过的距离。 */
enum class TrailMode {
	TIME, DISTANCE;

	companion object {
		fun parse(raw: String): TrailMode =
			entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: TIME
	}
}

/**
 * 轨迹粒子参数。写 `trail` 对象即启用。
 *
 * `interval` 只用于 [TrailMode.TIME]，`distance` 与 `lag` 只用于 [TrailMode.DISTANCE]。
 */
data class Trail(
	val mode: TrailMode = TrailMode.TIME,
	/** time 模式每段的回溯间隔（秒）。 */
	val interval: Float = 0.1f,
	/** distance 模式每段沿路径的距离（块）。 */
	val distance: Float = 0.5f,
	/** distance 模式最长回溯时间（秒）。 */
	val lag: Float = 1f,
	/** 最多几段，不含源粒子。 */
	val cap: Int = 8,
	/** 第 k 段相对源粒子的横向随机偏移半径（块），取 spread * k / cap。 */
	val spread: Float = 0f,
	/** 逐段尺寸倍率，第 k 段乘 sizeScale^k。 */
	val sizeScale: Float = 0.8f,
	/** 逐段不透明度倍率，第 k 段乘 alphaScale^k。 */
	val alphaScale: Float = 0.7f,
) {
	companion object {
		const val MAX_CAP = 32
	}
}

/** 子特效触发方式：定义结束、单个粒子消失，或客户端报告粒子碰撞。 */
enum class SubEffectTrigger {
	END, IMPACT, PARTICLE_END;

	companion object {
		fun parse(raw: String): SubEffectTrigger {
			val normalized = raw.replace('-', '_')
			return entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) } ?: END
		}
	}
}

/**
 * 特效朝向：`WORLD` 使用世界坐标；`VIEW` 把局部 +Z 对齐触发时的视线方向，
 * 整个特效绕原点旋转，之后不再跟随玩家。
 */
enum class Orientation {
	WORLD, VIEW;

	companion object {
		fun parse(raw: String): Orientation =
			entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: WORLD
	}
}

data class Emission(
	val mode: EmissionMode = EmissionMode.BURST,
	val count: Int = 200,
	val windowStart: Float = 0f,
	val windowEnd: Float = 0f,
	val jitter: Float = 0f,
	val lifetime: Float = 4f,
	val lifetimeJitter: Float = 0.1f,
)

data class Physics(
	val gravity: Vec3 = Vec3(0.0, -9.8, 0.0),
	val drag: Float = 0f,
	val wind: Vec3 = Vec3.ZERO,
)

/**
 * 外观字段保留原始 JSON，绑定期才编译表达式；`null` 表示用样式默认值。
 */
data class Appearance(
	val sprite: Identifier = Identifier.withDefaultNamespace("generic_0"),
	/** 原版粒子定义 id（`particles/<path>.json`）里 `textures` 有多张时，逐粒子还是逐特效选。 */
	val spriteVariation: SpriteVariation = SpriteVariation.PARTICLE,
	val spriteCycleTicks: Int = 2,
	val size: JsonElement? = null,
	val sizeJitter: Float = 0.15f,
	val sizeByAge: JsonElement? = null,
	val color: JsonElement? = null,
	val colorJitter: Float = 0f,
	val colorByAge: JsonElement? = null,
	val alphaByAge: JsonElement? = null,
	val blend: Blend = Blend.ADDITIVE,
	val fullbright: Boolean = true,
	val stretch: JsonElement? = null,
	val spin: JsonElement? = null,
)

data class Visibility(
	val viewDistance: Float = 96f,
	val requireScene: Boolean = true,
	val boundsPadding: Float = 1f,
)

data class EffectDefinition(
	val style: Identifier,
	val duration: Float,
	val loop: Boolean,
	val orientation: Orientation,
	val reverse: Boolean,
	val timeScale: Float,
	val subEffect: Identifier?,
	val subEffectTrigger: SubEffectTrigger,
	val emission: Emission,
	val physics: Physics,
	val appearance: Appearance,
	val trail: Trail?,
	val visibility: Visibility,
	val params: JsonObject,
	val source: String,
) {
	fun toJson(): String = source

	companion object {
		const val MAX_COUNT = 8192
		const val MAX_DURATION = 3600f

		fun parse(json: String): Result<EffectDefinition> = runCatching {
			val root = JsonParser.parseString(json)
			require(root.isJsonObject) { "definition is not a JSON object" }
			val obj = root.asJsonObject

			val style = Identifier.tryParse(obj.string("style", "astrorbitsparticle:radial_burst"))
				?: error("invalid style id")
			val duration = obj.float("duration", 4f).coerceIn(0.05f, MAX_DURATION)
			val loop = obj.bool("loop", false)
			val orientation = Orientation.parse(obj.string("orientation", "world"))
			val reverse = obj.bool("reverse", false)
			val timeScale = obj.float("timeScale", 1f).coerceIn(-8f, 8f)
			val subEffect = obj.get("subEffect")?.takeIf { it.isJsonPrimitive }?.asString
				?.takeIf { it.isNotEmpty() }?.let { Identifier.tryParse(it) }
			val subEffectTrigger = SubEffectTrigger.parse(obj.string("subEffectTrigger", "end"))

			val emission = obj.objectOrNull("emission")?.let { parseEmission(it, duration) }
				?: parseEmission(JsonObject(), duration)
			val physics = obj.objectOrNull("physics")?.let(::parsePhysics) ?: Physics()
			val appearance = obj.objectOrNull("appearance")?.let(::parseAppearance) ?: Appearance()
			val trail = obj.objectOrNull("trail")?.let(::parseTrail)
			val visibility = obj.objectOrNull("visibility")?.let(::parseVisibility) ?: Visibility()
			val params = obj.objectOrNull("params") ?: JsonObject()

			EffectDefinition(
				style, duration, loop, orientation, reverse, timeScale, subEffect, subEffectTrigger,
				emission, physics, appearance, trail, visibility, params, json,
			)
		}

		private fun parseEmission(obj: JsonObject, duration: Float): Emission {
			val window = obj.getAsJsonArray("window")
			return Emission(
				mode = EmissionMode.parse(obj.string("mode", "burst")),
				count = obj.int("count", 200)
					.coerceIn(1, net.astrorbits.config.ModConfig.maxParticlesPerEffect),
				windowStart = window?.getOrNull(0)?.asFloat ?: 0f,
				windowEnd = window?.getOrNull(1)?.asFloat ?: 0f,
				jitter = obj.float("jitter", 0f),
				lifetime = obj.float("lifetime", duration),
				lifetimeJitter = obj.float("lifetimeJitter", 0.1f),
			)
		}

		private fun parsePhysics(obj: JsonObject): Physics = Physics(
			gravity = obj.vec3("gravity", Vec3(0.0, -9.8, 0.0)),
			drag = obj.float("drag", 0f),
			wind = obj.vec3("wind", Vec3.ZERO),
		)

		private fun parseAppearance(obj: JsonObject): Appearance = Appearance(
			sprite = Identifier.tryParse(obj.string("sprite", "minecraft:generic_0"))
				?: Identifier.withDefaultNamespace("generic_0"),
			spriteVariation = SpriteVariation.parse(obj.string("spriteVariation", "particle")),
			spriteCycleTicks = obj.int("spriteCycleTicks", 2).coerceIn(1, 200),
			size = obj.get("size"),
			sizeJitter = obj.float("sizeJitter", 0.15f),
			sizeByAge = obj.get("sizeByAge"),
			color = obj.get("color"),
			colorJitter = obj.float("colorJitter", 0f),
			colorByAge = obj.get("colorByAge"),
			alphaByAge = obj.get("alphaByAge"),
			blend = Blend.parse(obj.string("blend", "additive")),
			fullbright = obj.bool("fullbright", true),
			stretch = obj.get("stretch"),
			spin = obj.get("spin"),
		)

		private fun parseTrail(obj: JsonObject): Trail? {
			if (!obj.bool("enabled", true)) return null
			return Trail(
				mode = TrailMode.parse(obj.string("mode", "time")),
				interval = obj.float("interval", 0.1f).coerceIn(0.01f, 10f),
				distance = obj.float("distance", 0.5f).coerceIn(0.01f, 16f),
				lag = obj.float("lag", 1f).coerceIn(0.01f, 16f),
				cap = obj.int("cap", 8).coerceIn(1, Trail.MAX_CAP),
				spread = obj.float("spread", 0f).coerceIn(0f, 8f),
				sizeScale = obj.float("sizeScale", 0.8f).coerceIn(0f, 1f),
				alphaScale = obj.float("alphaScale", 0.7f).coerceIn(0f, 1f),
			)
		}

		private fun parseVisibility(obj: JsonObject): Visibility = Visibility(
			viewDistance = obj.float("viewDistance", 96f).coerceIn(8f, 512f),
			requireScene = obj.bool("requireScene", true),
			boundsPadding = obj.float("boundsPadding", 1f).coerceIn(0f, 64f),
		)
	}
}

internal fun JsonObject.objectOrNull(key: String): JsonObject? =
	get(key)?.takeIf { it.isJsonObject }?.asJsonObject

internal fun JsonObject.string(key: String, default: String): String =
	get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: default

internal fun JsonObject.float(key: String, default: Float): Float =
	get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asFloat ?: default

internal fun JsonObject.int(key: String, default: Int): Int =
	get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: default

internal fun JsonObject.bool(key: String, default: Boolean): Boolean =
	get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: default

internal fun JsonObject.vec3(key: String, default: Vec3): Vec3 {
	val element = get(key) ?: return default
	if (element.isJsonPrimitive && element.asJsonPrimitive.isNumber) {
		return Vec3(0.0, element.asDouble, 0.0)
	}
	val array = element as? JsonArray ?: return default
	if (array.size() < 3) return default
	return Vec3(array[0].asDouble, array[1].asDouble, array[2].asDouble)
}

internal fun JsonArray.getOrNull(index: Int): JsonElement? = if (index < size()) get(index) else null
