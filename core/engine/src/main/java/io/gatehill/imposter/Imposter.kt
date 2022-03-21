/*
 * Copyright (c) 2016-2021.
 *
 * This file is part of Imposter.
 *
 * "Commons Clause" License Condition v1.0
 *
 * The Software is provided to you by the Licensor under the License, as
 * defined below, subject to the following condition.
 *
 * Without limiting other conditions in the License, the grant of rights
 * under the License will not include, and the License does not grant to
 * you, the right to Sell the Software.
 *
 * For purposes of the foregoing, "Sell" means practicing any or all of
 * the rights granted to you under the License to provide to third parties,
 * for a fee or other consideration (including without limitation fees for
 * hosting or consulting/support services related to the Software), a
 * product or service whose value derives, entirely or substantially, from
 * the functionality of the Software. Any license notice or attribution
 * required by the License must also include this Commons Clause License
 * Condition notice.
 *
 * Software: Imposter
 *
 * License: GNU Lesser General Public License version 3
 *
 * Licensor: Peter Cornish
 *
 * Imposter is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Imposter is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Imposter.  If not, see <https://www.gnu.org/licenses/>.
 */
package io.gatehill.imposter

import com.google.inject.Module
import io.gatehill.imposter.config.util.ConfigUtil
import io.gatehill.imposter.config.util.EnvVars
import io.gatehill.imposter.config.util.MetaUtil
import io.gatehill.imposter.http.HttpRouter
import io.gatehill.imposter.http.SingletonResourceMatcher
import io.gatehill.imposter.inject.BootstrapModule
import io.gatehill.imposter.inject.EngineModule
import io.gatehill.imposter.lifecycle.EngineLifecycleHooks
import io.gatehill.imposter.lifecycle.EngineLifecycleListener
import io.gatehill.imposter.plugin.PluginDiscoveryStrategy
import io.gatehill.imposter.plugin.PluginManager
import io.gatehill.imposter.plugin.PluginManagerImpl
import io.gatehill.imposter.plugin.RoutablePlugin
import io.gatehill.imposter.plugin.config.ConfigurablePlugin
import io.gatehill.imposter.plugin.config.PluginConfig
import io.gatehill.imposter.server.HttpServer
import io.gatehill.imposter.server.ServerFactory
import io.gatehill.imposter.service.ResourceService
import io.gatehill.imposter.util.AsyncUtil
import io.gatehill.imposter.util.HttpUtil
import io.gatehill.imposter.util.InjectorUtil
import io.gatehill.imposter.util.MetricsUtil
import io.gatehill.imposter.util.supervisedDefaultCoroutineScope
import io.vertx.core.Promise
import io.vertx.core.Vertx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import org.apache.logging.log4j.LogManager
import java.io.File
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import javax.inject.Inject
import kotlin.io.path.exists

/**
 * @author Pete Cornish
 */
class Imposter(
    private val vertx: Vertx,
    private val imposterConfig: ImposterConfig,
    private val pluginDiscoveryStrategy: PluginDiscoveryStrategy,
    private val additionalModules: List<Module>,
) : CoroutineScope by supervisedDefaultCoroutineScope {

    private val pluginManager: PluginManager = PluginManagerImpl(pluginDiscoveryStrategy)

    @Inject
    private lateinit var serverFactory: ServerFactory

    @Inject
    private lateinit var engineLifecycle: EngineLifecycleHooks

    @Inject
    private lateinit var resourceService: ResourceService

    private var httpServer: HttpServer? = null

    fun start(): CompletableFuture<Unit> = future {
        LOGGER.info("Starting mock engine ${MetaUtil.readVersion()}")

        val plugins = defaultPlugins.toMutableList()
        imposterConfig.plugins?.let(plugins::addAll)

        val pluginConfigs = processConfiguration()
        val dependencies = pluginManager.preparePluginsFromConfig(imposterConfig, plugins, pluginConfigs)

        val allModules = mutableListOf<Module>().apply {
            add(BootstrapModule(vertx, imposterConfig, pluginDiscoveryStrategy, pluginManager))
            add(EngineModule())
            addAll(dependencies.flatMap { it.requiredModules })
            addAll(additionalModules)
        }

        val injector = InjectorUtil.create(*allModules.toTypedArray())
        injector.injectMembers(this@Imposter)

        pluginManager.startPlugins(injector, pluginConfigs)

        val router = configureRoutes()
        httpServer = serverFactory.provide(imposterConfig, vertx, router).await()

        LOGGER.info("Mock engine up and running on {}", imposterConfig.serverUrl)
    }

    private fun processConfiguration(): Map<String, List<File>> {
        imposterConfig.serverUrl = HttpUtil.buildServerUrl(imposterConfig).toString()

        val configFiles = ConfigUtil.discoverConfigFiles(imposterConfig.configDirs)

        if (EnvVars.discoverEnvFiles) {
            EnvVars.reset(configFiles.map { Paths.get(it.parent, ".env") }.filter { it.exists() })
        }

        EnvVars.getEnv("IMPOSTER_EMBEDDED_SCRIPT_ENGINE")?.let {
            imposterConfig.useEmbeddedScriptEngine = it.toBoolean()
        }

        return ConfigUtil.loadPluginConfigs(imposterConfig, pluginManager, configFiles)
    }

    private fun configureRoutes(): HttpRouter {
        val router = HttpRouter.router(vertx)

        router.errorHandler(HttpUtil.HTTP_NOT_FOUND, resourceService.buildNotFoundExceptionHandler())
        router.errorHandler(HttpUtil.HTTP_INTERNAL_ERROR, resourceService.buildUnhandledExceptionHandler())
        router.route().handler(serverFactory.createBodyHttpHandler())

        val plugins = pluginManager.getPlugins()

        val allConfigs: List<PluginConfig> = plugins.filterIsInstance<ConfigurablePlugin<*>>().flatMap { it.configs }
        check(allConfigs.isNotEmpty()) {
            "No plugin configurations were found. Configuration directories [${imposterConfig.configDirs.joinToString()}] must contain one or more valid Imposter configuration files compatible with installed plugins."
        }

        MetricsUtil.doIfMetricsEnabled("add metrics endpoint") {
            LOGGER.trace("Metrics enabled")
            router.route("/system/metrics").handler(
                resourceService.passthroughRoute(
                    imposterConfig,
                    allConfigs,
                    SingletonResourceMatcher.instance,
                    serverFactory.createMetricsHandler()
                )
            )
        }

        // status check to indicate when server is up
        router.get("/system/status").handler(
            resourceService.handleRoute(imposterConfig, allConfigs, SingletonResourceMatcher.instance) { httpExchange ->
                httpExchange.response()
                    .putHeader(HttpUtil.CONTENT_TYPE, HttpUtil.CONTENT_TYPE_JSON)
                    .end(HttpUtil.buildStatusResponse())
            })

        plugins.filterIsInstance<RoutablePlugin>().forEach { it.configureRoutes(router) }

        // fire post route config hooks
        engineLifecycle.forEach { listener: EngineLifecycleListener ->
            listener.afterRoutesConfigured(imposterConfig, allConfigs, router)
        }

        return router
    }

    fun stop(promise: Promise<Void>) {
        LOGGER.info("Stopping mock server on {}:{}", imposterConfig.host, imposterConfig.listenPort)
        httpServer?.close(AsyncUtil.resolvePromiseOnCompletion(promise)) ?: promise.complete()
    }

    companion object {
        private val LOGGER = LogManager.getLogger(Imposter::class.java)
        private val defaultPlugins = listOf("js-detector", "store-detector")
    }
}
