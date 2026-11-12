package com.example.astrorbitsparticle.model

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
	val reverse: Boolean,
	val emission: Emission,
	val physics: Physics,
	val appearance: Appearance,
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
			val reverse = obj.bool("reverse", false)

			val emission = obj.objectOrNull("emission")?.let { parseEmission(it, duration) }
				?: parseEmission(JsonObject(), duration)
			val physics = obj.objectOrNull("physics")?.let(::parsePhysics) ?: Physics()
			val appearance = obj.objectOrNull("appearance")?.let(::parseAppearance) ?: Appearance()
			val visibility = obj.objectOrNull("visibility")?.let(::parseVisibility) ?: Visibility()
			val params = obj.objectOrNull("params") ?: JsonObject()

			EffectDefinition(style, duration, loop, reverse, emission, physics, appearance, visibility, params, json)
		}

		private fun parseEmission(obj: JsonObject, duration: Float): Emission {
			val window = obj.getAsJsonArray("window")
			return Emission(
				mode = EmissionMode.parse(obj.string("mode", "burst")),
				count = obj.int("count", 200)
					.coerceIn(1, com.example.astrorbitsparticle.config.ModConfig.maxParticlesPerEffect),
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
