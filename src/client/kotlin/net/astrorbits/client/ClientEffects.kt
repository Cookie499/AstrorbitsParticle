package net.astrorbits.client

import net.astrorbits.AstrorbitsParticle
import net.astrorbits.model.EffectDefinition
import net.astrorbits.model.SubEffectTrigger
import net.astrorbits.net.EffectClearPayload
import net.astrorbits.net.EffectHandshakePayload
import net.astrorbits.net.EffectImpactPayload
import net.astrorbits.net.EffectStartPayload
import net.astrorbits.net.EffectStopPayload
import net.astrorbits.net.PROTOCOL_VERSION
import net.astrorbits.net.RNG_VERSION
import net.astrorbits.particle.EffectAnchorOptions
import net.astrorbits.style.BoundEffect
import net.astrorbits.style.EffectBinder
import net.astrorbits.style.ParticleBuffer
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

private const val IMPACT_CHECKS_PER_TICK = 128
private const val MAX_IMPACT_REPORTS = 8

class ClientEffect(
	val id: Long,
	val bound: BoundEffect,
	val seed: Long,
	val origin: Vec3,
	val scale: Float,
	private val baseAge: Float,
	private val receivedNanos: Long,
) {
	var age: Float = baseAge
		private set

	val buffer = ParticleBuffer()
	private val impactBuffer = ParticleBuffer()
	private val impactedBlocks = HashSet<Long>()
	private var impactReports: Int = 0
	val cullBox: AABB = AABB(origin, origin).inflate(
		(bound.radius * scale + bound.definition.visibility.boundsPadding).toDouble(),
	)

	/**
	 * 客户端 tick 用独立缓冲采样并检测固体方块碰撞，返回新碰撞点（每 3 个 float 一组）。
	 * 与渲染线程共用 [buffer] 会并发写同一组数组，因此单独分配 [impactBuffer]。
	 */
	fun collectImpacts(level: ClientLevel): FloatArray {
		if (impactReports >= MAX_IMPACT_REPORTS) return EMPTY_POINTS
		update()
		sampleInto(impactBuffer)
		if (impactBuffer.count == 0) return EMPTY_POINTS
		val points = ArrayList<Float>(MAX_IMPACT_REPORTS * 3)
		val step = (impactBuffer.count / IMPACT_CHECKS_PER_TICK).coerceAtLeast(1)
		var i = 0
		while (i < impactBuffer.count && impactReports < MAX_IMPACT_REPORTS) {
			val pos = BlockPos.containing(
				impactBuffer.x[i].toDouble(), impactBuffer.y[i].toDouble(), impactBuffer.z[i].toDouble(),
			)
			if (level.isLoaded(pos) && level.getBlockState(pos).isSolid) {
				if (impactedBlocks.add(pos.asLong())) {
					points += impactBuffer.x[i]
					points += impactBuffer.y[i]
					points += impactBuffer.z[i]
					impactReports++
				}
			}
			i += step
		}
		return points.toFloatArray()
	}

	fun update() {
		age = baseAge + (System.nanoTime() - receivedNanos) / 1_000_000_000.0f
	}

	fun expired(): Boolean = !bound.definition.loop && age >= bound.duration

	fun sample(): ParticleBuffer {
		update()
		sampleInto(buffer)
		return buffer
	}

	private fun sampleInto(out: ParticleBuffer) {
		out.reset(bound.count)
		val sampleAge = when {
			// 反向循环先按周期回绕，否则 age 超过 duration 后一直停在起始帧
			bound.definition.reverse && bound.definition.loop ->
				bound.duration - (age % bound.duration)
			bound.definition.reverse -> (bound.duration - age).coerceAtLeast(0f)
			else -> age
		}
		bound.style.sample(bound, origin, seed, sampleAge, out)
	}

	private companion object {
		val EMPTY_POINTS = FloatArray(0)
	}
}

object ClientEffects {
	private const val DEFINITION_CACHE_LIMIT = 64

	private val definitions = LinkedHashMap<Int, EffectDefinition>()
	private val boundCache = HashMap<Int, BoundEffect>()
	private val active = LinkedHashMap<Long, ClientEffect>()
	private var versionMatches: Boolean? = null

	fun handshake(payload: EffectHandshakePayload) {
		val protocolMatches = payload.protocolVersion == PROTOCOL_VERSION
		val rngMatches = payload.rngVersion == RNG_VERSION
		versionMatches = protocolMatches && rngMatches
		if (versionMatches == true) {
			AstrorbitsParticle.logger.info(
				"[net] handshake ok: protocol={}, rng={}, client protocol={}, rng={}",
				payload.protocolVersion, payload.rngVersion, PROTOCOL_VERSION, RNG_VERSION,
			)
			return
		}
		AstrorbitsParticle.logger.warn(
			"[net] handshake mismatch: server protocol={} rng={}, client protocol={} rng={}; dropping effects",
			payload.protocolVersion, payload.rngVersion, PROTOCOL_VERSION, RNG_VERSION,
		)
		active.clear()
		boundCache.clear()
		definitions.clear()
		Minecraft.getInstance().player?.sendSystemMessage(
			Component.literal(
				"AstrorbitsParticle 版本不一致：服务端协议 ${payload.protocolVersion}、随机 ${payload.rngVersion}，" +
					"客户端协议 ${PROTOCOL_VERSION}、随机 ${RNG_VERSION}。特效已停用。",
			),
		)
	}

	fun start(payload: EffectStartPayload) {
		if (versionMatches == false) {
			AstrorbitsParticle.logger.debug(
				"[effect] dropped id={}: version mismatch", payload.effectId,
			)
			return
		}
		val definition = resolveDefinition(payload) ?: return
		val bound = boundCache.getOrPut(payload.definitionHash) {
			EffectBinder.bind(definition).getOrElse {
				AstrorbitsParticle.logger.warn(
					"[effect] client rejected definition {}: {}", definition.style, it.message,
				)
				return
			}
		}
		val baseAge = payload.elapsedTicks / 20f
		active[payload.effectId] = ClientEffect(
			id = payload.effectId,
			bound = bound,
			seed = payload.seed,
			origin = Vec3(payload.x.toDouble(), payload.y.toDouble(), payload.z.toDouble()),
			scale = payload.scale,
			baseAge = baseAge,
			receivedNanos = System.nanoTime(),
		)
		AstrorbitsParticle.logger.info(
			"[effect] client start id={} style={} particles={}",
			payload.effectId, definition.style, bound.count,
		)
	}

	fun stop(payload: EffectStopPayload) {
		payload.effectIds.forEach(active::remove)
	}

	fun clear(payload: EffectClearPayload) {
		active.clear()
		boundCache.clear()
		definitions.clear()
	}

	fun attach(effectId: Long) {
		val effect = active[effectId] ?: return
		val level = Minecraft.getInstance().level ?: return
		level.addParticle(
			EffectAnchorOptions(effectId),
			effect.origin.x, effect.origin.y, effect.origin.z,
			0.0, 0.0, 0.0,
		)
	}

	fun tick() {
		active.values.removeIf { it.expired() }
		val level = Minecraft.getInstance().level ?: return
		for (effect in active.values) {
			val definition = effect.bound.definition
			if (definition.subEffect == null || definition.subEffectTrigger != SubEffectTrigger.IMPACT) continue
			if (!ClientPlayNetworking.canSend(EffectImpactPayload.TYPE)) continue
			val points = effect.collectImpacts(level)
			if (points.isNotEmpty()) {
				ClientPlayNetworking.send(EffectImpactPayload(effect.id, points))
			}
		}
	}

	fun all(): Collection<ClientEffect> = active.values

	fun isEmpty(): Boolean = active.isEmpty()

	fun count(): Int = active.size

	private fun resolveDefinition(payload: EffectStartPayload): EffectDefinition? {
		payload.definition?.let { raw ->
			val parsed = EffectDefinition.parse(raw).getOrElse {
				AstrorbitsParticle.logger.warn("[effect] client parse failed: {}", it.message)
				return null
			}
			if (definitions.size >= DEFINITION_CACHE_LIMIT) definitions.clear()
			definitions[payload.definitionHash] = parsed
			return parsed
		}
		return definitions[payload.definitionHash]
	}
}
