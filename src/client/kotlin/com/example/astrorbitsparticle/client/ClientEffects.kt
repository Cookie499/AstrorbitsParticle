package com.example.astrorbitsparticle.client

import com.example.astrorbitsparticle.AstrorbitsParticle
import com.example.astrorbitsparticle.model.EffectDefinition
import com.example.astrorbitsparticle.net.EffectClearPayload
import com.example.astrorbitsparticle.net.EffectHandshakePayload
import com.example.astrorbitsparticle.net.EffectStartPayload
import com.example.astrorbitsparticle.net.EffectStopPayload
import com.example.astrorbitsparticle.net.PROTOCOL_VERSION
import com.example.astrorbitsparticle.net.RNG_VERSION
import com.example.astrorbitsparticle.particle.EffectAnchorOptions
import com.example.astrorbitsparticle.style.BoundEffect
import com.example.astrorbitsparticle.style.EffectBinder
import com.example.astrorbitsparticle.style.ParticleBuffer
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

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
	val cullBox: AABB = AABB(origin, origin).inflate(
		(bound.radius * scale + bound.definition.visibility.boundsPadding).toDouble(),
	)

	fun update() {
		age = baseAge + (System.nanoTime() - receivedNanos) / 1_000_000_000.0f
	}

	fun expired(): Boolean = !bound.definition.loop && age >= bound.duration

	fun sample(): ParticleBuffer {
		update()
		buffer.reset(bound.count)
		val sampleAge = if (bound.definition.reverse) {
			(bound.duration - age).coerceAtLeast(0f)
		} else {
			age
		}
		bound.style.sample(bound, origin, seed, sampleAge, buffer)
		return buffer
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
