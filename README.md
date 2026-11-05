# SkinToNexus

Velocity 代理端插件：监听 SkinsRestorer 的皮肤切换事件，把「玩家 → 皮肤」变更批量推送到 LuminolCraft Nexus 后端。

## 工作流程

1. 玩家在服务器内换肤，SkinsRestorer 触发 `SkinApplyEvent`。
2. 插件从事件里取权威的玩家名（`Player#getUsername`），再用 `SkinsRestorerProvider#playerStorage#getSkinIdOfPlayer` 取该玩家当前皮肤的 identifier，写入内存表 `玩家名 → identifier`（同一玩家多次换肤只保留最后一次）。
3. 后台协程按 `patch-interval-min` 周期把内存表里最多 `max-element-per-patch` 条打包成 JSON，POST 到 `{api-base-url}/api/v1/internal/minecraft/skins`。
4. 请求返回 2xx 后才把这些条目从内存表移除；网络异常或非 2xx 会保留条目，下个周期重发。删除前会比对值，期间玩家又换了皮就不删，避免吞掉新值。

## 环境要求

- **Velocity**（编译依赖 `com.velocitypowered:velocity-api:3.5.0-SNAPSHOT`）
- **Java 25**（`jvmToolchain(25)`，产物字节码需要 JDK 25 运行）
- **SkinsRestorer**：硬依赖，未安装时 Velocity 会拒绝加载本插件
- 可访问 Nexus 后端（生产环境必须 HTTPS）

## 安装

1. 把 `SkinToNexus-<version>-all.jar` 放进 Velocity 的 `plugins/` 目录。
2. 确认 SkinsRestorer 已安装。
3. 首次启动生成 `plugins/SkinToNexus/config.yml`，填入后端地址与 Secret 后保存（热重载，无需重启）。
4. 日志出现 `Loaded` 即启动完成。

## 配置

首次启动会把缺省键写入 `config.yml`：

| 键 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `api-base-url` | string | `http://localhost:8787` | 后端地址，需带 `http://` 或 `https://`。缺失 scheme 或非法地址会回退为默认值 |
| `secret` | string | `some-fucking-secret` | 共享密钥，作为 `X-Webhook-Secret` 请求头发送，必须与后端环境变量 `WEBHOOK_SECRET` 一致 |
| `patch-interval-min` | int | `10` | 上报周期，单位分钟。`<= 0` 会被钳制为 `Int.MAX_VALUE`，等于停用上报 |
| `max-element-per-patch` | int | `500` | 单次上报的最大条数。`<= 0` 视为不限量 |
| `connect-timeout-seconds` | int | `5` | 连接超时，单位秒。`<= 0` 会被钳制为 `Int.MAX_VALUE` |
| `request-timeout-seconds` | int | `10` | 请求超时，单位秒。`<= 0` 会被钳制为 `Int.MAX_VALUE` |

配置文件每秒检查一次修改时间，变更后自动重载并重启上报循环。

> 注意：插件会用 `config.save()` 重写整个文件，写出的 YAML 是 flow 风格（`{key: value, ...}` 单行），且**不保留注释**。需要注释请改用外部模板管理，不要依赖插件写回的内容。

## 后端接口契约

```
POST {api-base-url}/api/v1/internal/minecraft/skins
Content-Type: application/json
X-Webhook-Secret: <secret>

{"<玩家名>": "<皮肤 identifier>", ...}
```

- 请求体是平铺的字符串键值对象，键为 Minecraft 角色名，值为皮肤 identifier。
- 后端要求 `X-Webhook-Secret` 与 `WEBHOOK_SECRET` 严格相等，缺失或不匹配返回 `403`。
- 判定成功只看 HTTP 状态码是否落在 `200..299`，响应体不解析。
- 跟随重定向：已显式关闭 `HttpRedirect` 的 `checkHttpMethod` 限制，301/302/307/308 对 POST 也会跟随；`https` → `http` 的降级跳转仍被禁止。

## 构建

需要 JDK 21 以上跑 Gradle，工具链要求 JDK 25（Gradle 可自动探测 Zulu 安装）。

仓库只提交了 `gradle/wrapper/gradle-wrapper.properties`（指定 Gradle 9.4.0），没有 `gradlew` 脚本，用本机或 IDE 的 Gradle 直接跑：

```bash
gradle shadowJar
```

产物：`build/libs/SkinToNexus-<version>-all.jar`。

`shadowJar` 会把以下依赖重定位进插件私有包，避免与 Velocity `server.jar` 自带的同名类冲突：

| 原包 | 重定位后 |
| --- | --- |
| `com.electronwill.nightconfig` | `org.luminolcraft.skinToNexus.lib.nightconfig` |
| `org.yaml.snakeyaml` | `org.luminolcraft.skinToNexus.lib.snakeyaml` |

重定位必须保留：Velocity 4 的 `server.jar` 自带 `night-config-core`（读 `velocity.toml` 用）但不带 yaml 模块，父优先加载会让它的 `FormatDetector` 抢先生效，而它的 `Class.forName("...yaml.YamlFormat")` 在 server 加载器里找不到类，最终抛 `NoFormatFoundException`。此外 `ConfigManager` 显式传入 `YamlFormat.defaultInstance()`，加载路径不依赖格式探测。

## 行为细节与已知限制

- **失败重试**：请求失败时条目保留在内存表，按周期重试；没有指数退避。
- **静默丢弃**：后端对未绑定或已改名的角色名是「0 行更新但返回 200」，插件按 2xx 删除本地条目，这类变更不会重发。
- **内存无上限**：后端长期不可用时，内存表会随换肤事件持续增长。
- **空批次也会发送**：内存表为空时仍会每个周期 POST 一次 `{}`。
- **热重载**：每秒轮询 `config.yml` 的修改时间，重载会重启上报循环，已在内存中的待发条目不会丢。
- **凭据**：Secret 只从配置文件读取，不写日志、不硬编码；生产环境务必使用 `https://`，否则 Secret 明文传输。

## 排障

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 启动报 `NoFormatFoundException: No suitable format for config.yml` | night-config 未重定位，格式探测跑在 Velocity 的加载器上 | 确认产物里 `com/electronwill` 已清空、`FormatDetector` 的 `Class.forName` 常量已指向私有包 |
| 启动报 `ParsingException: YAML parsing failed`，cause 为 `map is null` | 空文档被解析（首次运行 0 字节，或文件只剩注释） | 已内置判空保护；确认使用的是修复后的构建 |
| 日志出现 `Status code: 301/302` | 后端地址被强制跳转且未被跟随 | 直接把 `api-base-url` 写成最终地址（`https://`），不要依赖跳转 |
| 日志 `Status code: 403` | `secret` 与后端 `WEBHOOK_SECRET` 不一致 | 同步两侧密钥 |
| 数据一直不上报，日志无输出 | `patch-interval-min` 配成 `0` 或负数（被钳制为 `Int.MAX_VALUE`） | 改成正整数 |
