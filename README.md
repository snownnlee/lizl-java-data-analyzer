# Lizl Java Data Analyzer

> 运行时剖析 Java 类的字段定义与对象实例的字段值、引用关系，渲染成可折叠 HTML 树或 PlantUML / Mermaid 图

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Java Version](https://img.shields.io/badge/Java-1.8+-green.svg)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.7.18-brightgreen.svg)](https://spring.io/projects/spring-boot)

代码仓库：[Gitee](https://gitee.com/snownnlee/lizl-java-data-analyzer)（主仓）· [GitHub](https://github.com/snownnlee/lizl-java-data-analyzer)（代码镜像）

Maven 坐标只有 `io.gitee.snownnlee:lizl-java-data-analyzer`；GitHub 仅作代码镜像，不发布 Maven 坐标。

## 核心亮点

| 维度 | 亮点 |
|---|---|
| **看得全** | 类结构与实例值可同时分析：同一个产物里既有字段定义，也有真实取值 |
| **看得清** | 可折叠交互式 HTML，CSS/JS 全内联，**单文件、断网可看**，双击即开 |
| **看得懂** | 5 种产物形态：`HTML` / `PUML` / `PUML_HTML` / `MERMAID` / `MERMAID_HTML`；类图连线锚定到来源字段行（PUML 系） |
| **接得上** | 纯工具库可直接调；Spring Boot 环境加几行配置，容器里所有 Bean 自动纳入分析。**运行时零依赖**：前端渲染引擎随包分发，不引 CDN、不装 PlantUML |
| **查得到** | 独立 HTTP 服务（JDK 原生，不依赖 Servlet/WebFlux），浏览器里点 Bean 名直接看对象现状 |
| **拦得住** | 请求拦截器通道按路径捕获 handler 入参并落盘——线上偶发问题可直接回看那条请求的实参 |
| **兜得住** | 分析深度、节点数、耗时三重上限；敏感字段（password/token 等）默认脱敏为 `***` |

## 目录

- [独立使用](#独立使用) — 引入依赖 / 最小示例 / 完整示例 / 结果取回 / 完整配置说明 / 独立 HTTP 服务
- [Spring Boot 集成](#spring-boot-集成) — 三块配置与四个开关 / 采集时机 / 访问端点 / 拦截器通道 / 完整配置示例 / 配置项说明
- [常见问题](#常见问题) · [依赖说明](#依赖说明) · [安全须知](#安全须知) · [许可证](#许可证)

---

## 独立使用

不引入 Spring，作为普通工具库直接在 `main` 方法或任意代码里调用。

### 1. 引入依赖

```xml
<dependency>
    <groupId>io.gitee.snownnlee</groupId>
    <artifactId>lizl-java-data-analyzer</artifactId>
    <version>x.x.x</version>
</dependency>
```

### 2. 最小示例

```java
import cn.lizl.project.tool.analysis.JavaDataAnalyzer;

// 全局配置：进程内设一次即可（不设则全用默认值）
JavaDataAnalyzer.globalConfigurer()
        .setGlobalOutputRootDir("MyAnalysisHome")  // 输出根目录
        .setAnalyzingMaxDepth(10)                  // 最大分析深度
        .apply();

// 运行配置 + 执行：每次分析一份
JavaDataAnalyzer.runningConfigurer()
        .setOutputFileName("analysis", "订单对象")   // 输出到 MyAnalysisHome/analysis/订单对象.html
        .addAnalyzingClass(Order.class)            // 要分析的类（字段定义）
        .addAnalyzingObject(new Order())           // 要分析的实例（字段值）
        .run();                                    // 默认写文件
```

分析类名也可传字符串：`.addAnalyzingClass("com.example.Order")`，找不到类时默认静默跳过
（`setThrowExceptionWhenStringClassNotFound(true)` 改为抛异常）。

### 3. 完整示例

```java
import cn.lizl.project.tool.analysis.JavaDataAnalyzer;
import cn.lizl.project.tool.analysis.api.AnalysisMode;
import cn.lizl.project.tool.analysis.api.OutputFileReceiver;
import cn.lizl.project.tool.analysis.api.RenderType;

import java.util.Arrays;
import java.util.List;

public class OrderAnalysis {

    /** 被分析的对象：基本类型、字符串、集合各一个 */
    static class Order {
        private long id = 10086L;
        private String customer = "张三";
        private List<String> items = Arrays.asList("apple", "banana");
    }

    public static void main(String[] args) {
        // 1. 全局配置：进程内设置一次，影响之后所有分析任务
        JavaDataAnalyzer.globalConfigurer()
                .setCommonExcludePackagesToDefault()   // 排除 java./javax./jakarta./jdk./sun.
                .setGlobalOutputRootDir("./analysis-output")
                .setClassLoader(OrderAnalysis.class.getClassLoader())
                .setAnalyzingMaxDepth(15)
                .setCollectionsAnalyzingCount(50)
                .setValueDisplayLengthLimit(1000)
                .setAnalyzeStaticField(false)
                .setShouldAnalyzeNullValue(false)
                .setEnableCollectionSimpleView(true)
                .setAnalysisMode(AnalysisMode.AUTO_BY_COMPUTE)
                .setRenderType(RenderType.HTML)
                .apply();

        // 2. 运行配置 + 执行：输出到 ./analysis-output/order-analysis/Order对象分析.html
        JavaDataAnalyzer.runningConfigurer()
                .setOutputFileName("order-analysis", "Order对象分析")
                .addAnalyzingClass(Order.class)
                .addAnalyzingObject(new Order())
                .run((OutputFileReceiver) path -> System.out.println("分析完成：" + path));
    }
}
```

### 4. 结果取回

| 方式 | 写法 | 说明 |
|---|---|---|
| 写文件（默认） | `.run()` | 按 `outputRootDir` + `outputFileName` 落盘 |
| 文件路径回调 | `.run((OutputFileReceiver) path -> ...)` | 落盘后把路径交给你处理 |
| 内容字符串回调 | `.run((OutputContentReceiver) content -> ...)` | 产物内容直接进内存，不落盘 |

> `run` 的两个重载都是函数式接口，**裸 lambda 会编译歧义**，必须像上表那样显式转型。

`setOutputFileName("a", "b")` 是路径分段：前段作子目录，最后一段是文件名（扩展名按 `RenderType` 自动补）。

> **并发**：每次分析新建一个 `runningConfigurer()`。它内部持有待分析对象列表，共享同一实例时
> A 任务刚加的对象会被 B 任务的 `clear()` 抹掉，产物会与目标错配。同一实例并发 `runAsync` 是允许的，
> 前提是任务在飞期间不改配置、不换 `renderType`、产物文件名不重名。

### 5. 完整配置说明

#### 全局配置（`globalConfigurer()`）

设置一次，影响进程内所有分析任务。

| 方法 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `setGlobalOutputRootDir` | String | `用户主目录/cn.lizl.project.tool.analysis-result` | 全局输出根目录 |
| `setCommonIncludePackages` | String... | 空 | 只分析这些包（空 = 不限制） |
| `setCommonExcludePackages` | String... | `java.` `javax.` `jakarta.` `jdk.` `sun.` | 排除这些包 |
| `setCommonExcludePackagesToDefault` | - | - | 恢复为上述默认排除包 |
| `setClassLoader` | ClassLoader | GlobalConfig 的类加载器 | 分析时使用的类加载器 |
| `setThrowExceptionWhenStringClassNotFound` | boolean | `false` | 按类名字符串找不到类时是否抛异常 |
| `setAnalyzeStaticField` | boolean | `false` | 是否分析静态字段 |
| `setShouldAnalyzeNullValue` | boolean | `true` | 是否分析 null 值 |
| `setAnalyzingMaxDepth` | int | `7` | 最大分析深度 |
| `setAnalyzingMaxNodeCount` | int | `2000` | 单次分析最多新建节点数，`≤0` 不限制；触顶后未展开位置显示提示节点 |
| `setAnalyzingTimeoutMillis` | int | `0`（不限） | 单次分析耗时上限，防字段取值本身卡住 |
| `setCollectionsAnalyzingCount` | int | `10` | 集合最多分析多少个元素 |
| `setValueDisplayLengthLimit` | int | `800` | 字段值显示长度上限 |
| `setDefaultToExpand` | boolean | `false` | HTML 折叠树默认是否展开 |
| `setAnalysisMode` | AnalysisMode | `AUTO_BY_COMPUTE` | 分析模式，见下表 |
| `setEnableCollectionSimpleView` | boolean | `true` | 集合使用简单视图 |
| `setAddToStringField` | boolean | `false` | 追加 `toString` 字段（会真实调用一次 `toString()`） |
| `setMaskSensitiveValues` | boolean | `true` | 敏感字段值脱敏为 `***`（见「安全须知」） |
| `setRenderType` | RenderType | `HTML` | 输出格式，见下表 |

#### 运行配置（`runningConfigurer()`）

每次分析一份，含全局配置的全部项，可对单次分析覆盖：

| 方法 | 说明 |
|---|---|
| `setOutputRootDir` | 本次输出根目录，覆盖全局值 |
| `setOutputFileName` | 路径分段：前段作子目录，末段为文件名（自动补扩展名） |
| `addAnalyzingClass(Class / String)` | 添加要分析的类 |
| `addAnalyzingObject(Object)` | 添加要分析的实例 |
| `run(...)` | 执行分析，见「结果取回」 |
| `clear()` / `reset()` | 清空已添加的类与对象 / 恢复到全局配置 |

> **包过滤例外**：`setCommonIncludePackages` / `setCommonExcludePackages` 设的是运行级规则，**不会替换**全局规则，
> 两层叠加参与判定（运行级 include → 运行级 exclude → 全局 include → 全局 exclude）。
> 配置文件与 `interceptor.*` 这两处对包规则的处理是**追加合并**（走 `add*`，去重），不是替换，
> 详见 [配置项说明](#7-配置项说明) 里的「包过滤是两层叠加」。

#### 分析模式（`AnalysisMode`）

| 模式 | 说明 |
|---|---|
| `ONLY_CLASS` | 仅分析类型（字段定义），忽略实例值 |
| `ONLY_INSTANCE` | 仅分析实例对象（字段值） |
| `AUTO_BY_CONFIG` | 按配置决定分析类型还是实例 |
| `AUTO_BY_COMPUTE` | 有实例就按实例分析，否则按类型 |

#### 输出格式（`RenderType`）

| 格式 | 说明 |
|---|---|
| `HTML` | 可折叠交互式 HTML 树，单文件、断网可看 |
| `PUML` | PlantUML 文本，需外部渲染器查看 |
| `PUML_HTML` | 极简 HTML + 同目录 `plantuml-*.js`，浏览器端渲染成图；**离线可看** |
| `MERMAID` | Mermaid `classDiagram` 文本，GitHub / Typora / Obsidian 原生出图 |
| `MERMAID_HTML` | 同一份 Mermaid 文本嵌进 HTML + 同目录 `mermaid-*.min.js`；**离线可看** |

补充说明：

- `PUML_HTML` / `MERMAID_HTML` 都是**浏览器端渲染**：写产物时把对应引擎（`plantuml-*.js` + `viz-global-*.js`
  或 `mermaid-*.min.js`）复制到产物**同目录**，页面用相对路径 `./xxx.js` 引用。因此
  **不用联网、不引 CDN、双击 `file://` 打开也能出图**；同目录多个产物共用一份引擎，不重复落盘。
- 文件名自带版本（如 `mermaid-11.17.2.min.js`）：换版本时不会误用旧文件，也便于浏览器长期强缓存。
- 引擎从 classpath 的 `analysis/js/` 分发（来源、版本、许可见同目录 `SOURCES.txt`，由
  `scripts/fetch-frontend-assets.py` 生成）。走 HTTP 时由服务端返回同一份文件，带一年强缓存。
- 类图连线锚定到字段行由浏览器里的 Graphviz（`viz-global.js` 内嵌 Viz.js）完成，**不再需要系统安装 Graphviz**，
  也不需要设置 `GRAPHVIZ_DOT`。
- 代价：图不再"打开即见"，需等浏览器解析并渲染引擎（首次打开数秒）；无 JS 环境（如某些邮件/文本预览）看不到图。
  PlantUML 引擎约 5.4 MB、mermaid 约 3.5 MB，文件名带版本，同一目录只存一份。


### 6. 独立 HTTP 服务（可选）

基于 JDK 原生 `com.sun.net.httpserver`，**不依赖 Spring / Servlet / WebFlux**，纯 Java 程序同样可用。
内置 `SimpleAnalysisProvider` 负责登记对象、按需分析：

```java
SimpleAnalysisProvider provider = new SimpleAnalysisProvider(JavaDataAnalyzer.runningConfigurer()
        .setOutputRootDir("./analysis"))   // 分析粒度与产物形态全由这个 configurer 决定
        .add("order", order)
        .add(user);                        // 不传名字时按类简单名自动命名

HttpAnalysisServer server = JavaDataAnalyzer.httpServer(provider)
        .setPort(18080)                    // 0 = 系统分配
        .setToken("s3cr3t")                // 可选，不设则不校验
        .start();

System.out.println(server.getAccessUrl());
// ...
server.stop();
provider.close();
```

- 对象**首次被访问时才分析**（懒分析），`?rebuild=true` 强制重算
- `provider` 独占传入的 `RunningConfigurer`，不要与其他线程共用同一实例
- 对象来源不是手工登记时（例如自有容器），实现 `AnalysisProvider` 即可接入

| 请求 | 说明 |
|---|---|
| `GET /beans` | Bean 列表页面 |
| `POST /beans` | Bean 名称列表（JSON 字符串数组） |
| `GET /bean/{beanName}` | 指定 Bean 的分析结果 |

以上均支持 `?rebuild=true`。路径前缀、端口、绑定地址、令牌与 Basic 认证的配置方式见
[Spring Boot 配置项说明](#7-配置项说明)（Spring 下由配置文件提供，非 Spring 下由 `HttpAnalysisServer`
的 setter 提供）。

**扩展点**：服务器只负责鉴权、路由和根路径索引页，具体端点由 `HttpServiceHandler` 实现。新增一种服务
只要实现该接口并注册一次，不用改服务器：

```java
server.register(new HttpServiceHandler() {
    public Collection<String> getPaths()            { return Collections.singletonList("/custom"); }
    public boolean isAvailable()                    { return true; }          // false 时该组返回 404 + 提示
    public String unavailableMessage()              { return "custom 通道未启用"; }
    public void handle(HttpExchange ex, String path) { HttpResponses.text(ex, 200, "ok"); }
    public IndexCard indexCard()                    { return new IndexCard("custom", "自定义通道", "说明文案"); }
});
```

路由按**最长前缀匹配**（`/bean/` 优先于 `/beans`，`/interceptors` 不会误命中 `/interceptor`），
根路径索引页的卡片与链接由注册表自动生成（并自动带上 token）。

Spring 环境下不必手工注册：容器里**任何** `HttpServiceHandler` Bean 都会被 `server.enable=true` 的服务自动收集挂载
（beans 与 interceptor 两个通道就是这样各自提供自己处理器的），新增通道只需在自己的自动配置里提供一个该类型的 Bean。

反过来也成立：**`server.enable=false` 时不注册任何处理器 Bean**——处理器唯一的消费者就是那台服务器，
服务不存在时它只是一份没人读的模板缓存。通道是否启用不影响处理器的注册：服务在、通道没开时处理器照样注册，
于是访问该组得到的是「通道未启用」提示，而不是 `not found`。

---

## Spring Boot 集成

### 1. 引入依赖

```xml
<dependency>
    <groupId>io.gitee.snownnlee</groupId>
    <artifactId>lizl-java-data-analyzer</artifactId>
    <version>x.x.x</version>
</dependency>
```

Spring 相关依赖是 `provided + optional`，不会影响非 Spring 环境的运行时类路径。

### 2. 三块配置、四个开关

Spring 集成拆成**三块互不影响**的配置，前缀都在 `cn.lizl.java-data-analyzer` 下：

| 配置块 | 管什么 | 是否影响另一端 |
|---|---|---|
| `server.*` | HTTP 服务的传输层（一个端口、一套鉴权） | 两个通道共用 |
| `beans.*` | bean 采集通道：采集谁、怎么分析、产物放哪、出什么格式 | 不影响 interceptor |
| `interceptor.*` | 请求拦截器通道：字段与 `beans.*` 一一对应，**自带默认值** | 不影响 beans |

四个开关，各管一段，全部默认关闭：

| 开关 | 含义 | 关掉后 |
|---|---|---|
| `server.enable` | 起不起 HTTP 服务 | 整台不起，所有端点不可达 |
| `beans.enable` | 装不装 bean 采集 | 不采集；server 起着时 `/beans*` 返回 404 |
| `interceptor.enable` | 装不装拦截器通道 | 不包装参数解析器；`/interceptor*` 返回 404 |
| `interceptor.active` | 通道装好后是否真的捕获 | 通道在、端点可用，只是不捕获 |

**通道与 HTTP 服务是正交的**：只开通道不开 `server` 时，通道照常装配、产物照常写入各自的 `output-root-dir`
（处理器 Bean 则不会注册——没有服务就没有消费者），只是**没有网页入口**。这里有个容易踩的点——
`beans.lazy=true`（默认）的语义是“首次访问才分析”，而访问入口就是 `/bean/{name}`：
**没有 HTTP 就永远不会分析，磁盘上不会出现任何产物**。这种组合会在启动时由各通道自己打一条 WARN
（bean 采集点名 `lazy=true`、拦截器点名“捕获照常但没有管理页”），并指出该开哪个开关。

最小可用配置（.properties）：

```properties
cn.lizl.java-data-analyzer.server.enable=true
cn.lizl.java-data-analyzer.beans.enable=true
```

等价 YAML：

```yaml
cn:
  lizl:
    java-data-analyzer:
      server:
        enable: true
      beans:
        enable: true
```

启动后日志会打印实际访问地址：

```
http://127.0.0.1:18080/lizl-java-data-analyzer/beans
```

> 开关键同时接受 `enable` 与 `enabled` 两种写法——`enabled` 这个词太常用，写错会静默不生效，
> 所以两种都认，避免这类最难排查的配置事故。

### 3. Bean 采集时机

`beans.*` 下的两个开关组合决定「什么时候登记、什么时候真正分析」：

| `eager-to-analyze` | `lazy` | 行为 | 适用 |
|---|---|---|---|
| `true`（默认） | `true`（默认） | 每个 Bean 初始化后立即**登记**，首次访问才分析 | 启动几乎零成本，推荐 |
| `true` | `false` | 每个 Bean 初始化后立即**异步全量分析** | 启动期 CPU 占用高，但打开页面即出结果 |
| `false` | `true` | 全部单例就绪后统一登记，仍按需分析 | 避开初始化高峰期的提交打扰 |
| `false` | `false` | 全部就绪后集中分析 | 一次性生成全量快照 |

产物落在 `beans.output-root-dir` 下；拦截器通道落在 `interceptor.output-root-dir`（默认
`~/cn.lizl.project.tool.analysis-result/interceptor`，其下按日期建子目录）。两边目录互不干扰。

### 4. 访问端点

| 请求 | 组 | 说明 |
|---|---|---|
| `GET /beans` | beans | Bean 列表页面 |
| `POST /beans` | beans | Bean 名称列表（JSON 字符串数组） |
| `GET /bean/{beanName}` | beans | 指定 Bean 的分析结果 |
| `GET /interceptor` | interceptor | 拦截器管理页 |
| `GET /interceptor/state` | interceptor | 状态 JSON |
| `GET /interceptor/list` | interceptor | 已捕获产物名列表 |
| `GET /interceptor/output/{name}` | interceptor | 指定产物内容 |
| `POST /interceptor?active=&include=&exclude=` | interceptor | 运行时热改捕获开关与路径（仅内存态） |

**根路径 `/` 是通道索引页**：只列出本进程真正装配了的通道，点卡片就进对应页面（两个都装配时给两张卡片）；
若某个通道没装配，页面下方会注明缺哪个开关。两个都没装配时，根路径直接返回 404 + 文案：

```
beans 与 interceptor 通道都未启用：请至少配置 cn.lizl.java-data-analyzer.beans.enable=true 或 cn.lizl.java-data-analyzer.interceptor.enable=true 后重启
```

**端点组跟着通道走**：对应通道没装配时该组返回 404，body 直接点明缺哪个开关，例如：

```
interceptor 通道未启用：本进程没有装配请求拦截器，请配置 cn.lizl.java-data-analyzer.interceptor.enable=true 后重启
```

均支持 `?rebuild=true` 触发重新分析。

**配了 `server.enable=true` 就意味着"这个服务必须起来"**：端口被占用、绑定地址非法等启动失败会**让宿主应用一起启动失败**。 异常信息里带上冲突端口与可选做法，例如：

```
JavaDataAnalyzer 独立 HTTP 服务启动失败（18080 端口）：Address already in use: bind。
可改用其它端口（cn.lizl.java-data-analyzer.server.port）、用 0 让系统分配，或关闭 cn.lizl.java-data-analyzer.server.enable
```

这样做的理由：静默降级时宿主照常启动、页面却永远打不开，比启动失败更难排查。

### 5. 请求拦截器通道

开启后本库包装 Spring MVC / WebFlux 的 handler 方法参数解析器，在参数解析完成时把**本次请求的实参**
按路径规则捕获并落盘为分析产物——适合回看线上偶发问题的入参现场。

```properties
# 装配开关：装不装这条通道（启动期一次性，配 false 则整条通道不存在）
cn.lizl.java-data-analyzer.interceptor.enable=true
# 运行期开关：装好后是否真的捕获（默认 false，需显式开启；可热改）
cn.lizl.java-data-analyzer.interceptor.active=true
cn.lizl.java-data-analyzer.interceptor.include-paths=/api/**
cn.lizl.java-data-analyzer.interceptor.exclude-paths=/api/health/**,/api/auth/login/**
```

**两个开关各管一段，不要混用**：

| 开关 | 生效时机 | 为 false 时 | 能否运行期改 |
|---|---|---|---|
| `interceptor.enable`（默认 false） | 启动期（装配条件） | 通道整条不存在：不包装参数解析器、无控制 Bean、`/interceptor` 404 | **不能**（装配无法撤销） |
| `interceptor.active`（默认 false） | 运行期（每个请求判定） | 通道在、端点可用，只是不捕获（**这也是默认状态**） | 能，`POST /interceptor?active=`，仅内存态 |

- 路径匹配为 **Ant 风格**；`include-paths` 为空 = 拦截所有请求；`exclude-paths` 优先于白名单
- **本通道不读 `beans.*` 的任何值**：`render-type`、`output-root-dir`、超时、包规则全部自成一套，
  没配就用本通道自己的默认值（渲染 HTML、目录 `.../interceptor`、超时 3000ms）
- 高频触发，建议 `interceptor.max-depth` / `max-node-count` 比 beans 段更小
- 异步写盘队列满载时短等待后降级为**调用线程同步落盘**，产物不丢
- `enable=true` 而 `active=false` 时通道装好但不捕获，启动日志会打印一行提示，别误判为功能失效

### 6. 完整配置示例

仓库内提供了可直接使用的完整示例，随 jar 发布：**jar 内 `analysis/spring/application-analysis.yaml`**
（源码仓库对应 `src/main/resources/analysis/spring/`；三块全量键 + 每项注释 + 三种加载方式，
解包 jar 或从源码仓库都能取到）。结构如下：

```yaml
cn:
  lizl:
    java-data-analyzer:

      server:                            # 两个通道共用
        enable: true
        port: 18080                      # 0 = 系统分配
        bind-address: 127.0.0.1
        path-prefix: /lizl-java-data-analyzer
        token: change-me
        auth-user: admin                 # Basic 认证，与下一项同时配置才启用
        auth-password: change-me

      beans:                             # beans 通道
        enable: true
        eager-to-analyze: true
        lazy: true
        ignore-bean-names: [someInternalBean]
        ignore-bean-name-pattern: '^(internal|temp)[-.].*'
        ignore-bean-packages: [org.springframework.]
        output-root-dir: ./analysis-output/beans
        render-type: HTML
        common-exclude-packages: [java., javax., sun.]
        common-include-packages: [com.example.model.]
        max-depth: 7
        max-node-count: 2000
        analysis-mode: AUTO_BY_COMPUTE
        # …其余分析项见示例文件

      interceptor:                       # interceptor 通道（独立默认值，不读上面）
        enable: true
        active: true
        include-paths: [/api/**]
        exclude-paths: [/api/health/**]
        output-root-dir: ./analysis-output/interceptor
        render-type: HTML
        max-depth: 5
        max-node-count: 1000
        analysis-timeout-millis: 3000
        analysis-mode: ONLY_INSTANCE
```

### 7. 配置项说明

**键名规则**：代码里 `setXxx` 之后的名字 kebab 化（`setEnableCollectionSimpleView` →
`enable-collection-simple-view`）。三块配置**各有一套同名字段**，互不回落。
仅存在于代码侧的 `setClassLoader`、`setThrowExceptionWhenStringClassNotFound`、
`setCommonExcludePackagesToDefault` 没有对应键。

**包过滤是两层叠加，不是替换**：运行级（配置 → `RunningConfig`）与全局级（`GlobalConfig`）都参与判定，
顺序为 `运行级 include → 运行级 exclude → 全局 include → 全局 exclude`。因此：

- 配置里的 `common-exclude-packages` 只会**追加**规则，**删不掉全局默认的**
  `java.` / `javax.` / `jakarta.` / `jdk.` / `sun.`
- 想把被默认排除的包（例如 `java.` 下的类）拉进分析，用 `common-include-packages`——运行级 include
  判定在全局 exclude 之前，能直接抢回
- 只接受**非空且无空白项**的数组：不写 / 留空 / `[]` 三者等价（都表示本层不追加规则），
  数组里的 `null` / 空白项会被过滤（`startsWith("")` 会命中一切类名）

#### server.*

| 配置项 | 类型 | 默认值 | 说明                                                 |
|---|---|---|------------------------------------------------------|
| `enable` | boolean | `false` | 是否启动 HTTP 服务（`enable` / `enabled` 都认）      |
| `port` | int | `18080` | 监听端口，`0` = 系统分配；**端口被占用会让启动失败** |
| `bind-address` | String | `127.0.0.1` | 绑定地址，默认仅本机可访问                           |
| `path-prefix` | String | `/lizl-java-data-analyzer` | 访问路径前缀                                         |
| `token` | String | 空 | 配置后须带 `?token=xxx` 或请求头 `X-Analysis-Token`  |
| `auth-user` | String | 空 | HTTP Basic 用户名，与密码同时配置才启用              |
| `auth-password` | String | 空 | HTTP Basic 密码，缺任一项则不启用                    |

#### beans.*

| 配置项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `enable` | boolean | `false` | 是否装配 bean 采集通道 |
| `eager-to-analyze` | boolean | `true` | `true` 每个 Bean 初始化后即登记；`false` 全部单例就绪后统一登记 |
| `lazy` | boolean | `true` | `true` 只登记、首次访问才分析；`false` 登记后立即分析 |
| `ignore-bean-names` | Set | 空 | 按 Bean 名称精确忽略 |
| `ignore-bean-name-pattern` | String | 空 | 按 Bean 名称正则忽略（`String.matches` 全匹配） |
| `ignore-bean-packages` | String[] | 空 | 按类、父类、父接口的类名包前缀忽略 |
| `output-root-dir` | String | 全局默认目录 | 本通道产物根目录 |
| `render-type` | RenderType | 全局配置（默认 `HTML`） | 产物格式 |
| `common-exclude-packages` / `common-include-packages` | String[] | 空 | 包规则，追加合并、非空才生效 |
| `analyze-static-field` | boolean | `false` | 是否分析静态字段 |
| `should-analyze-null-value` | boolean | `true` | 是否分析 null 值 |
| `max-depth` | int | `7` | 最大分析深度 |
| `max-node-count` | int | `2000` | 单次分析最多新建节点数，`≤0` 不限制 |
| `analysis-timeout-millis` | int | `0` | 单次分析耗时上限，`≤0` 不限制 |
| `analysis-mode` | AnalysisMode | 全局配置（默认 `AUTO_BY_COMPUTE`） | 分析模式；未配置时不再强制 `ONLY_INSTANCE` |
| `value-display-length-limit` | int | `800` | 字段值显示长度上限 |
| `collections-analyzing-count` | int | `10` | 集合最多展开元素数 |
| `default-to-expand` | boolean | `false` | HTML 折叠树默认是否展开 |
| `add-to-string-field` | boolean | `false` | 追加 `toString` 字段 |
| `enable-collection-simple-view` | boolean | `true` | 集合使用简单视图 |
| `mask-sensitive-values` | boolean | `true` | 敏感字段值脱敏为 `***` |

#### interceptor.*

除与 `beans.*` 同名的分析项外，本通道另有这些独有项：

| 配置项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `enable` | boolean | `false` | 装配开关（启动期一次性，装配后不可撤销） |
| `active` | boolean | `false` | 运行期捕获开关，可经 `/interceptor` 热改 |
| `include-paths` | List | 空 | Ant 风格白名单；空 = 拦截所有请求 |
| `exclude-paths` | List | 空 | Ant 风格黑名单，优先于白名单 |
| `output-root-dir` | String | `~/cn.lizl.project.tool.analysis-result/interceptor` | 本通道产物根目录（其下按日期建子目录） |
| `output-queue-capacity` | int | `64` | 异步写盘队列容量；满载降级为调用线程同步落盘 |
| `output-retention` | int | `3000` | 每个日期目录各自保留的产物上限，`≤0` 不限制 |
| `render-type` | RenderType | `HTML` | 本通道自带默认值，不继承 `beans.render-type` |
| `analysis-timeout-millis` | int | `3000` | 未配置也用安全默认 3000ms（请求线程同步分析，防卡死线程池） |

其余分析项（`common-*`、`analyze-static-field`、`max-depth`、`max-node-count`、`analysis-mode`、
`value-display-length-limit`、`collections-analyzing-count`、`default-to-expand`、`add-to-string-field`、
`mask-sensitive-values`、`enable-collection-simple-view`）与 `beans.*` 同名、同语义，
但**取值完全独立**，互不回落。

## 常见问题

| 现象 | 原因与处理 |
|---|---|
| 配了 `beans.enable=true` 但访问不到页面 | HTTP 服务是独立开关，还要配 `server.enable=true`；启动日志里有实际地址，端口被占用时只告警不报错 |
| 开了 `beans.enable` 但目录里一个产物都没有 | 若同时没开 `server.enable`：`lazy=true`（默认）只在首次访问时分析，没有网页入口就不会分析；要落盘请配 `lazy=false` |
| 访问 `/beans` 看到「beans 通道未启用」 | 只开了 `server.enable`，还得开 `beans.enable`（两个开关管不同的事） |
| 开了 `interceptor.enable=true` 但一个产物都没有 | `interceptor.active` 默认关闭，需显式配 `true`（或到管理页点开）；启动日志会有一行提示 |
| 报告里展开的全是 Spring / Netty 内部对象 | `common-exclude-packages` 写成了 `[]`（显式清空，默认排除包一并失效），删掉该键或留空即恢复默认 |
| `addAnalyzingClass("com.x.Y")` 没反应 | 类名找不到时默认静默跳过，确认类名与 `classLoader`；需要报错就开 `setThrowExceptionWhenStringClassNotFound(true)` |
| 页面里 `password` 之类仍是明文 | `mask-sensitive-values` 被关掉，或字段名不含 `password/token/secret/credential/apikey/authorization` 等子串（按名匹配，不是完备清单） |
| 分析跑很久或产物巨大 | 调小 `max-depth` / `max-node-count`，或设 `analysis-timeout-millis`；集合用 `collections-analyzing-count` 限制 |
| 想让 `java.` / `javax.` 下的类也展开 | 排除包是运行级与全局级**叠加**，`common-exclude-packages` 删不掉默认规则；改用 `common-include-packages` 指定包（判定在全局 exclude 之前） |
| `PUML_HTML` / `MERMAID_HTML` 打开后是空白或提示「未找到 xxx.js」 | 产物与同名 js 必须放在**同一目录**（移动产物时要把 js 一起带走）；也可从 jar 的 `analysis/js/` 里复制过去 |

---

## 依赖说明

| 依赖 | 作用 | 是否传递 |
|---|---|---|
| 前端引擎（mermaid / PlantUML JS） | `PUML_HTML` / `MERMAID_HTML` 的渲染，随 jar 分发到产物同目录 | 不在 Maven 依赖里，不占宿主类路径 |
| Spring Boot | 自动配置与 Bean 采集 | 否（`provided + optional`） |
| Lombok | 本库自身代码简化 | 否（仅编译期） |

## 安全须知

> 本库会把运行时对象数据**导出为可读报告**，并提供可选的 HTTP 查看通道。
> 使用前请确认以下风险边界，不要在不可信网络环境开启任何对外能力。

**输出落盘**

- 报告以明文写入输出目录（默认 `用户主目录/cn.lizl.project.tool.analysis-result`），无加密、无访问控制——
  目录权限由操作系统决定
- 报告包含对象的**字段值**：业务 DTO、请求参数、甚至凭证类字段都可能被完整展开
- **敏感值脱敏默认开启**（`mask-sensitive-values=true`）：字段名含 `password`、`token`、`secret`、
  `credential`、`apikey`、`authorization` 等子串时值渲染为 `***`（只挡值不挡结构）。这是按字段**名**的
  防御默认值，**不是完备清单**——密码放在任何非常规命名字段里仍会明文输出，不能因此放松对输出目录的管控

**请求拦截器通道**

- 拦截器通道装配后（`interceptor.enable=true` 且 `interceptor.active=true`）会捕获**请求处理器入参**并深度展开进报告，登录 DTO、请求头绑定值等可能含真实凭证
- `org.springframework.security.*`（`Authentication` / `SecurityContext` 等）默认排除
- 生产开启前先用 `interceptor.include-paths` / `exclude-paths` 圈定路径，并保持 `interceptor.mask-sensitive-values` 默认开启

**独立 HTTP 服务**

- 默认绑定 `127.0.0.1` 且**无鉴权**：本机任何程序、浏览器里打开的任何第三方网页（跨站请求）都能访问。
  上线前务必配置 `server.token` 或 `server.auth-user` / `server.auth-password`，并保持 `server.bind-address` 为回环地址
- token 放在 query（`?token=`）会被浏览器历史与访问日志留存；条件允许时优先用 HTTP Basic（凭证不进 URL），
  本库日志中也不会输出带 token 的完整地址
- 写操作（`POST /interceptor` 等）内置跨站校验：携带跨站 `Origin` 的请求会被 403 拒绝，**但这不是鉴权替代品**

**结论**：本库定位是**开发与调试辅助工具**，请勿把分析产物目录或 HTTP 服务暴露给不可信网络；
生产环境常开前请完成上述三项检查。

## 许可证

[Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)

**第三方许可披露**：本库分发的两个前端引擎都是 **MIT**，不引入 GPL 传播：

- `mermaid` 11.17.2（MIT）—— `MERMAID_HTML` 用
- `@plantuml/core` 1.2026.8（MIT；该从 1.2026.6 起改为 MIT，更早版本是 GPL-3.0-or-later）—— `PUML_HTML` 用，
  含配套的 `viz-global.js`（内嵌 Viz.js / Graphviz）
- 两者都以**资源文件**形式放在 jar 的 `analysis/js/` 下，只在生成产物时复制到输出目录，不进入你的类路径；
  来源、版本、许可原文见该目录的 `SOURCES.txt`
- 由它们**执行生成的图像产物**归你所有，不受引擎许可证约束

其余依赖：Spring（`provided + optional`，Apache-2.0）、Lombok（仅编译期，MIT）、jsr305（`provided`，Apache-2.0），均不随本库传递。

## 作者

**snownnlee** — [Gitee](https://gitee.com/snownnlee) · [GitHub](https://github.com/snownnlee)

## 相关链接

- 项目地址：[Gitee](https://gitee.com/snownnlee/lizl-java-data-analyzer)（主仓）· [GitHub](https://github.com/snownnlee/lizl-java-data-analyzer)（代码镜像）
- 问题反馈：[Gitee Issues](https://gitee.com/snownnlee/lizl-java-data-analyzer/issues)

欢迎提交 Issue 和 Pull Request。
