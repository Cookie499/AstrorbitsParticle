package com.example.astrorbitsparticle

import com.example.astrorbitsparticle.net.ModPayloads
import com.example.astrorbitsparticle.server.EffectsCommand
import com.example.astrorbitsparticle.server.ServerEffects
import com.example.astrorbitsparticle.style.BuiltinStyles
import com.example.astrorbitsparticle.style.ParticleStyles
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory

object AstrorbitsParticle : ModInitializer {
	const val MOD_ID: String = "astrorbitsparticle"

	val logger = LoggerFactory.getLogger(MOD_ID)

	override fun onInitialize() {
		BuiltinStyles.registerAll()
		ModPayloads.register()
		ServerLifecycleEvents.SERVER_STARTING.register(ServerEffects::init)
		ServerTickEvents.END_SERVER_TICK.register(ServerEffects::tick)
		CommandRegistrationCallback.EVENT.register { dispatcher, _, _ -> EffectsCommand.register(dispatcher) }
		logger.info("[effect] registered {} particle styles", ParticleStyles.all().size)
	}

	fun id(path: String): Identifier
		= Identifier.fromNamespaceAndPath(MOD_ID, path)
}
