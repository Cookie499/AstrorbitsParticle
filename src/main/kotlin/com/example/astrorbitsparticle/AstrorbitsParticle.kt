package com.example.astrorbitsparticle

import com.example.astrorbitsparticle.net.ModPayloads
import com.example.astrorbitsparticle.style.BuiltinStyles
import com.example.astrorbitsparticle.style.ParticleStyles
import net.fabricmc.api.ModInitializer
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory

object AstrorbitsParticle : ModInitializer {
	const val MOD_ID: String = "astrorbitsparticle"

	val logger = LoggerFactory.getLogger(MOD_ID)

	override fun onInitialize() {
		BuiltinStyles.registerAll()
		ModPayloads.register()
		logger.info("[effect] registered {} particle styles", ParticleStyles.all().size)
	}

	fun id(path: String): Identifier
		= Identifier.fromNamespaceAndPath(MOD_ID, path)
}
