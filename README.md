# Lizl Java Data Analyzer

> 把 JVM 里"活的对象"变成可离线查看的结构化报告：字段定义、实例真实取值与引用关系，输出为可折叠 HTML 树或 PlantUML / Mermaid 类图。

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Java Version](https://img.shields.io/badge/Java-1.8+-green.svg)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.7%20%2F%203.x-brightgreen.svg)](https://spring.io/projects/spring-boot)

[Gitee](https://gitee.com/snownnlee/lizl-java-data-analyzer)（主仓）· [GitHub](https://github.com/snownnlee/lizl-java-data-analyzer)（镜像）· Maven 坐标 `io.gitee.snownnlee:lizl-java-data-analyzer`

## 核心亮点

| | |
|---|---|
| **看得全** | 同一份报告里既有类字段定义（类型、泛型、静态/final、继承），也有实例真实取值与引用关系 |
| **看得清** | 可折叠交互式 HTML；样式/脚本/渲染引擎随产物同目录分发，**断网双击即看**，不引 CDN |
| **看得懂** | 6 种产物：`HTML` / `HTML2` / `PUML` / `PUML_HTML` / `MERMAID` / `MERMAID_HTML` |
| **接得上** | 纯工具库零 Spring 依赖；Spring Boot 下三块配置自动装配。**运行时零依赖**，前端引擎随包分发 |
| **查得到** | 独立 HTTP 服务（JDK 原生 `HttpServer`，不依赖 Servlet/WebFlux），浏览器里点 Bean 名看对象现状 |
| **拦得住** | 拦截器通道按路径捕获 handler 入参并落盘，可回看线上偶发请求的真实入参 |
| **兜得住** | 深度 / 节点数 / 耗时三重上限；敏感字段默认脱敏为 `***` |

## 快速开始

不引入 Spring，作为普通工具库直接调用：

```xml
<dependency>
    <groupId>io.gitee.snownnlee</groupId>
    <artifactId>lizl-java-data-analyzer</artifactId>
    <version>x.x.x</version>
</dependency>
```

```java
import cn.lizl.project.tool.analysis.JavaDataAnalyzer;

// 全局配置：进程内设一次即可
JavaDataAnalyzer.globalConfigurer()
        .setGlobalOutputRootDir("MyAnalysisHome")
        .setAnalyzingMaxDepth(10)
        .apply();

// 每次分析一份
JavaDataAnalyzer.runningConfigurer()
        .setOutputFileName("analysis", "订单对象")
        .addAnalyzingClass(Order.class)      // 类结构
        .addAnalyzingObject(new Order())     // 实例值
        .run();                             // 默认写文件
```

产物默认写到 `MyAnalysisHome/analysis/订单对象.html`。想直接取回内容用 `run(OutputContentReceiver)` 回调，
`setRenderType(...)` 切换 6 种产物形态；完整配置项见各 configurer 的 Javadoc。

## Spring Boot 集成

三块配置互不影响，前缀都是 `cn.lizl.java-data-analyzer`：

| 配置块 | 管什么 |
|---|---|
| `server.*` | HTTP 服务的端口、路径、鉴权（两个通道共用） |
| `beans.*` | Bean 采集：采集谁、怎么分析、产物放哪 |
| `interceptor.*` | 请求拦截器：捕获开关、路径规则，**自带独立默认值** |

四个开关全部默认关闭：`server.enable`（起不起服务）、`beans.enable` / `interceptor.enable`（装不装通道）、
`interceptor.active`（装好后是否真的捕获，可运行期热改）。`enable` 与 `enabled` 两种写法都认。

最小可用配置：

```yaml
cn:
  lizl:
    java-data-analyzer:
      server:
        enable: true
      beans:
        enable: true
```

启动后日志打印访问地址 `http://127.0.0.1:18080/lizl-java-data-analyzer/beans`。常用端点：

| 请求 | 说明 |
|---|---|
| `GET /` | 通道索引页 |
| `GET /beans` · `POST /beans` | Bean 列表页 / 名称 JSON 数组 |
| `GET /bean/{beanName}` | 指定 Bean 的分析结果 |
| `GET /interceptor` · `.../state` · `.../list` | 拦截器管理页 / 状态 / 产物列表 |
| `POST /interceptor?active=` | 运行期热改捕获开关与路径 |

拦截器通道需要 `interceptor.enable=true` + `interceptor.active=true`；`include-paths` / `exclude-paths`
用 Ant 风格，按**应用内路径**匹配（不含 contextPath、不含矩阵变量后缀）。

**完整配置项**不在此罗列：jar 内 `analysis/spring/application-analysis.yaml` 是带逐项注释的可复制模板，随包发布。

## 独立 HTTP 服务

不依赖 Spring / Servlet / WebFlux，纯 Java 程序也能挂一个只读查看页：

```java
SimpleAnalysisProvider provider = new SimpleAnalysisProvider("manual",
        JavaDataAnalyzer.runningConfigurer().setOutputRootDir("./analysis"))
        .add("order", order)
        .add(user);

HttpAnalysisServer server = JavaDataAnalyzer.httpServer(provider)
        .setPort(18080)
        .setToken("s3cr3t")   // 绑定回环时可选；绑定非回环地址时必填
        .start();
System.out.println(server.getAccessUrl());
server.stop();
provider.close();
```

对象首次被访问时才分析（懒分析），`?rebuild=true` 强制重算。

## 现状：支持矩阵

| 环境 | 纯工具库 / 独立 HTTP 服务 | beans / server 通道 | interceptor（MVC 捕获） | WebFlux 捕获 |
|---|---|---|---|---|
| Spring Boot 2.7.x + `javax.servlet` | 支持 | 支持 | 支持 | 支持 |
| Spring Boot 3.x + `jakarta.servlet` | 支持 | 支持 | **支持** | 支持 |

- Spring Boot 3 适配用纯 Spring 抽象（`NativeWebRequest` + `PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE` + `SCOPE_REQUEST`），
  不引入 jakarta 编译期依赖，同一份字节码兼容两套宿主。
- Spring 相关依赖、Lombok、注解库均为 `provided + optional`，不传递给使用者。

## 安全须知

> 本库会把运行时对象数据**明文导出为报告**，定位是开发与调试辅助工具。

- 报告以明文写入输出目录，无加密与访问控制；目录权限由操作系统决定。产物含字段值，业务 DTO、请求参数、凭证类字段都可能被完整展开。
- 敏感值脱敏默认开启（`mask-sensitive-values=true`），按字段名 / Map entry key 匹配 `password`、`token`、`secret`、`authorization` 等子串；这是**按名匹配的防御默认值，不是完备清单**。
- 拦截器捕获的是请求处理器入参，登录 DTO、请求头绑定值可能含真实凭证；生产开启前用 `include-paths` / `exclude-paths` 圈定路径。
- **默认绑定 `127.0.0.1` 且无鉴权**：本机任何程序都能访问，绑定回环地址时启动会打 WARN。把 `bind-address` 改成 `0.0.0.0` 或局域网 IP 等非回环地址时，**必须配置** `server.token` 或 `server.auth-user` + `server.auth-password`，否则服务拒绝启动。
- 服务单独校验 `Host` 头（防 DNS rebinding），不在白名单返回 403；默认只放行 `127.0.0.1` / `localhost` / `::1` 与 `bind-address` 本身。经反向代理或局域网 IP 访问时用 `server.allowed-hosts`（独立服务用 `setAllowedHosts(...)`）显式放行。
- token 放 query 会进浏览器历史与访问日志，条件允许时优先用 HTTP Basic；本库日志不输出带 token 的完整地址。
- **不要在生产环境把 HTTP 查看端点作为常开能力**，用完即关；写请求的跨站校验不是鉴权替代品。

## 许可证与链接

[Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)。随包分发的前端引擎为 MIT（来源、版本、许可原文见 jar 内 `analysis/js/SOURCES.txt`）；Spring、Lombok、注解库均不随本库传递。

**snownnlee** · [Gitee Issues](https://gitee.com/snownnlee/lizl-java-data-analyzer/issues) · 欢迎提交 Issue 和 Pull Request。
