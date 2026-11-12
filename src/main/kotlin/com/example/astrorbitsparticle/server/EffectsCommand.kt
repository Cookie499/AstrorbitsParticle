package com.example.astrorbitsparticle.server

import com.example.astrorbitsparticle.AstrorbitsParticle
import com.example.astrorbitsparticle.style.ParticleStyles
import com.example.astrorbitsparticle.style.StyleDocs
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.LongArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.permissions.Permissions

object EffectsCommand {
	fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
		dispatcher.register(
			Commands.literal("astrorbits")
				.requires { it.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER) }
				.then(
					Commands.literal("effect")
						.then(
							Commands.literal("play")
								.then(
									Commands.argument("definition", StringArgumentType.string())
										.executes { ctx ->
											val source = ctx.source
											val raw = StringArgumentType.getString(ctx, "definition")
											val id = Identifier.tryParse(
												if (':' in raw) raw else "${AstrorbitsParticle.MOD_ID}:$raw",
											)
											val definition = id?.let(ServerEffects::findDefinition)
											if (definition == null) {
												source.sendFailure(Component.literal("找不到特效定义：$raw"))
												return@executes 0
											}
											val effect = ServerEffects.play(
												definition, source.level, source.position,
											)
											if (effect == null) {
												source.sendFailure(Component.literal("特效定义校验失败，详见日志"))
												return@executes 0
											}
											source.sendSuccess(
												{ Component.literal("已播放 ${effect.definition.style}，id=${effect.id}") },
												false,
											)
											1
										},
								),
						)
						.then(
							Commands.literal("stop")
								.then(
									Commands.literal("all").executes { ctx ->
										val count = ServerEffects.list().size
										ServerEffects.stopAll()
										ctx.source.sendSuccess({ Component.literal("已停止 $count 个特效") }, false)
										1
									},
								)
								.then(
									Commands.argument("id", LongArgumentType.longArg())
										.executes { ctx ->
											val id = LongArgumentType.getLong(ctx, "id")
											if (ServerEffects.stop(id)) {
												ctx.source.sendSuccess({ Component.literal("已停止特效 $id") }, false)
												1
											} else {
												ctx.source.sendFailure(Component.literal("没有 id 为 $id 的特效"))
												0
											}
										},
								),
						)
						.then(
							Commands.literal("list").executes { ctx ->
								val effects = ServerEffects.list()
								val text = if (effects.isEmpty()) {
									"当前没有活跃特效"
								} else {
									effects.joinToString("\n") {
										"id=${it.id} style=${it.definition.style} scene=${it.scene?.id ?: "-"}"
									}
								}
								ctx.source.sendSuccess({ Component.literal(text) }, false)
								1
							},
						),
				)
				.then(
					Commands.literal("scene")
						.then(
							Commands.literal("list").executes { ctx ->
								val scenes = ServerEffects.scenes()
								val text = if (scenes.isEmpty()) {
									"当前没有场景"
								} else {
									scenes.joinToString("\n") {
										"${it.id} ${it.dimension.identifier()} 视距=${it.viewDistance}"
									}
								}
								ctx.source.sendSuccess({ Component.literal(text) }, false)
								1
							},
						),
				)
				.then(
					Commands.literal("schema")
						.then(
							Commands.literal("all").executes { ctx ->
								val names = ParticleStyles.all().joinToString("、") { it.id }
								ctx.source.sendSuccess(
									{ Component.literal("可用样式：$names（另有 common）") },
									false,
								)
								1
							},
						)
						.then(
							Commands.argument("style", StringArgumentType.word())
								.suggests { _, builder ->
									builder.suggest("common")
									ParticleStyles.all().forEach { builder.suggest(it.id.substringAfter(':')) }
									builder.buildFuture()
								}
								.executes { ctx ->
									val key = StringArgumentType.getString(ctx, "style")
									val lines = if (key == "common") {
										StyleDocs.renderCommonLines()
									} else {
										val style = ParticleStyles.all().firstOrNull {
											it.id == key || it.id == "${AstrorbitsParticle.MOD_ID}:$key" ||
												it.id.substringAfter(':') == key
										}
										if (style == null) {
											ctx.source.sendFailure(Component.literal("未知样式：$key"))
											return@executes 0
										}
										StyleDocs.renderStyleLines(style)
									}
									lines.forEach { line ->
										ctx.source.sendSuccess({ Component.literal(line) }, false)
									}
									1
								},
						),
				)
				.then(
					Commands.literal("test")
						.then(
							Commands.literal("list").executes { ctx ->
								ctx.source.sendSuccess(
									{ Component.literal("可用预设：" + TestPresets.names().joinToString("、")) },
									false,
								)
								1
							},
						)
						.then(
							Commands.literal("all").executes { ctx ->
								val source = ctx.source
								val names = TestPresets.names().toList()
								var spawned = 0
								names.forEachIndexed { index, name ->
									val angle = index * Math.PI * 2.0 / names.size
									val origin = source.position.add(
										kotlin.math.cos(angle) * 6.0,
										0.0,
										kotlin.math.sin(angle) * 6.0,
									)
									if (TestPresets.play(name, source.level, origin) != null) spawned++
								}
								source.sendSuccess(
									{ Component.literal("已播放 $spawned/${names.size} 个测试特效") },
									false,
								)
								spawned
							},
						)
						.then(
							Commands.argument("preset", StringArgumentType.word())
								.suggests { _, builder ->
									TestPresets.names().forEach(builder::suggest)
									builder.buildFuture()
								}
								.executes { ctx ->
									val source = ctx.source
									val name = StringArgumentType.getString(ctx, "preset")
									if (name !in TestPresets.names()) {
										source.sendFailure(Component.literal("未知预设：$name"))
										return@executes 0
									}
									val effect = TestPresets.play(name, source.level, source.position)
									if (effect == null) {
										source.sendFailure(Component.literal("测试特效创建失败，详见日志"))
										return@executes 0
									}
									source.sendSuccess(
										{ Component.literal("已播放测试特效 $name，id=${effect.id}") },
										false,
									)
									1
								},
						),
				)
				.then(
					Commands.literal("reload").executes { ctx ->
						ServerEffects.reload()
						ctx.source.sendSuccess(
							{
								Component.literal(
									"已重载 ${ServerEffects.definitions().size} 个定义、${ServerEffects.scenes().size} 个场景",
								)
							},
							false,
						)
						1
					},
				),
		)
	}
}
