package net.astrorbits

import net.astrorbits.net.EffectHandshakePayload
import net.astrorbits.net.EffectImpactPayload
import net.astrorbits.net.ModPayloads
import net.astrorbits.net.PROTOCOL_VERSION
import net.astrorbits.net.RNG_VERSION
import net.astrorbits.particle.EffectAnchorOptions
import net.astrorbits.server.EffectsCommand
import net.astrorbits.server.ServerEffects
import net.astrorbits.style.BuiltinStyles
import net.astrorbits.style.ParticleStyles
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory

object AstrorbitsParticle : ModInitializer {
	const val MOD_ID: String = "astrorbitsparticle"

	val logger = LoggerFactory.getLogger(MOD_ID)

	override fun onInitialize() {
		net.astrorbits.config.ModConfig.load()
		EffectAnchorOptions.TYPE
		BuiltinStyles.registerAll()
		ModPayloads.register()
		ServerLifecycleEvents.SERVER_STARTING.register(ServerEffects::init)
		ServerTickEvents.END_SERVER_TICK.register(ServerEffects::tick)
		ServerPlayConnectionEvents.JOIN.register { listener, _, _ ->
			val player = listener.player
			if (ServerPlayNetworking.canSend(player, EffectHandshakePayload.TYPE)) {
				ServerPlayNetworking.send(player, EffectHandshakePayload(PROTOCOL_VERSION, RNG_VERSION))
			}
		}
		ServerPlayNetworking.registerGlobalReceiver(EffectImpactPayload.TYPE) { payload, context ->
			context.server().execute { ServerEffects.impact(context.player(), payload) }
		}
		CommandRegistrationCallback.EVENT.register { dispatcher, _, _ -> EffectsCommand.register(dispatcher) }
		logger.info("[effect] registered {} particle styles", ParticleStyles.all().size)
	}

	fun id(path: String): Identifier
		= Identifier.fromNamespaceAndPath(MOD_ID, path)
}
