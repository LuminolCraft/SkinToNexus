package org.luminolcraft.skinToNexus

import com.google.gson.Gson
import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.CancellationException
import kotlinx.coroutines.*
import kotlinx.coroutines.time.delay
import net.skinsrestorer.api.SkinsRestorerProvider
import net.skinsrestorer.api.event.SkinApplyEvent
import org.slf4j.Logger
import java.nio.file.Path
import java.time.Duration
import java.util.*

class Main @Inject constructor(val logger: Logger, val server: ProxyServer, @DataDirectory val dataDirectory: Path) {

    companion object {
        lateinit var instance: Main
        val dataDirectory: Path
            get() = instance.dataDirectory
        lateinit var configManager: ConfigManager
        val gson = Gson()
        private val skinChangeMap = Collections.synchronizedMap(LinkedHashMap<String, String>())
        private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        @Volatile
        private var timerJob: Job? = null
        private lateinit var httpClient: HttpClient


        fun startTimerTask() {
            timerJob?.cancel()
            timerJob = scope.launch {
                while (isActive) {
                    val selectedEntries =
                        synchronized(skinChangeMap) { skinChangeMap.entries.take(configManager.maxElementPerPatch) }
                    val packagedJson = gson.toJson(selectedEntries.associateTo(LinkedHashMap()) { it.toPair() })

                    val response: HttpResponse
                    try {
                        response = httpClient.post(configManager.url + "/api/v1/internal/minecraft/skins") {
                            contentType(ContentType.Application.Json)
                            setBody(packagedJson)
                        }
                    } catch (e: CancellationException) {
                        return@launch
                    } catch (e: Exception) {
                        instance.logger.error("Error while posting skin changes.")
                        delay(Duration.ofMinutes(configManager.patchInterval.toLong()))
                        continue
                    }
                    if (response.status.value !in 200..299) {
                        instance.logger.error("Error while posting skin changes. Status code: ${response.status.value}")
                        delay(Duration.ofMinutes(configManager.patchInterval.toLong()))
                        continue
                    }
                    selectedEntries.forEach {
                        synchronized(skinChangeMap) {
                            if (skinChangeMap[it.key] == it.value) skinChangeMap.remove(it.key)
                        }
                    }
                    delay(Duration.ofMinutes(configManager.patchInterval.toLong()))
                }
            }
        }

        fun stopTimerTask() {
            timerJob?.cancel()
            timerJob = null
        }
    }


    @Subscribe
    fun onProxyInitialization(event: ProxyInitializeEvent) {
        instance = this
        configManager = ConfigManager()
        configManager.initAndLoadConfig()
        httpClient = HttpClient(CIO) {
            install(ContentNegotiation) {
                json()
            }
            install(HttpTimeout) {
                requestTimeoutMillis = configManager.requestTimeoutSec.toLong() * 1000
                connectTimeoutMillis = configManager.connectionTimeoutSec.toLong() * 1000
            }
            defaultRequest {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                header("X-Webhook-Secret", configManager.secret)
            }
        }
        server.eventManager.register(this, this)
        configManager.startWatchConfig()
        startTimerTask()
        logger.info("Loaded")
    }

    @Subscribe
    fun onProxyShutdown(event: ProxyShutdownEvent) {
        logger.info("Shutting down")
        server.eventManager.unregisterListeners(this)
        configManager.stopWatchConfig()
        stopTimerTask()
        scope.cancel()
    }

    @Subscribe
    fun onPlayerChangeSkin(event: SkinApplyEvent) {
        val player = event.getPlayer(Player::class.java)
        val uuid = player.uniqueId
        val playerName = player.username
        val skinId = SkinsRestorerProvider.get().playerStorage.getSkinIdOfPlayer(uuid)
        skinId.ifPresent {
            val identifier = it.identifier
            skinChangeMap[playerName] = identifier
        }
    }

}
