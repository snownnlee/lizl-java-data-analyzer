package cn.lizl.project.tool.analysis.spring.beans;

import cn.lizl.project.tool.analysis.api.OutputFileReceiver;
import cn.lizl.project.tool.analysis.api.RunningConfigurer;
import cn.lizl.project.tool.analysis.http.AnalysisProvider;
import cn.lizl.project.tool.analysis.http.BeansHttpServiceHandler;
import cn.lizl.project.tool.analysis.util.ThreadNames;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.SingletonBeanRegistry;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.lang.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * beans 通道的容器数据提供者：以 {@link BeanPostProcessor} 感知 Bean 初始化并登记单例引用，
 * 由 {@code BeansHttpServiceHandler} 在首次访问时按需分析。
 *
 * <p>登记时机由 {@code beans.eager-to-analyze} 决定：初始化即登记，或等全部单例就绪后统一补扫
 * （补扫用于覆盖早于本类创建、BPP 看不到的基础设施 Bean）。分析在单线程守护线程上串行执行
 * （线程名 {@code lizl-java-data-analyzer-analysis-SpringBeans}），不与业务争用线程池。
 */
public class SpringBeansAnalysisProvider
        implements EnvironmentAware, ApplicationContextAware, BeanFactoryAware,
        BeanPostProcessor, SmartInitializingSingleton, DisposableBean, AnalysisProvider {

    private static final String ZEROS = "00000000";

    /** 容器注册名：本类只对应 Spring 的 bean 采集通道，固定为特定名字 */
    private static final String ANALYSIS_PROVIDER_NAME = "SpringBeans";

    private Environment environment;

    private ApplicationContext applicationContext;

    private BeanFactory beanFactory;

    private final Logger log = Logger.getLogger(getClass().getName());

    private final String timestamp = String.valueOf(System.currentTimeMillis());

    private final AtomicInteger counter = new AtomicInteger(0);

    /**
     * 分析线程池。随服务启停重建：{@link #onStop()} 关掉并置 {@link #stopped}，停止期间不再新建线程；
     * {@link #onStart()} 复位后按需重建，因此 HTTP 服务反复启停不影响本容器。
     */
    private ExecutorService executorService = newExecutor();

    /** 已停止标记：停止期间不新建线程池，也不会把任务提交进已关闭的池 */
    private volatile boolean stopped;

    private final RunningConfigurer runningConfigurer;

    /**
     * 全部分析共用同一个 configurer，因此必须串行。分析本身是 CPU 密集的反射遍历，串行不构成瓶颈；
     * 每任务一份 configurer 虽免锁，但会让配置与渲染器缓存每次重建。
     */
    private final Object analyzeLock = new Object();

    private final BeansProperties properties;

    /**
     * 单例延期登记表。写入来自 BPP 回调——惰性单例可能在任意请求线程创建，并发 put 下
     * LinkedHashMap 会结构损坏，必须用并发容器；顺序不重要，展示顺序由 {@code beanNameOrder} 维护。
     */
    private final Map<Object, String> beanToBeanNameMap = new ConcurrentHashMap<>(256);

    /** 注册由单线程 executor 完成、读取来自 HTTP 线程，必须用并发容器 */
    private final Map<String, AnalysisFactory> beanNameToAnalysisFactoryMap = new ConcurrentHashMap<>(256);

    /** 稳定的展示顺序：map 换成并发容器后不再保序，单独记一份 */
    private final List<String> beanNameOrder = new CopyOnWriteArrayList<>();

    private final Map<String, File> beanNameToOutputFileMap = new ConcurrentHashMap<>(256);

    private boolean singletonsInstantiated;

    public SpringBeansAnalysisProvider(Supplier<RunningConfigurer> configurerSupplier,
                                       BeansProperties properties) {
        // 只创建一次，全程复用：配置与 render 实例都由后续每次分析共享
        this.runningConfigurer = configurerSupplier.get();
        this.properties = properties;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
    }

    @Override
    public Object postProcessAfterInitialization(@Nullable Object bean, @Nullable String beanName) throws BeansException {
        if (properties.shouldIgnore(bean, beanName)) {
            return bean;
        }
        if (isPrototypeScope(beanName)) {
            // 原型作用域每次创建都是新实例、beanName 却不变：直接覆盖登记，让旧实例随旧工厂一起回收；
            // 沿用「已存在就复用」会把第一个实例永久钉在 map 里。
            registerAndAnalyze(bean, beanName, true);
            return bean;
        }
        if (properties.isEagerToAnalyze() || singletonsInstantiated) {
            registerAndAnalyze(bean, beanName, false);
        } else {
            // 单例延期登记：这里用实例作键，容器本身也持有它们，不构成额外负担
            beanToBeanNameMap.put(bean, beanName);
        }
        return bean;
    }

    /**
     * 判断是否原型作用域。{@code isPrototype} 对不在容器里的名字（手工登记的 {@code "@environment"} 等）
     * 会抛 {@link org.springframework.beans.factory.NoSuchBeanDefinitionException}，这类对象按单例处理。
     */
    private boolean isPrototypeScope(String beanName) {
        if (beanName == null || beanFactory == null) {
            return false;
        }
        try {
            return beanFactory.isPrototype(beanName);
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public void afterSingletonsInstantiated() {
        registerAndAnalyze(this, "@javaDataAnalyzerLifecycleHandler", false);
        registerAndAnalyze(environment, "@environment", false);
        registerAndAnalyze(applicationContext, "@applicationContext", false);
        registerAndAnalyze(beanFactory, "@beanFactory", false);

        // 复制一份再遍历：这里是启动收尾，理论上不会再写入，但惰性初始化的 Bean 可能正在别的线程创建
        for (Map.Entry<Object, String> entry : new ArrayList<>(beanToBeanNameMap.entrySet())) {
            registerAndAnalyze(entry.getKey(), entry.getValue(), false);
        }
        beanToBeanNameMap.clear();

        registerMissedSingletons();

        singletonsInstantiated = true;
    }

    /**
     * 补采「早于采集器创建」的单例。
     *
     * <p>采集靠 {@link BeanPostProcessor} 回调，而它自己连同构造依赖（{@code BeansProperties}）都是在
     * {@code registerBeanPostProcessors()} 阶段建好的——那时本处理器还没注册进容器，Spring 会明确提示
     * 这些 Bean「not eligible for getting processed by all BeanPostProcessors」，于是它们永远进不了列表。
     *
     * <p>单例就绪后扫一遍容器里已经实例化的单例，按真实 Bean 名补上。用
     * {@link SingletonBeanRegistry#getSingleton} 只读已注册实例，不会反过来触发创建；已经在
     * {@code @environment} 之类合成名下登记过的实例按引用跳过，避免同一对象在列表里出现两次。
     *
     * <p>代价是列表里会多出 Spring 自身的基础设施单例（{@code systemProperties}、
     * {@code messageSource} 等）：这是「宁可多、不可漏」的取舍，噪音可以用
     * {@code ignore-bean-names} / {@code ignore-bean-packages} 过滤。
     */
    private void registerMissedSingletons() {
        if (!(beanFactory instanceof SingletonBeanRegistry)) {
            return;
        }
        SingletonBeanRegistry singletonRegistry = (SingletonBeanRegistry) beanFactory;
        Set<Object> registeredBeans = Collections.newSetFromMap(new IdentityHashMap<>());
        for (AnalysisFactory analysisFactory : beanNameToAnalysisFactoryMap.values()) {
            registeredBeans.add(analysisFactory.bean);
        }
        for (String beanName : singletonRegistry.getSingletonNames()) {
            // "&" 开头是 FactoryBean 本体，不是容器对外暴露的那个 Bean
            if (beanName.startsWith("&")) {
                continue;
            }
            Object bean = singletonRegistry.getSingleton(beanName);
            if (bean == null || registeredBeans.contains(bean) || properties.shouldIgnore(bean, beanName)) {
                continue;
            }
            registerAndAnalyze(bean, beanName, false);
        }
    }

    /**
     * 登记（同步）+ 按需异步分析。
     *
     * <p>登记必须在调用线程上完成：整包丢给 executor 会让 {@code refresh()} 返回后立刻请求 {@code /beans}
     * 拿到空列表或残缺列表。登记只是建一个工厂对象，代价可忽略，真正耗时的分析仍是后台任务。
     */
    private void registerAndAnalyze(Object bean, String beanName, boolean replaceExisting) {
        AnalysisFactory existing = replaceExisting ? null : beanNameToAnalysisFactoryMap.get(beanName);
        AnalysisFactory analysisFactory = existing != null ? existing : register(bean, beanName);
        if (!properties.isLazy()) {
            ExecutorService current = executor();
            if (current != null) {
                current.execute(analysisFactory::doAnalyze);
            }
        }
    }

    /**
     * 登记一个新的分析工厂。
     *
     * <p>不同 Bean 可能由不同线程并发创建，所以 map 用并发容器、顺序表用写时复制；同名只入表一次靠
     * {@code put} 的返回值判断。同名覆盖时清掉旧产物缓存，避免拿到与当前实例不对应的文件。
     */
    private AnalysisFactory register(Object bean, String beanName) {
        AnalysisFactory created = new AnalysisFactory(counter.incrementAndGet(), bean, beanName);
        if (beanNameToAnalysisFactoryMap.put(beanName, created) == null) {
            beanNameOrder.add(beanName);
        } else {
            beanNameToOutputFileMap.remove(beanName);
        }
        return created;
    }

    /** 容器注册名固定：本类就是 Spring 的 bean 采集通道，与它在容器里的 Bean 名无关 */
    @Override
    public String getName() {
        return ANALYSIS_PROVIDER_NAME;
    }

    @Override
    public List<String> getBeanNames(boolean rebuild) {
        if (rebuild) {
            ExecutorService current = executor();
            if (current != null) {
                current.execute(() -> beanNameToAnalysisFactoryMap.values().forEach(AnalysisFactory::doAnalyze));
            }
        }
        return new ArrayList<>(beanNameOrder);
    }

    @Override
    public File getAnalyzedResult(String beanName, boolean rebuild) {
        if (rebuild) {
            beanNameToOutputFileMap.remove(beanName);
        }
        File resultFile = beanNameToOutputFileMap.get(beanName);
        if (resultFile == null) {
            AnalysisFactory analysisFactory = beanNameToAnalysisFactoryMap.get(beanName);
            if (analysisFactory != null) {
                resultFile = analysisFactory.getAnalysisResultFile(rebuild);
            }
        }
        return resultFile;
    }

    /**
     * Spring 容器销毁回调，等同 {@link #onStop()}：两条收尾链（容器关闭、HTTP 服务停止）落到同一处，
     * 重复调用无副作用。
     */
    @Override
    public void destroy() {
        onStop();
    }

    /**
     * 停止本容器：关掉分析线程池。之后 {@link #getAnalyzedResult} 仍可同步分析，只是不再有后台任务。
     *
     * <p>由 {@link BeansHttpServiceHandler} 随 HTTP 服务停止调用；Spring 容器关闭时由 {@link #destroy()} 调用。
     */
    @Override
    public void onStop() {
        ExecutorService current;
        synchronized (this) {
            stopped = true;
            current = executorService;
            executorService = null;
        }
        if (current != null) {
            // 优雅停止：正在跑的分析写完产物再退出，避免容器关闭时留下截断的报告
            // （与原先 DisposableBean.destroy() 的口径一致；手工停止服务也走这里）
            current.shutdown();
        }
    }

    /** 重新启用：复位停止标记并确保线程池可用（已关闭的按需重建，且不会立刻建线程） */
    @Override
    public void onStart() {
        synchronized (this) {
            stopped = false;
        }
        executor();
    }

    /** 取当前可用的分析线程池；已停止时返回 null，调用方跳过后台任务 */
    private synchronized ExecutorService executor() {
        if (stopped) {
            return null;
        }
        if (executorService == null || executorService.isShutdown()) {
            executorService = newExecutor();
        }
        return executorService;
    }

    private static ExecutorService newExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, ThreadNames.of("analysis", ANALYSIS_PROVIDER_NAME));
            thread.setDaemon(true);
            return thread;
        });
    }

    private class AnalysisFactory {

        final String count;
        final Object bean;
        final String beanName;
        volatile File analysisResultFile;

        AnalysisFactory(int count, Object bean, String beanName) {
            String countTmp = String.valueOf(count);
            if (countTmp.length() < ZEROS.length()) {
                countTmp = ZEROS.substring(0, ZEROS.length() - countTmp.length()) + count;
            }
            this.count = countTmp;
            this.bean = bean;
            this.beanName = beanName;
        }

        synchronized File getAnalysisResultFile(boolean rebuild) {
            if (rebuild || analysisResultFile == null) {
                doAnalyze();
            }
            return analysisResultFile;
        }

        synchronized void doAnalyze() {
            synchronized (analyzeLock) {
                try {
                    runningConfigurer
                            .addAnalyzingObject(bean)
                            .setOutputFileName(timestamp, count + "-" + beanName + "-" + bean.hashCode())
                            .run((OutputFileReceiver) outputFile -> {
                                OutputFileReceiver.DEFAULT.receive(outputFile);
                                beanNameToOutputFileMap.put(beanName, outputFile);
                                analysisResultFile = outputFile;
                            });
                } catch (Throwable throwable) {
                    log.log(Level.WARNING, "分析bean结构::" + count + "-" + beanName + "::发生异常：" + throwable.getMessage(), throwable);
                } finally {
                    // 成功失败都要清掉待分析对象，否则残留会混进下一个任务
                    runningConfigurer.clear();
                }
            }
        }
    }
}
