# Panama

基于 Java 和 Netty 的 Shadowsocks TCP 服务端，支持普通代理、代理中转和反向代理（内网穿透）。仓库还包含可复用的 TCP、HTTP、WebSocket 服务及 Spring 集成模块。

## 构建与启动

需要 JDK 8 或以上版本、Maven 3。项目目标字节码版本为 Java 8。

```bash
mvn clean package
java -jar vpn/target/panama.jar /absolute/path/panama.config
```

打包产物 `vpn/target/panama.jar` 已包含运行依赖。`mvn clean package` 会先运行测试，再生成 JAR。

也可以将 JAR 和配置放入同一目录，从该目录启动：

```bash
cd /path/to/panama
java -jar panama.jar
```

未指定参数时读取**启动命令当前工作目录**下的 `panama.config`，不会自动查找 JAR 所在目录。配置文件支持绝对路径和 UTF-8；文件不存在、不可读、为空或配置不合法时会报错退出，不会自动生成默认配置。

## 模式

| `mode` | 部署位置与用途 | 数据路径 |
| --- | --- | --- |
| `normal` | 普通 Shadowsocks 服务端，默认模式 | 客户端 → normal → 目标服务 |
| `proxy` | 中转到另一台 Shadowsocks 服务端 | 客户端 → proxy → normal → 目标服务 |
| `outer` | 反向代理的外网端，接受客户端和 inner 连接 | 客户端 → outer → 已建立的反向隧道 |
| `inner` | 反向代理的内网端，主动连接 outer 并访问目标 | 反向隧道 → inner → 目标服务 |

当前实现转发 TCP，不支持 UDP。下方密码均为占位符，部署前请替换；不同进程使用各自的配置文件。

### 普通模式 normal

`normal.config`：

```json
{
  "mode": "normal",
  "encrypt": "encrypt",
  "type": "aes-256-cfb",
  "password": "REPLACE_WITH_CLIENT_PASSWORD",
  "port": 9898
}
```

```bash
java -jar panama.jar /absolute/path/normal.config
```

Shadowsocks 客户端填写该服务器地址、端口 `9898`，以及一致的加密类型和密码。

### 中转模式 proxy

中转端 `proxy.config`：

```json
{
  "mode": "proxy",
  "type": "aes-256-cfb",
  "password": "REPLACE_WITH_CLIENT_PASSWORD",
  "port": 9898,
  "proxy": "127.0.0.1",
  "proxyPort": 9899,
  "proxyType": "aes-256-cfb",
  "proxyPassword": "REPLACE_WITH_BACKEND_PASSWORD"
}
```

后端 `backend.config`：

```json
{
  "mode": "normal",
  "type": "aes-256-cfb",
  "password": "REPLACE_WITH_BACKEND_PASSWORD",
  "port": 9899
}
```

分别启动后端和中转端：

```bash
java -jar panama.jar /absolute/path/backend.config
java -jar panama.jar /absolute/path/proxy.config
```

以上命令各占一个终端或服务进程。示例使用本机后端；跨机器部署时，将 `proxy` 替换为后端地址。客户端连接中转端的 `9898` 端口。

`proxyType` 对应后端的 `type`，`proxyPassword` 对应后端的 `password`，`proxyPort` 对应后端的 `port`。客户端侧和后端侧加密类型、密码可以不同；相同时直接转发，不同时进行解密和重新加密。

### 反向代理 outer / inner

外网端 `outer.config`：

```json
{
  "mode": "outer",
  "type": "aes-256-cfb",
  "password": "REPLACE_WITH_CLIENT_PASSWORD",
  "port": 9898,
  "proxyType": "aes-256-cfb",
  "proxyPassword": "REPLACE_WITH_INNER_PASSWORD",
  "reversePort": 9899
}
```

内网端 `inner.config`：

```json
{
  "mode": "inner",
  "type": "aes-256-cfb",
  "password": "REPLACE_WITH_INNER_PASSWORD",
  "reverseHost": "127.0.0.1",
  "reversePort": 9899
}
```

先在外网服务器启动 outer，再在内网服务器启动 inner：

```bash
java -jar panama.jar /absolute/path/outer.config
java -jar panama.jar /absolute/path/inner.config
```

跨机器部署时，将 inner 的 `reverseHost` 替换为外网服务器地址。inner 必须能连接 outer 的 `reversePort`；客户端连接 outer 的 `port`。outer 的两个端口不能相同。

outer 的 `proxyType`、`proxyPassword` 分别对应 inner 的 `type`、`password`。目标服务由 inner 访问。连接失败或隧道断开后，inner 每 3 秒尝试重连；断开的业务连接会关闭，需要客户端重新建立。没有可用 inner 时，outer 会关闭新的业务连接，避免一直等待。

反向隧道本身尚无身份认证和 TLS，应通过可信网络或防火墙限制 `reversePort` 的来源。Shadowsocks 密码不等同于反向隧道接入认证。

## 配置参考

| 字段 | 默认值 / 要求 | 含义 |
| --- | --- | --- |
| `mode` | `normal` | `normal`、`proxy`、`outer`、`inner` |
| `encrypt` | `encrypt` | 传输封装方式，见下表 |
| `type` | `aes-256-cfb` | 当前端的加密类型 |
| `password` | 历史默认值为 `123456`，请显式替换 | 当前端密码 |
| `port` | `9898` | normal/proxy/outer 监听端口；inner 不使用 |
| `proxy` / `proxyPort` | proxy 模式必填 | 后端 Shadowsocks 服务地址和端口 |
| `proxyType` / `proxyPassword` | 加密传输下 proxy/outer 必填 | 后端 normal 或 inner 的加密配置 |
| `reverseHost` | inner 模式必填 | outer 地址 |
| `reversePort` | inner/outer 必填 | outer 接受反向隧道的端口 |

支持的加密类型：`aes-128-cfb`、`aes-192-cfb`、`aes-256-cfb`、`aes-128-ofb`、`aes-192-ofb`、`aes-256-ofb`、`bf-cfb`。客户端须支持所选类型。

| `encrypt` 值 | 行为 | 是否加密 |
| --- | --- | --- |
| `encrypt` | 使用 `type` 和 `password` 加解密 | 是 |
| `raw` | 原始数据 | 否 |
| `compress` | 分帧和压缩 | 否 |
| `zero-padding` | 分帧和零填充 | 否 |
| `random-padding` | 分帧和随机填充 | 否 |

这些封装选项互斥。压缩和填充不会自动叠加加密，也不是通用 Shadowsocks 客户端支持的默认格式；链路两端需要采用一致且兼容的封装。

## 本轮稳定性修复

- normal：异步 DNS/连接建立、双向背压、分片 IV 和地址头处理，改善连接等待及慢连接占用问题。
- proxy：根据实际生效的配置选择直转或加密转换，正确处理分片响应。
- outer/inner：每条隧道独立拆包，会话固定到所属隧道；断线只清理相关会话，重连及关闭释放资源。
- 压缩和分帧：修复截断输入空转、分片数据丢失、异常长度和 padding 越界。
- 公共模块：修复 HTTP/WebSocket 消息边界与握手、响应头、监听器和线程关闭、配置路径读取及密码日志问题。

反向帧、封装包和解压结果上限为 8 MiB，主要待发队列也设有 8 MiB 上限；超限或写失败会关闭相应连接。HTTP 正文及完整 WebSocket 文本消息上限为 1 MiB。这些是包/队列限制，不是 VPN 转发文件总大小限制。

建议同时更新 outer 和 inner。详细行为、嵌入式 API 变化及限制见 [全模式排雷说明](docs/project-hardening.md)，normal 模式的修复过程见 [normal 修复说明](docs/normal-mode-fixes.md)。

## 测试与项目结构

```bash
mvn test
```

本轮验证包含 36 项自动化测试，覆盖拆包、加密、压缩、HTTP/WebSocket、队列上限、真实 TCP 代理链路和断线重连。独立 JAR 另通过 normal/proxy、outer/inner 四路并发、每路 1 MiB 数据校验及隧道中断恢复测试。测试详情与环境记录见 [验证记录](docs/project-hardening.md#验证记录)；这些结果不代表公网长期压测或速度基准。

| 模块 | 用途 |
| --- | --- |
| `core` | 请求、响应和协议处理 |
| `server` | TCP、HTTP、WebSocket 服务 |
| `client` | TCP 客户端与响应处理 |
| `spring` | Spring 服务发现及启动集成 |
| `vpn` | Shadowsocks、反向隧道、可执行 JAR 与回归测试 |
| `test` | 客户端和服务端示例 |

本轮未升级旧依赖栈，也未增加现代认证加密协议。依赖版本及协议安全边界见 [待处理项](docs/project-hardening.md#仍需单独处理的边界)。
