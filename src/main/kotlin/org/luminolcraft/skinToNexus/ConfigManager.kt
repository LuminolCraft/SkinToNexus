package org.luminolcraft.skinToNexus

import com.electronwill.nightconfig.core.CommentedConfig
import com.electronwill.nightconfig.core.ConfigFormat
import com.electronwill.nightconfig.core.file.CommentedFileConfig
import com.electronwill.nightconfig.yaml.YamlFormat
import java.io.File
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ConfigManager {
    private val configFile = File(Main.dataDirectory.toFile(), "config.yml")
    // 显式传格式，绕开 FormatDetector：父加载器里那份 night-config-core 看不到插件 shade 的 yaml 模块，
    // 探测会返回 null 并抛 NoFormatFoundException。YamlFormat 走常量池，由插件加载器解析。
    // 强转为必需：YamlFormat 实现的是 ConfigFormat<Config>，该重载要 ConfigFormat<? extends CommentedConfig>。
    @Suppress("UNCHECKED_CAST")
    private val config: CommentedFileConfig = CommentedFileConfig.of(
        configFile,
        YamlFormat.defaultInstance() as ConfigFormat<CommentedConfig>
    )
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
        // night-config 3.9.0 的 YamlParser 对空文档会直接 NPE：snakeyaml 返回 null 后仍调用 map.entrySet()。
        // 首次运行走到这里时 config.yml 刚被 createNewFile() 成 0 字节，或用户只留了注释。
        // 因此只有文件里真的存在键值时才解析，否则保持默认值，稍后由 config.save() 写成合法 YAML。
        val hasContent = configFile.length() > 0L &&
            configFile.useLines { lines -> lines.any { it.isNotBlank() && !it.trimStart().startsWith("#") } }
        if (hasContent) {
            config.load()
        }
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
            if (uri.scheme == null) url = "http://localhost:8787"
            else if (!uri.scheme.equals("https", true) && !uri.scheme.equals("http", true)) {
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