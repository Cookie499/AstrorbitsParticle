package com.example.astrorbitsparticle.api

import com.example.astrorbitsparticle.model.EffectDefinition
import com.example.astrorbitsparticle.server.ServerEffects
import com.example.astrorbitsparticle.style.ParticleStyle
import com.example.astrorbitsparticle.style.ParticleStyles
import com.example.astrorbitsparticle.style.StyleSchema
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3

/** 供其他模组读取的活跃特效信息。 */
data class EffectInfo(
	val id: Long,
	val style: Identifier,
	val origin: Vec3,
	val dimension: Identifier,
	val startTick: Long,
	val subscriberCount: Int,
)

/** 供其他模组读取的场景信息。 */
data class SceneInfo(
	val id: String,
	val dimension: Identifier,
	val min: Vec3,
	val max: Vec3,
	val viewDistance: Float,
)

/**
 * 其他模组调用 AstrorbitsParticle 的入口。
 *
 * 所有方法都必须在服务端线程调用；客户端线程只会读取场景与定义的快照。
 */
object AstrorbitsApi {
	const val API_VERSION: Int = 1

	@JvmStatic
	fun parseDefinition(json: String): Result<EffectDefinition> = EffectDefinition.parse(json)

	/** Java 调用方使用，避免处理 Kotlin 的 Result。 */
	@JvmStatic
	fun parseDefinitionOrNull(json: String): EffectDefinition? = EffectDefinition.parse(json).getOrNull()

	/** 读取 config/astrorbitsparticle/effects 下已加载的定义。 */
	@JvmStatic
	fun definition(id: Identifier): EffectDefinition? = ServerEffects.findDefinition(id)

	/**
	 * 用已加载的定义 id 生成特效，适合“播放服主配置好的特效”这种场景。
	 */
	@JvmStatic
	fun spawnConfigured(
		definitionId: Identifier,
		level: ServerLevel,
		origin: Vec3,
		scale: Float,
		seed: Long?,
	): Long? = definition(definitionId)?.let { spawn(it, level, origin, scale, seed) }

	@JvmStatic
	fun spawnConfigured(definitionId: Identifier, level: ServerLevel, origin: Vec3): Long? =
		spawnConfigured(definitionId, level, origin, 1f, null)

	/**
	 * 在以 [origin] 为原点的位置生成特效。
	 *
	 * @param seed 指定随机种子以便复现同一段画面；传 null 用服务端随机数。
	 * @return 特效 id；定义校验失败时返回 null。
	 */
	@JvmStatic
	fun spawn(
		definition: EffectDefinition,
		level: ServerLevel,
		origin: Vec3,
		scale: Float,
		seed: Long?,
	): Long? = ServerEffects.play(definition, level, origin, scale, seed)?.id

	@JvmStatic
	fun spawn(definition: EffectDefinition, level: ServerLevel, origin: Vec3): Long? =
		spawn(definition, level, origin, 1f, null)

	@JvmStatic
	fun spawnJson(json: String, level: ServerLevel, origin: Vec3, scale: Float, seed: Long?): Long? =
		parseDefinition(json).getOrNull()?.let { spawn(it, level, origin, scale, seed) }

	@JvmStatic
	fun spawnJson(json: String, level: ServerLevel, origin: Vec3): Long? =
		spawnJson(json, level, origin, 1f, null)

	@JvmStatic
	fun stop(effectId: Long): Boolean = ServerEffects.stop(effectId)

	@JvmStatic
	fun stopAll() = ServerEffects.stopAll()

	@JvmStatic
	fun activeEffects(): List<EffectInfo> = ServerEffects.list().map {
		EffectInfo(
			id = it.id,
			style = it.definition.style,
			origin = it.origin,
			dimension = it.dimension.identifier(),
			startTick = it.startTick,
			subscriberCount = it.subscribers.size,
		)
	}

	@JvmStatic
	fun scenes(): List<SceneInfo> = ServerEffects.scenes().map {
		SceneInfo(it.id, it.dimension.identifier(), it.min, it.max, it.viewDistance)
	}

	@JvmStatic
	fun definitions(): Set<Identifier> = ServerEffects.definitions().keys

	/**
	 * 注册自定义样式。id 已被占用时返回 false，不会覆盖已有样式。
	 * 样式必须同时装到客户端，否则客户端会丢弃使用该样式的特效。
	 */
	@JvmStatic
	fun registerStyle(style: ParticleStyle): Boolean = ParticleStyles.registerIfAbsent(style)

	@JvmStatic
	fun styles(): List<StyleSchema> = ParticleStyles.all().map { it.schema }

	@JvmStatic
	fun style(id: String): StyleSchema? = ParticleStyles.getOrNull(id)?.schema
}