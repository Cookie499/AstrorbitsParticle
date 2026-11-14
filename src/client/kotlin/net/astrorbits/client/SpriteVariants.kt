package net.astrorbits.client

import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier

/** 读取原版粒子定义里的贴图数量；客户端资源包才知道，服务端不参与。 */
object SpriteVariants {
	fun count(id: Identifier): Int {
		val jsonId = Identifier.fromNamespaceAndPath(id.namespace, "particles/${id.path}.json")
		val resource = Minecraft.getInstance().resourceManager.getResource(jsonId).orElse(null) ?: return 1
		return runCatching {
			resource.open().use { stream ->
				com.google.gson.JsonParser.parseReader(stream.bufferedReader())
					.asJsonObject.getAsJsonArray("textures")?.size() ?: 1
			}
		}.getOrDefault(1)
	}
}
