package com.example.astrorbitsparticle.client

import com.example.astrorbitsparticle.net.EffectClearPayload
import com.example.astrorbitsparticle.net.EffectHandshakePayload
import com.example.astrorbitsparticle.net.EffectStartPayload
import com.example.astrorbitsparticle.net.EffectStopPayload
import com.example.astrorbitsparticle.particle.EffectAnchorOptions
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.particle.v1.ParticleGroupRegistry
import net.fabricmc.fabric.api.client.particle.v1.ParticleProviderRegistry

object AstrorbitsParticleClient : ClientModInitializer {
	override fun onInitializeClient() {
		ParticleProviderRegistry.getInstance().register(EffectAnchorOptions.TYPE) { options, level, x, y, z, _, _, _, _ ->
			EffectAnchorParticle.create(options, level, x, y, z)
		}
		ParticleGroupRegistry.register(EFFECT_RENDER_TYPE) { engine -> EffectParticleGroup(engine) }

		ClientPlayNetworking.registerGlobalReceiver(EffectHandshakePayload.TYPE) { payload, context ->
			context.client().execute { ClientEffects.handshake(payload) }
		}
		ClientPlayNetworking.registerGlobalReceiver(EffectStartPayload.TYPE) { payload, context ->
			context.client().execute {
				ClientEffects.start(payload)
				ClientEffects.attach(payload.effectId)
			}
		}
		ClientPlayNetworking.registerGlobalReceiver(EffectStopPayload.TYPE) { payload, context ->
			context.client().execute { ClientEffects.stop(payload) }
		}
		ClientPlayNetworking.registerGlobalReceiver(EffectClearPayload.TYPE) { payload, context ->
			context.client().execute { ClientEffects.clear(payload) }
		}
	}
}
