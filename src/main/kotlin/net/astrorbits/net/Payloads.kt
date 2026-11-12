package net.astrorbits.net

import net.astrorbits.AstrorbitsParticle
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.codec.StreamDecoder
import net.minecraft.network.codec.StreamEncoder
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

/** 协议版本；改动包结构时递增，客户端在连接建立时与服务端比对。 */
const val PROTOCOL_VERSION = 3

/** 随机算法版本；改动 [net.astrorbits.math.HashRandom] 或流的取用顺序时递增。 */
const val RNG_VERSION = 1

/** 连接建立后发送一次，用于客户端校验两端的协议与随机算法版本。 */
class EffectHandshakePayload(
	val protocolVersion: Int,
	val rngVersion: Int,
) : CustomPacketPayload {
	override fun type(): CustomPacketPayload.Type<EffectHandshakePayload> = TYPE

	companion object {
		val TYPE = CustomPacketPayload.Type<EffectHandshakePayload>(AstrorbitsParticle.id("handshake"))

		val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, EffectHandshakePayload> = StreamCodec.of(
			StreamEncoder { buf, payload ->
				buf.writeVarInt(payload.protocolVersion)
				buf.writeVarInt(payload.rngVersion)
			},
			StreamDecoder { buf ->
				EffectHandshakePayload(buf.readVarInt(), buf.readVarInt())
			},
		)
	}
}

class EffectStartPayload(
	val effectId: Long,
	val seed: Long,
	val elapsedTicks: Int,
	val x: Float,
	val y: Float,
	val z: Float,
	val scale: Float,
	val definitionHash: Int,
	/** 客户端已有该 hash 的定义时为 null。 */
	val definition: String?,
) : CustomPacketPayload {
	override fun type(): CustomPacketPayload.Type<EffectStartPayload> = TYPE

	companion object {
		val TYPE = CustomPacketPayload.Type<EffectStartPayload>(AstrorbitsParticle.id("effect_start"))

		val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, EffectStartPayload> = StreamCodec.of(
			StreamEncoder { buf, payload ->
				buf.writeLong(payload.effectId)
				buf.writeLong(payload.seed)
				buf.writeVarInt(payload.elapsedTicks)
				buf.writeFloat(payload.x)
				buf.writeFloat(payload.y)
				buf.writeFloat(payload.z)
				buf.writeFloat(payload.scale)
				buf.writeInt(payload.definitionHash)
				buf.writeBoolean(payload.definition != null)
				if (payload.definition != null) buf.writeUtf(payload.definition)
			},
			StreamDecoder { buf ->
				EffectStartPayload(
					effectId = buf.readLong(),
					seed = buf.readLong(),
					elapsedTicks = buf.readVarInt(),
					x = buf.readFloat(),
					y = buf.readFloat(),
					z = buf.readFloat(),
					scale = buf.readFloat(),
					definitionHash = buf.readInt(),
					definition = if (buf.readBoolean()) buf.readUtf() else null,
				)
			},
		)
	}
}

class EffectStopPayload(val effectIds: LongArray) : CustomPacketPayload {
	override fun type(): CustomPacketPayload.Type<EffectStopPayload> = TYPE

	companion object {
		val TYPE = CustomPacketPayload.Type<EffectStopPayload>(AstrorbitsParticle.id("effect_stop"))

		val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, EffectStopPayload> = StreamCodec.of(
			StreamEncoder { buf, payload ->
				buf.writeVarInt(payload.effectIds.size)
				payload.effectIds.forEach(buf::writeLong)
			},
			StreamDecoder { buf ->
				val size = buf.readVarInt()
				EffectStopPayload(LongArray(size) { buf.readLong() })
			},
		)
	}
}

class EffectClearPayload : CustomPacketPayload {
	override fun type(): CustomPacketPayload.Type<EffectClearPayload> = TYPE

	companion object {
		val TYPE = CustomPacketPayload.Type<EffectClearPayload>(AstrorbitsParticle.id("effect_clear"))

		val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, EffectClearPayload> = StreamCodec.of(
			StreamEncoder { _, _ -> },
			StreamDecoder { EffectClearPayload() },
		)
	}
}

/**
 * 客户端报告本 tick 检测到的碰撞点，每 3 个 float 一组世界坐标。
 * 服务端只接受已订阅该特效的玩家发来的、位于定义可见范围内的点。
 */
class EffectImpactPayload(
	val effectId: Long,
	val positions: FloatArray,
) : CustomPacketPayload {
	override fun type(): CustomPacketPayload.Type<EffectImpactPayload> = TYPE

	companion object {
		const val MAX_POSITIONS = 8

		val TYPE = CustomPacketPayload.Type<EffectImpactPayload>(AstrorbitsParticle.id("effect_impact"))

		val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, EffectImpactPayload> = StreamCodec.of(
			StreamEncoder { buf, payload ->
				val count = (payload.positions.size / 3).coerceAtMost(MAX_POSITIONS)
				buf.writeLong(payload.effectId)
				buf.writeVarInt(count)
				for (i in 0 until count * 3) buf.writeFloat(payload.positions[i])
			},
			StreamDecoder { buf ->
				val effectId = buf.readLong()
				val count = buf.readVarInt().coerceIn(0, MAX_POSITIONS)
				EffectImpactPayload(effectId, FloatArray(count * 3) { buf.readFloat() })
			},
		)
	}
}

object ModPayloads {
	fun register() {
		PayloadTypeRegistry.clientboundPlay().register(EffectHandshakePayload.TYPE, EffectHandshakePayload.STREAM_CODEC)
		PayloadTypeRegistry.clientboundPlay().register(EffectStartPayload.TYPE, EffectStartPayload.STREAM_CODEC)
		PayloadTypeRegistry.clientboundPlay().register(EffectStopPayload.TYPE, EffectStopPayload.STREAM_CODEC)
		PayloadTypeRegistry.clientboundPlay().register(EffectClearPayload.TYPE, EffectClearPayload.STREAM_CODEC)
		PayloadTypeRegistry.serverboundPlay().register(EffectImpactPayload.TYPE, EffectImpactPayload.STREAM_CODEC)
	}
}
