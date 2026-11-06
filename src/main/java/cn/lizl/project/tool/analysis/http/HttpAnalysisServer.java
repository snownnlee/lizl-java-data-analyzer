package cn.lizl.project.tool.analysis.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 基于 JDK 原生 {@link HttpServer} 的独立 HTTP 服务，不依赖任何 Web 容器，也不依赖 Spring。
 *
 * <p>本类只负责四件事：<b>生命周期</b>（端口/地址/线程/启停）、<b>鉴权</b>（token / HTTP Basic / 跨站写校验）、
 * <b>路由</b>（按 {@link HttpServiceHandler#supports} 把请求交给认领的处理器）、<b>根路径索引页</b>
 * （列出已挂载的处理器）。具体端点由各处理器自己实现，新增一种服务只需实现接口并
 * {@link #register(HttpServiceHandler)} 一次。
 *
 * <p>两种鉴权，都未配置时不校验，都配置时任一通过即可：{@link #setToken(String)}（query 或
 * {@value #TOKEN_HEADER} 请求头）、{@link #setBasicAuth(String, String)}（HTTP Basic）。
 * token 放在 query 里会被记进浏览器历史，条件允许时优先用 Basic。
 */
@SuppressWarnings({"HttpUrlsUsage", "UnusedReturnValue"})
public final class HttpAnalysisServer {

    public static final int DEFAULT_PORT = 18080;

    public static final String DEFAULT_BIND_ADDRESS = "127.0.0.1";

    public static final String DEFAULT_PATH_PREFIX = "/lizl-java-data-analyzer";

    public static final String TOKEN_HEADER = "X-Analysis-Token";

    /** 未通过 token 校验时的质询方案名；浏览器不认识该 scheme，因此不会弹登录框 */
    private static final String TOKEN_CHALLENGE = "Token";

    private static final String BASIC_CHALLENGE = "Basic";

    private static final String BASIC_REALM = "lizl-java-data-analyzer";

    private static final String AUTHORIZATION_HEADER = "Authorization";

    private static final String WWW_AUTHENTICATE_HEADER = "WWW-Authenticate";

    private static final String ORIGIN_HEADER = "Origin";

    private static final String REFERER_HEADER = "Referer";

    private static final String HOST_HEADER = "Host";

    /**
     * 默认放行的 Host。防 DNS rebinding：攻击者域名解析到回环后，页面 URL 与 Host 头一致，
     * 基于 Origin/Host 比较的跨站校验会失效，只有校验 Host 本身才能区分。
     */
    private static final Set<String> DEFAULT_ALLOWED_HOSTS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("127.0.0.1", "localhost", "::1", "0:0:0:0:0:0:0:1")));

    private final Logger log = Logger.getLogger(getClass().getName());

    /**
     * 有序注册表：索引页卡片顺序与注册顺序一致，路由也按此顺序先到先得。
     *
     * <p>写时复制：注册是低频操作，{@link #register} 复制一份改完再把引用指向新快照；读路径直接遍历
     * 当前引用，拿到的就是这份只读快照，不必再拷贝一次列表。
     *
     * <p>{@code volatile} 是读路径不加锁的前提：注册写入后立即可见，读方永远看到完整快照。
     * 发布出去的快照只读，遍历期间即便有并发注册也不会有结构变更。
     */
    private volatile List<HttpServiceHandler> handlers = Collections.emptyList();

    /** 索引页渲染（模板在 classpath 的 index.html，Java 侧只装配数据） */
    private final IndexPageRenderer indexPageRenderer = new IndexPageRenderer();

    private int port = DEFAULT_PORT;

    private String bindAddress = DEFAULT_BIND_ADDRESS;

    private String pathPrefix = DEFAULT_PATH_PREFIX;

    private String token;

    /** 预拼好的 {@code Basic base64(user:pass)}，为 null 表示未启用 Basic 认证 */
    private String basicCredential;

    /** 额外放行的 Host（归一化后的值），由 {@link #setAllowedHosts(String...)} 设置 */
    private final Set<String> allowedHosts = new HashSet<>();

    private int threads = 4;

    private HttpServer server;

    private ExecutorService executor;

    private int boundPort = -1;

    /** 只挂 interceptor 处理器（或处理器都由 setter/register 稍后指定）时用这个构造 */
    public HttpAnalysisServer() {
    }

    /**
     * 挂载一个服务处理器。重复注册同一实例为幂等操作；路由按注册顺序先到先得，
     * 处理器应只在 {@link HttpServiceHandler#supports} 里认领真正属于自己的请求。
     *
     * <p>写时复制：复制当前快照、追加，再把字段指向新快照。方法同步只为串行化「读字段—替换」这一段，
     * 读方拿到的是不可变快照，遍历过程不受后续注册影响。
     *
     * <p>在服务运行期注册时，追加前会先调 {@link HttpServiceHandler#onStart()}（与启动时的语义一致）；
     * 抛异常则本次注册不生效，注册表保持原样。
     */
    public synchronized HttpAnalysisServer register(HttpServiceHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("handler 不能为 null");
        }
        if (handlers.contains(handler)) {
            return this;
        }
        if (server != null) {
            handler.onStart();
        }
        List<HttpServiceHandler> updated = new ArrayList<>(handlers.size() + 1);
        updated.addAll(handlers);
        updated.add(handler);
        handlers = Collections.unmodifiableList(updated);
        return this;
    }

    /**
     * 监听端口；传 0 表示由系统分配空闲端口，启动后可通过 {@link #getBoundPort()} 获取。
     */
    public HttpAnalysisServer setPort(int port) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("端口必须位于 0-65535：" + port);
        }
        this.port = port;
        return this;
    }

    public HttpAnalysisServer setBindAddress(String bindAddress) {
        if (bindAddress == null || bindAddress.trim().isEmpty()) {
            throw new IllegalArgumentException("绑定地址不能为空");
        }
        this.bindAddress = bindAddress.trim();
        return this;
    }

    public HttpAnalysisServer setPathPrefix(String pathPrefix) {
        this.pathPrefix = normalizePrefix(pathPrefix);
        return this;
    }

    public HttpAnalysisServer setToken(String token) {
        this.token = (token == null || token.trim().isEmpty()) ? null : token.trim();
        return this;
    }

    /**
     * 启用 HTTP Basic 认证；用户名或密码缺一个时按「未启用」处理。
     */
    public HttpAnalysisServer setBasicAuth(String username, String password) {
        if (username == null || username.trim().isEmpty() || password == null || password.isEmpty()) {
            this.basicCredential = null;
        } else {
            this.basicCredential = BASIC_CHALLENGE + " " + Base64.getEncoder().encodeToString(
                    (username.trim() + ":" + password).getBytes(StandardCharsets.UTF_8));
        }
        return this;
    }

    public HttpAnalysisServer setThreads(int threads) {
        if (threads < 1) {
            throw new IllegalArgumentException("线程数至少为 1：" + threads);
        }
        this.threads = threads;
        return this;
    }

    /**
     * 设置额外放行的 Host，重复调用以最后一次为准；传 null 或空数组等于清空。
     *
     * <p>默认只放行回环地址与 {@code bindAddress} 本身。绑定 0.0.0.0、经反向代理或用局域网 IP 访问时，
     * Host 头会是代理域名/局域网 IP，必须在这里显式放行，否则请求一律 403。传入值应为纯主机名或 IP
     * （不带协议与端口），比较时忽略大小写、剥 IPv6 方括号与末尾点；不接收通配符。
     */
    public HttpAnalysisServer setAllowedHosts(String... hosts) {
        allowedHosts.clear();
        if (hosts != null) {
            for (String host : hosts) {
                String normalized = normalizeHost(host);
                if (normalized != null && !normalized.isEmpty()) {
                    allowedHosts.add(normalized);
                }
            }
        }
        return this;
    }

    public synchronized boolean isRunning() {
        return server != null;
    }

    /**
     * 实际监听端口；未启动时返回配置值。
     */
    public synchronized int getBoundPort() {
        return boundPort > 0 ? boundPort : port;
    }

    /**
     * 可直接粘贴到浏览器的访问地址（索引页，已带上 token）。
     *
     * <p>token 按 URL 编码后拼接，含 {@code + & # %}、空格等字符时也能直接复制打开。
     * 仅供使用方按需调用取地址用；日志一律走 {@link #logUrl(String)}，不落 token。
     */
    public synchronized String getAccessUrl() {
        return accessUrl();
    }

    private String accessUrl() {
        return "http://" + displayHost() + ':' + getBoundPort() + pathPrefix + tokenQuerySuffix();
    }

    /**
     * 索引页链接与 {@link #getAccessUrl()} 共用的 token 查询串（已 URL 编码）；未配置 token 时为空串。
     *
     * <p>必须编码：token 含 {@code +}、{@code &}、{@code #}、{@code %} 或空格时，原样拼接会被浏览器与服务端
     * 的解码破坏（{@code +} 会被 {@link java.net.URLDecoder} 还原成空格，{@code #} 之后整段被当成片段，
     * {@code &} 会被切成额外参数）——表现是复制出来的地址一律 401。
     */
    private String tokenQuerySuffix() {
        if (token == null || token.isEmpty()) {
            return "";
        }
        try {
            return "?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            // UTF-8 是 JVM 必须支持的编码，理论不可达
            throw new IllegalStateException("UTF-8 编码不可用", e);
        }
    }

    /** 日志用 URI：抹掉 {@code token} 参数的值，其余参数原样保留（排障要看 container/rebuild 等） */
    static String logUri(URI uri) {
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        String rawQuery = uri.getRawQuery();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return path;
        }
        StringBuilder out = new StringBuilder(path).append('?');
        String[] pairs = rawQuery.split("&", -1);
        for (int i = 0; i < pairs.length; i++) {
            if (i > 0) {
                out.append('&');
            }
            out.append(isTokenParameter(pairs[i]) ? "token=***" : pairs[i]);
        }
        return out.toString();
    }

    private static boolean isTokenParameter(String pair) {
        return pair.length() >= 5 && pair.regionMatches(true, 0, "token", 0, 5)
                && (pair.length() == 5 || pair.charAt(5) == '=');
    }

    /** 日志用的基础地址：不带 token（token 进访问日志/采集系统就是留了一份明文凭证） */
    private String logUrl(String path) {
        return "http://" + displayHost() + ':' + getBoundPort() + pathPrefix + path;
    }

    private String displayHost() {
        return ("0.0.0.0".equals(bindAddress) || "::".equals(bindAddress)) ? "localhost" : bindAddress;
    }

    /**
     * 启动服务；失败时抛出原始异常（{@link java.net.BindException} 等），并把自己这一轮
     * 已经创建的资源收干净——不留半启动的实例，调用方只管决定"要不要连累宿主启动"。
     */
    public synchronized HttpAnalysisServer start() throws IOException {
        if (server != null) {
            return this;
        }
        if (token == null && basicCredential == null && !isLoopbackBinding()) {
            throw new IllegalStateException("HTTP 服务绑定到非回环地址 " + bindAddress
                    + " 但未配置任何凭证，已拒绝启动。请配置 token 或 HTTP Basic"
                    + "（setToken / setBasicAuth，Spring 侧为 server.token 或 server.auth-user + server.auth-password），"
                    + "或把 bind-address 改回 127.0.0.1");
        }
        HttpServer created = null;
        ExecutorService createdExecutor = null;
        try {
            created = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
            createdExecutor = Executors.newFixedThreadPool(threads, new DaemonThreadFactory());
            created.setExecutor(createdExecutor);
            created.createContext(pathPrefix.isEmpty() ? "/" : pathPrefix, this::handle);
            created.start();
        } catch (IOException | RuntimeException e) {
            closeQuietly(created);
            if (createdExecutor != null) {
                createdExecutor.shutdownNow();
            }
            throw e;
        }

        this.server = created;
        this.executor = createdExecutor;
        this.boundPort = created.getAddress().getPort();
        try {
            startHandlers();
            log.info("JavaDataAnalyzer 独立 HTTP 服务已启动 ===> " + logUrl("/")
                    + (token == null ? "" : "（已启用 token 校验；完整带 token 的访问地址请用 getAccessUrl() 获取，不落日志）"));
            if (token == null && basicCredential == null) {
                log.warning("HTTP 服务未配置任何凭证，仅应在开发/调试环境使用；本机任意程序与第三方网页都可能读取报告，"
                        + "请勿在生产环境常开");
            }
            for (HttpServiceHandler handler : handlers) {
                if (handler.isAvailable()) {
                    String entryPath = handler.getEntryPath();
                    log.info("通道已挂载 ===> " + logUrl(entryPath == null ? "" : entryPath));
                }
            }
            if (availableHandlers().isEmpty()) {
                log.warning("HTTP 服务已启动，但没有任何可用通道（" + unavailableSummary() + "）");
            }
        } catch (RuntimeException e) {
            // 已在监听但收尾失败（处理器 onStart 或扩展点抛异常）：同样不留半启动实例，
            // 让宿主的启动失败干净落地，而不是留下一个占着端口的孤儿服务。
            // 已经回调过 onStart 的处理器由 startHandlers 按相反顺序收尾，这里只管服务本体。
            closeQuietly(created);
            createdExecutor.shutdownNow();
            this.server = null;
            this.executor = null;
            this.boundPort = 0;
            throw e;
        }
        return this;
    }

    /**
     * 按注册顺序回调 {@link HttpServiceHandler#onStart()}。
     *
     * <p>任一处理器抛异常时，把已经回调过的按相反顺序 {@link HttpServiceHandler#onStop()} 掉，再原样抛出——
     * 收掉服务本体与端口由 {@link #start()} 的失败分支负责。这样启动失败不会留下"半个已就绪"的处理器。
     */
    private void startHandlers() {
        List<HttpServiceHandler> started = new ArrayList<>(handlers.size());
        try {
            for (HttpServiceHandler handler : handlers) {
                handler.onStart();
                started.add(handler);
            }
        } catch (RuntimeException e) {
            stopHandlers(started);
            throw e;
        }
    }

    /**
     * 按相反顺序回调 {@link HttpServiceHandler#onStop()}：先起来的后停，与 {@link #startHandlers()} 对称。
     *
     * <p>单个处理器收尾失败只记日志、不中断其余处理器——资源释放是尽力而为，不能因为一个 handler
     * 让 {@code stop()} 半途而废。
     */
    private void stopHandlers(List<HttpServiceHandler> targets) {
        for (int i = targets.size() - 1; i >= 0; i--) {
            HttpServiceHandler handler = targets.get(i);
            try {
                handler.onStop();
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "处理器停止失败：" + handler.getClass().getName(), e);
            }
        }
    }

    /** 半启动清理用：{@code stop(0)} 对尚未 start 的实例可能抛异常，这里只做尽力而为 */
    private static void closeQuietly(HttpServer httpServer) {
        if (httpServer == null) {
            return;
        }
        try {
            httpServer.stop(0);
        } catch (RuntimeException ignored) {
            // 已经因为启动失败走到这里，收尾异常没有更多信息量
        }
    }

    /**
     * 停止服务。顺序固定：先停止接收新请求并关掉请求线程池，再按注册顺序的相反顺序回调
     * {@link HttpServiceHandler#onStop()}，让处理器有机会释放自己（及其 provider）的资源。
     *
     * <p>未启动时调用是空操作；处理器收尾异常只记日志。停止后可以再次 {@link #start()}，
     * 处理器会重新收到 {@link HttpServiceHandler#onStart()}。
     */
    public synchronized void stop() {
        if (server == null) {
            return;
        }
        try {
            server.stop(0);
        } finally {
            log.info("JavaDataAnalyzer 独立 HTTP 服务已停止（端口 " + getBoundPort() + "）");
            stopHandlers(handlers);
            server = null;
            boundPort = -1;
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
        }
    }

    // ---------------- 路由 ----------------

    private void handle(HttpExchange exchange) {
        try {
            if (!isAllowedHost(exchange)) {
                HttpResponses.text(exchange, 403, "host not allowed");
                return;
            }
            if (!isAuthorized(exchange)) {
                sendUnauthorized(exchange);
                return;
            }
            if (isCrossSiteWrite(exchange)) {
                HttpResponses.text(exchange, 403, "cross-site write request rejected");
                return;
            }

            String method = exchange.getRequestMethod();
            // 带副作用的 GET：rebuild 会在 HTTP 线程同步重分析（可占满线程池），跨站 <img> 不许触发
            if (("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method))
                    && HttpRequests.isRebuildRequested(exchange) && isCrossSiteRequest(exchange)) {
                HttpResponses.text(exchange, 403, "cross-site rebuild request rejected");
                return;
            }

            String rawPath = exchange.getRequestURI().getRawPath();
            String relativePath = pathPrefix.isEmpty()
                    ? rawPath
                    : (rawPath.length() > pathPrefix.length() ? rawPath.substring(pathPrefix.length()) : "");

            if (relativePath.isEmpty() || "/".equals(relativePath)) {
                handleIndex(exchange);
                return;
            }

            // 相对路径放进 attribute：处理器不知道 path-prefix（服务器运行时可配），supports 里从这里取
            exchange.setAttribute(HttpServiceHandler.RELATIVE_PATH_ATTRIBUTE, relativePath);
            HttpServiceHandler handler = resolve(exchange);
            if (handler == null) {
                HttpResponses.text(exchange, 404, "not found");
                return;
            }
            if (!handler.isAvailable()) {
                HttpResponses.text(exchange, 404, handler.unavailableMessage());
                return;
            }
            handler.handle(exchange, relativePath);
        } catch (Throwable throwable) {
            log.log(Level.WARNING, "处理请求失败：" + logUri(exchange.getRequestURI()), throwable);
            try {
                HttpResponses.text(exchange, 500, "internal server error");
            } catch (IOException ignored) {
                // 响应已提交，无法再写回错误
            }
        } finally {
            exchange.close();
        }
    }

    /**
     * 按注册顺序问每个处理器 {@link HttpServiceHandler#supports}，先到先得。
     * 匹配逻辑归处理器自管（默认实现做最长前缀），服务器不再做全局前缀仲裁。
     */
    private HttpServiceHandler resolve(HttpExchange exchange) {
        // 拿一次引用即可：快照只读，遍历期间即便有并发注册也不会有结构变更
        for (HttpServiceHandler handler : handlers) {
            if (handler.supports(exchange)) {
                return handler;
            }
        }
        return null;
    }

    // ---------------- 索引页 ----------------

    /**
     * 根路径索引页：只列出真正可用的处理器，点卡片就进对应页面。
     *
     * <p>全都不可用时返回 404 + 各处理器的缺失说明——这时没有可跳转的目标，给个空页面反而误导。
     * 链接带上 token（若配置了），否则点进去会被鉴权挡回 401。
     */
    private void handleIndex(HttpExchange exchange) throws IOException {
        List<HttpServiceHandler> available = availableHandlers();
        if (available.isEmpty()) {
            StringBuilder message = new StringBuilder("没有可用的通道：所有通道都未启用");
            for (HttpServiceHandler handler : handlers) {
                message.append(System.lineSeparator()).append("- ").append(handler.unavailableMessage());
            }
            HttpResponses.text(exchange, 404, message.toString());
            return;
        }
        HttpResponses.html(exchange, 200,
                indexPageRenderer.render(handlers, available, pathPrefix, tokenQuerySuffix()));
    }

    private List<HttpServiceHandler> availableHandlers() {
        List<HttpServiceHandler> available = new ArrayList<>();
        for (HttpServiceHandler handler : handlers) {
            if (handler.isAvailable() && handler.indexCard() != null) {
                available.add(handler);
            }
        }
        return available;
    }

    private String unavailableSummary() {
        StringBuilder summary = new StringBuilder();
        for (HttpServiceHandler handler : handlers) {
            if (!handler.isAvailable()) {
                if (summary.length() > 0) {
                    summary.append("；");
                }
                summary.append(handler.unavailableMessage());
            }
        }
        return summary.toString();
    }

    // ---------------- Host 白名单 ----------------

    /** 归一化 Host：去空白、转小写、剥 IPv6 方括号与末尾点。Locale.ROOT 规避土耳其语大小写问题。 */
    private static String normalizeHost(String host) {
        if (host == null) {
            return null;
        }
        String value = host.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        while (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    /** 绑定地址是否为回环：回环允许零配置启动，非回环必须配凭证。整个 127/8 段都是回环。 */
    private boolean isLoopbackBinding() {
        String address = normalizeHost(bindAddress);
        return address != null && ("localhost".equals(address) || address.startsWith("127.")
                || "::1".equals(address) || "0:0:0:0:0:0:0:1".equals(address));
    }

    /**
     * Host 头是否在白名单内。缺失 Host 视为非浏览器/内部客户端放行，与
     * {@link #isCrossSiteRequest} 对缺失 Origin 的口径一致；解析不出主机名的一律拒绝。
     */
    private boolean isAllowedHost(HttpExchange exchange) {
        String hostHeader = exchange.getRequestHeaders().getFirst(HOST_HEADER);
        if (hostHeader == null || hostHeader.trim().isEmpty()) {
            return true;
        }
        String host;
        try {
            host = normalizeHost(URI.create("http://" + hostHeader.trim()).getHost());
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (host == null || host.isEmpty()) {
            return false;
        }
        return DEFAULT_ALLOWED_HOSTS.contains(host)
                || host.equals(normalizeHost(bindAddress))
                || allowedHosts.contains(host);
    }

    // ---------------- 鉴权 ----------------

    /**
     * 是否通过鉴权。两种凭证都未配置时直接放行；都配置时任一通过即可。
     */
    private boolean isAuthorized(HttpExchange exchange) {
        boolean tokenConfigured = token != null;
        boolean basicConfigured = basicCredential != null;
        if (!tokenConfigured && !basicConfigured) {
            return true;
        }
        if (tokenConfigured && matchesToken(exchange)) {
            return true;
        }
        return basicConfigured && matchesBasicCredential(exchange);
    }

    /**
     * 写请求是否来自跨站页面（CSRF 防护）。
     *
     * <p>浏览器发起跨站请求时必带 {@code Origin}（POST 场景），旧浏览器降级看 {@code Referer}；
     * 两者都缺视为非浏览器客户端（curl、服务间调用），放行。带凭证（token/Basic）的请求同样过这道校验：
     * token 在 query 上时同站页面可携带，跨站页面拿不到值，校验不改变其结论，只多一层防御。
     */
    private boolean isCrossSiteWrite(HttpExchange exchange) {
        String method = exchange.getRequestMethod();
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) {
            return false;
        }
        return isCrossSiteRequest(exchange);
    }

    /**
     * 请求来源是否与 Host 跨站。GET 本身无副作用、一律放行（见 {@link #isCrossSiteWrite}），
     * 但带副作用的 GET（{@code ?rebuild=true} 强制重分析）会被跨站 {@code <img>} 驱动——
     * 浏览器跨站请求必带攻击页的 {@code Origin}/{@code Referer}，据此拦截；两者都缺视为
     * 非浏览器客户端（curl、服务间调用），放行。
     *
     * <p>比对 host 与端口：同机不同端口的页面（本地 dev server 等）同样算跨站。端口只在
     * {@code Origin} 与 {@code Host} 都显式给出时比较，避免反向代理/默认端口场景误判。
     */
    private boolean isCrossSiteRequest(HttpExchange exchange) {
        String source = exchange.getRequestHeaders().getFirst(ORIGIN_HEADER);
        if (source == null) {
            source = exchange.getRequestHeaders().getFirst(REFERER_HEADER);
        }
        if (source == null || source.trim().isEmpty()) {
            return false;
        }
        try {
            URI origin = URI.create(source.trim());
            String originHost = origin.getHost();
            if (originHost == null) {
                return true;
            }
            String hostHeader = exchange.getRequestHeaders().getFirst(HOST_HEADER);
            if (hostHeader == null || hostHeader.trim().isEmpty()) {
                return true;
            }
            URI target = URI.create("http://" + hostHeader.trim());
            String requestHost = target.getHost();
            if (requestHost == null || requestHost.isEmpty() || !originHost.equalsIgnoreCase(requestHost)) {
                return true;
            }
            // 端口只在两侧都显式给出时比较：反向代理场景下 Host 可能不带端口，
            // 用默认端口硬比会把正常的同站请求误判成跨站
            return origin.getPort() > 0 && target.getPort() > 0 && origin.getPort() != target.getPort();
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    /**
     * 写 401 并附质询头：RFC 7235 要求 401 至少带一个 {@code WWW-Authenticate}，否则浏览器不弹登录框、
     * 客户端也无从得知该用哪种方案。token 场景用自定义 scheme {@code Token}，浏览器不认识它，不会误弹登录框。
     */
    private void sendUnauthorized(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set(WWW_AUTHENTICATE_HEADER, basicCredential != null
                ? BASIC_CHALLENGE + " realm=\"" + BASIC_REALM + "\""
                : TOKEN_CHALLENGE + " realm=\"" + BASIC_REALM + "\"");
        HttpResponses.text(exchange, 401, "unauthorized");
    }

    /** query 与 header 两种携带方式任一正确即通过：query 里有错值不应遮蔽正确的 header */
    private boolean matchesToken(HttpExchange exchange) {
        String queryToken = HttpRequests.queryParameters(exchange).get("token");
        if (queryToken != null && constantTimeEquals(token, queryToken)) {
            return true;
        }
        String headerToken = exchange.getRequestHeaders().getFirst(TOKEN_HEADER);
        return headerToken != null && constantTimeEquals(token, headerToken);
    }

    private boolean matchesBasicCredential(HttpExchange exchange) {
        String provided = exchange.getRequestHeaders().getFirst(AUTHORIZATION_HEADER);
        return provided != null && constantTimeEquals(basicCredential, provided.trim());
    }

    /** 定时安全比较，避免按字节短路泄露已匹配的长度 */
    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private static String normalizePrefix(String pathPrefix) {
        String prefix = pathPrefix == null ? "" : pathPrefix.trim();
        if (prefix.isEmpty() || "/".equals(prefix)) {
            return "";
        }
        if (!prefix.startsWith("/")) {
            prefix = "/" + prefix;
        }
        while (prefix.length() > 1 && prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix;
    }

    private static final class DaemonThreadFactory implements ThreadFactory {

        private final AtomicInteger counter = new AtomicInteger(0);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "lizl-java-data-analyzer-http-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
