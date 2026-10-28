package cn.lizl.project.tool.analysis.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * <b>路由</b>（按路径把请求交给注册的 {@link HttpServiceHandler}）、<b>根路径索引页</b>（列出已挂载的处理器）。
 * 具体端点由各处理器自己实现，新增一种服务只需实现接口并 {@link #register(HttpServiceHandler)} 一次。
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

    private final Logger log = Logger.getLogger(getClass().getName());

    /** 有序注册表：索引页卡片顺序与注册顺序一致；同路径后注册的覆盖先注册的 */
    private final Map<String, HttpServiceHandler> handlers = new LinkedHashMap<>();

    /** 索引页渲染（模板在 classpath 的 index.html，Java 侧只装配数据） */
    private final IndexPageRenderer indexPageRenderer = new IndexPageRenderer();

    private int port = DEFAULT_PORT;

    private String bindAddress = DEFAULT_BIND_ADDRESS;

    private String pathPrefix = DEFAULT_PATH_PREFIX;

    private String token;

    /** 预拼好的 {@code Basic base64(user:pass)}，为 null 表示未启用 Basic 认证 */
    private String basicCredential;

    private int threads = 4;

    private HttpServer server;

    private ExecutorService executor;

    private int boundPort = -1;

    /** 只挂 interceptor 处理器（或处理器都由 setter/register 稍后指定）时用这个构造 */
    public HttpAnalysisServer() {
    }

    /** 便捷构造：等价于 {@code new HttpAnalysisServer().setAnalysisProvider(provider)} */
    public HttpAnalysisServer(AnalysisProvider provider) {
        setAnalysisProvider(provider);
    }

    /**
     * 挂载一个服务处理器。同一路径重复注册时后者覆盖前者，便于运行期替换实现。
     */
    public synchronized HttpAnalysisServer register(HttpServiceHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("handler 不能为 null");
        }
        for (String path : handler.getPaths()) {
            handlers.put(path, handler);
        }
        return this;
    }

    /**
     * 挂载 beans 通道；不设置时该组返回 404 + 提示。可在服务启动前或运行中调用。
     */
    public HttpAnalysisServer setAnalysisProvider(AnalysisProvider provider) {
        return register(new BeansHttpService(provider));
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
     * <p>仅供使用方按需调用取地址用；日志一律走 {@link #logUrl(String)}，不落 token。
     */
    public synchronized String getAccessUrl() {
        return accessUrl();
    }

    private String accessUrl() {
        StringBuilder url = new StringBuilder("http://").append(displayHost()).append(':').append(getBoundPort())
                .append(pathPrefix);
        if (token != null) {
            url.append("?token=").append(token);
        }
        return url.toString();
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
        log.info("JavaDataAnalyzer 独立 HTTP 服务已启动 ===> " + logUrl("/")
                + (token == null ? "" : "（已启用 token 校验；完整带 token 的访问地址请用 getAccessUrl() 获取，不落日志）"));
        for (HttpServiceHandler handler : distinctHandlers()) {
            if (handler.isAvailable()) {
                log.info("通道已挂载 ===> " + logUrl(handler.getPaths().iterator().next()));
            }
        }
        if (availableHandlers().isEmpty()) {
            log.warning("HTTP 服务已启动，但没有任何可用通道（" + unavailableSummary() + "）");
        }
        return this;
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

    public synchronized void stop() {
        if (server == null) {
            return;
        }
        try {
            server.stop(0);
            log.info("JavaDataAnalyzer 独立 HTTP 服务已停止（端口 " + getBoundPort() + "）");
        } finally {
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

            HttpServiceHandler handler = resolve(relativePath);
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
            log.log(Level.WARNING, "处理请求失败：" + exchange.getRequestURI(), throwable);
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
     * 最长前缀匹配：{@code /bean/alpha} 命中 {@code /bean/} 而不是 {@code /beans}；
     * 前缀必须止于路径分隔符，避免 {@code /interceptors} 误命中 {@code /interceptor}。
     */
    private HttpServiceHandler resolve(String relativePath) {
        HttpServiceHandler matched = null;
        int matchedLength = -1;
        synchronized (this) {
            for (Map.Entry<String, HttpServiceHandler> entry : handlers.entrySet()) {
                String path = entry.getKey();
                boolean hit = relativePath.equals(path)
                        || (relativePath.startsWith(path)
                        && (path.endsWith("/") || relativePath.charAt(path.length()) == '/'));
                if (hit && path.length() > matchedLength) {
                    matched = entry.getValue();
                    matchedLength = path.length();
                }
            }
        }
        return matched;
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
            for (HttpServiceHandler handler : distinctHandlers()) {
                message.append(System.lineSeparator()).append("- ").append(handler.unavailableMessage());
            }
            HttpResponses.text(exchange, 404, message.toString());
            return;
        }
        String query = token == null || token.isEmpty()
                ? "" : "?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8.name());
        HttpResponses.html(exchange, 200,
                indexPageRenderer.render(distinctHandlers(), available, pathPrefix, query));
    }

    private List<HttpServiceHandler> availableHandlers() {
        List<HttpServiceHandler> available = new ArrayList<>();
        for (HttpServiceHandler handler : distinctHandlers()) {
            if (handler.isAvailable() && handler.indexCard() != null) {
                available.add(handler);
            }
        }
        return available;
    }

    /** 去重：一个处理器可能注册了多个路径 */
    private List<HttpServiceHandler> distinctHandlers() {
        synchronized (this) {
            List<HttpServiceHandler> distinct = new ArrayList<>();
            for (HttpServiceHandler handler : handlers.values()) {
                if (!distinct.contains(handler)) {
                    distinct.add(handler);
                }
            }
            return distinct;
        }
    }

    private String unavailableSummary() {
        StringBuilder summary = new StringBuilder();
        for (HttpServiceHandler handler : distinctHandlers()) {
            if (!handler.isAvailable()) {
                if (summary.length() > 0) {
                    summary.append("；");
                }
                summary.append(handler.unavailableMessage());
            }
        }
        return summary.toString();
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
            String originHost = URI.create(source.trim()).getHost();
            if (originHost == null) {
                return true;
            }
            String hostHeader = exchange.getRequestHeaders().getFirst(HOST_HEADER);
            String requestHost = hostHeader == null ? "" : URI.create("http://" + hostHeader).getHost();
            return requestHost.isEmpty() || !originHost.equalsIgnoreCase(requestHost);
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

    private boolean matchesToken(HttpExchange exchange) {
        String provided = HttpRequests.queryParameters(exchange).get("token");
        if (provided == null) {
            provided = exchange.getRequestHeaders().getFirst(TOKEN_HEADER);
        }
        return provided != null && constantTimeEquals(token, provided);
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
        public Thread newThread(@Nonnull Runnable runnable) {
            Thread thread = new Thread(runnable, "lizl-java-data-analyzer-http-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
