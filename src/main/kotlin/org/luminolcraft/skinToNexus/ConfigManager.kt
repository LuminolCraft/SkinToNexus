package org.luminolcraft.skinToNexus

import com.electronwill.nightconfig.core.file.CommentedFileConfig
import java.io.File
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ConfigManager {
    private val configFile = File(Main.dataDirectory.toFile(), "config.yml")
    private val config = CommentedFileConfig.of(configFile)
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var lastModified = 0L

    @Volatile
    var url = ""

    @Volatile
    var secret = ""

    @Volatile
    var patchInterval = 0

    @Volatile
    var maxElementPerPatch = 0

    @Volatile
    var connectionTimeoutSec = 0

    @Volatile
    var requestTimeoutSec = 0

    fun initAndLoadConfig() {
        if (!configFile.exists()) {
            if (!configFile.parentFile.exists()) configFile.parentFile.mkdirs()
            configFile.createNewFile()
        }
        config.load()
        var shouldSave = false
        if (config.get<String>("api-base-url") == null) {
            config.set<String>("api-base-url", "http://localhost:8787")
            shouldSave = true
        }
        if (config.get<String>("secret") == null) {
            config.set<String>("secret", "some-fucking-secret")
            shouldSave = true
        }
        if (config.get<Int>("patch-interval-min") == null) {
            config.set<Int>("patch-interval-min", 10)
            config.setComment(
                "patch-interval-min", """配置自动向后端发送更新皮肤的间隔时间
                |单位：分钟
            """.trimMargin()
            )
            shouldSave = true
        }
        if (config.get<Int>("max-element-per-patch") == null) {
            config.set<Int>("max-element-per-patch", 500)
            config.setComment("max-element-per-patch", """配置每次向后端发送更新皮肤数据的最大量""")
            shouldSave = true
        }
        if (config.get<Int>("connect-timeout-seconds") == null) {
            config.set<Int>("connect-timeout-seconds", 5)
            shouldSave = true
        }
        if (config.get<Int>("request-timeout-seconds") == null) {
            config.set<Int>("request-timeout-seconds", 10)
            shouldSave = true
        }
        if (shouldSave) {
            config.save()
        }

        //Read
        lastModified = configFile.lastModified()
        url = config.getOrElse("api-base-url", "http://localhost:8787");
        run {
            val uri = URI.create(url)
            if (!uri.scheme.equals("https", true) && !uri.scheme.equals("http", true)) {
                url = "http://localhost:8787"
            }
        }
        secret = config.getOrElse("secret", "some-fucking-secret")
        patchInterval = config.getOrElse("patch-interval-min", 10)
        if (patchInterval <= 0) {
            patchInterval = Int.MAX_VALUE
        }
        maxElementPerPatch = config.getOrElse("max-element-per-patch", 500)
        if (maxElementPerPatch <= 0) {
            maxElementPerPatch = Int.MAX_VALUE
        }
        connectionTimeoutSec = config.getOrElse("connection-timeout-seconds", 5)
        if (connectionTimeoutSec <= 0) {
            connectionTimeoutSec = Int.MAX_VALUE
        }
        requestTimeoutSec = config.getOrElse("request-timeout-seconds", 10)
        if (requestTimeoutSec <= 0) {
            requestTimeoutSec = Int.MAX_VALUE
        }
    }

    fun reloadConfig() {
        Main.stopTimerTask()
        initAndLoadConfig()
        Main.startTimerTask()
    }

    fun startWatchConfig() {
        scheduler.scheduleAtFixedRate({
            if (configFile.lastModified() != lastModified) {
                reloadConfig()
            }
        }, 0L, 1L, TimeUnit.SECONDS)
    }

    fun stopWatchConfig() {
        scheduler.shutdownNow()
    }
}