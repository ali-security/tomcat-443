/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.catalina.core;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.Enumeration;
import java.util.EventListener;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.management.ListenerNotFoundException;
import javax.management.MBeanNotificationInfo;
import javax.management.Notification;
import javax.management.NotificationBroadcasterSupport;
import javax.management.NotificationEmitter;
import javax.management.NotificationFilter;
import javax.management.NotificationListener;
import javax.naming.NamingException;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextAttributeListener;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.ServletRegistration.Dynamic;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestAttributeListener;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.ServletSecurityElement;
import jakarta.servlet.SessionCookieConfig;
import jakarta.servlet.SessionTrackingMode;
import jakarta.servlet.descriptor.JspConfigDescriptor;
import jakarta.servlet.http.HttpSessionAttributeListener;
import jakarta.servlet.http.HttpSessionIdListener;
import jakarta.servlet.http.HttpSessionListener;

import org.apache.catalina.Authenticator;
import org.apache.catalina.Container;
import org.apache.catalina.ContainerListener;
import org.apache.catalina.Context;
import org.apache.catalina.CredentialHandler;
import org.apache.catalina.Globals;
import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleListener;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.Loader;
import org.apache.catalina.Manager;
import org.apache.catalina.Pipeline;
import org.apache.catalina.Realm;
import org.apache.catalina.ThreadBindingListener;
import org.apache.catalina.Valve;
import org.apache.catalina.WebResource;
import org.apache.catalina.WebResourceRoot;
import org.apache.catalina.Wrapper;
import org.apache.catalina.deploy.NamingResourcesImpl;
import org.apache.catalina.loader.WebappClassLoaderBase;
import org.apache.catalina.loader.WebappLoader;
import org.apache.catalina.session.StandardManager;
import org.apache.catalina.util.CharsetMapper;
import org.apache.catalina.util.ContextName;
import org.apache.catalina.util.ErrorPageSupport;
import org.apache.catalina.util.URLEncoder;
import org.apache.catalina.webresources.StandardRoot;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.naming.ContextBindings;
import org.apache.tomcat.InstanceManager;
import org.apache.tomcat.InstanceManagerBindings;
import org.apache.tomcat.JarScanner;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.buf.StringUtils;
import org.apache.tomcat.util.compat.JreCompat;
import org.apache.tomcat.util.descriptor.XmlIdentifiers;
import org.apache.tomcat.util.descriptor.web.ApplicationParameter;
import org.apache.tomcat.util.descriptor.web.ErrorPage;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.apache.tomcat.util.descriptor.web.Injectable;
import org.apache.tomcat.util.descriptor.web.InjectionTarget;
import org.apache.tomcat.util.descriptor.web.LoginConfig;
import org.apache.tomcat.util.descriptor.web.MessageDestination;
import org.apache.tomcat.util.descriptor.web.SecurityCollection;
import org.apache.tomcat.util.descriptor.web.SecurityConstraint;
import org.apache.tomcat.util.http.CookieProcessor;
import org.apache.tomcat.util.http.Rfc6265CookieProcessor;
import org.apache.tomcat.util.scan.StandardJarScanner;
import org.apache.tomcat.util.security.PrivilegedGetTccl;
import org.apache.tomcat.util.security.PrivilegedSetTccl;
import org.apache.tomcat.util.threads.ScheduledThreadPoolExecutor;

/**
 * Standard implementation of the <b>Context</b> interface. Each child container must be a Wrapper implementation to
 * process the requests directed to a particular servlet.
 *
 * @author Craig R. McClanahan
 * @author Remy Maucherat
 */
public class StandardContext extends ContainerBase implements Context, NotificationEmitter {

    private static final Log log = LogFactory.getLog(StandardContext.class);


    // ----------------------------------------------------------- Constructors


    /**
     * Create a new StandardContext component with the default basic Valve.
     */
    public StandardContext() {

        super();
        pipeline.setBasic(new StandardContextValve());
        broadcaster = new NotificationBroadcasterSupport();
        // Set defaults
        if (!Globals.STRICT_SERVLET_COMPLIANCE) {
            // Strict servlet compliance requires all extension mapped servlets
            // to be checked against welcome files
            resourceOnlyServlets.add("jsp");
        }
    }


    // ----------------------------------------------------- Instance Variables

    /**
     * Allow multipart/form-data requests to be parsed even when the target servlet doesn't specify @MultipartConfig or
     * have a &lt;multipart-config&gt; element.
     */
    protected boolean allowCasualMultipartParsing = false;

    /**
     * Control whether remaining request data will be read (swallowed) even if the request violates a data size
     * constraint.
     */
    private boolean swallowAbortedUploads = true;

    /**
     * The alternate deployment descriptor name.
     */
    private String altDDName = null;


    /**
     * Lifecycle provider.
     */
    private InstanceManager instanceManager = null;


    /**
     * The antiResourceLocking flag for this Context.
     */
    private boolean antiResourceLocking = false;


    /**
     * The list of unique application listener class names configured for this application, in the order they were
     * encountered in the resulting merged web.xml file.
     */
    private CopyOnWriteArrayList<String> applicationListeners = new CopyOnWriteArrayList<>();

    /**
     * The set of application listeners that are required to have limited access to ServletContext methods. See Servlet
     * 3.1 section 4.4.
     */
    private final Set<Object> noPluggabilityListeners = new HashSet<>();

    /**
     * The list of instantiated application event listener objects. Note that SCIs and other code may use the
     * pluggability APIs to add listener instances directly to this list before the application starts.
     */
    private List<Object> applicationEventListenersList = new CopyOnWriteArrayList<>();


    /**
     * The set of instantiated application lifecycle listener objects. Note that SCIs and other code may use the
     * pluggability APIs to add listener instances directly to this list before the application starts.
     */
    private Object applicationLifecycleListenersObjects[] = new Object[0];


    /**
     * The ordered set of ServletContainerInitializers for this web application.
     */
    private Map<ServletContainerInitializer,Set<Class<?>>> initializers = new LinkedHashMap<>();


    /**
     * The set of application parameters defined for this application.
     */
    private ApplicationParameter applicationParameters[] = new ApplicationParameter[0];

    private final Object applicationParametersLock = new Object();


    /**
     * The broadcaster that sends j2ee notifications.
     */
    private NotificationBroadcasterSupport broadcaster = null;

    /**
     * The Locale to character set mapper for this application.
     */
    private CharsetMapper charsetMapper = null;


    /**
     * The Java class name of the CharsetMapper class to be created.
     */
    private String charsetMapperClass = "org.apache.catalina.util.CharsetMapper";


    /**
     * The URL of the XML descriptor for this context.
     */
    private URL configFile = null;


    /**
     * The "correctly configured" flag for this Context.
     */
    private boolean configured = false;


    /**
     * The security constraints for this web application.
     */
    private volatile SecurityConstraint constraints[] = new SecurityConstraint[0];

    private final Object constraintsLock = new Object();


    /**
     * The ServletContext implementation associated with this Context.
     */
    protected ApplicationContext context = null;

    /**
     * The wrapped version of the associated ServletContext that is presented to listeners that are required to have
     * limited access to ServletContext methods. See Servlet 3.1 section 4.4.
     */
    private NoPluggabilityServletContext noPluggabilityServletContext = null;


    /**
     * Should we attempt to use cookies for session id communication?
     */
    private boolean cookies = true;


    /**
     * Should we allow the <code>ServletContext.getContext()</code> method to access the context of other web
     * applications in this server?
     */
    private boolean crossContext = false;


    /**
     * Encoded path.
     */
    private String encodedPath = null;


    /**
     * Unencoded path for this web application.
     */
    private String path = null;


    /**
     * The "follow standard delegation model" flag that will be used to configure our ClassLoader. Graal cannot actually
     * load a class from the webapp classloader, so delegate by default.
     */
    private boolean delegate = JreCompat.isGraalAvailable();


    private boolean denyUncoveredHttpMethods;


    /**
     * The display name of this web application.
     */
    private String displayName = null;


    /**
     * Override the default context xml location.
     */
    private String defaultContextXml;


    /**
     * Override the default web xml location.
     */
    private String defaultWebXml;


    /**
     * The distributable flag for this web application.
     */
    private boolean distributable = false;


    /**
     * The document root for this web application.
     */
    private String docBase = null;


    private final ErrorPageSupport errorPageSupport = new ErrorPageSupport();

    /**
     * The set of filter configurations (and associated filter instances) we have initialized, keyed by filter name.
     */
    private Map<String,ApplicationFilterConfig> filterConfigs = new HashMap<>(); // Guarded by filterDefs


    /**
     * The set of filter definitions for this application, keyed by filter name.
     */
    private Map<String,FilterDef> filterDefs = new HashMap<>();


    /**
     * The set of filter mappings for this application, in the order they were defined in the deployment descriptor with
     * additional mappings added via the {@link ServletContext} possibly both before and after those defined in the
     * deployment descriptor.
     */
    private final ContextFilterMaps filterMaps = new ContextFilterMaps();

    /**
     * Ignore annotations.
     */
    private boolean ignoreAnnotations = false;


    /**
     * The Loader implementation with which this Container is associated.
     */
    private Loader loader = null;
    private final ReadWriteLock loaderLock = new ReentrantReadWriteLock();


    /**
     * The login configuration descriptor for this web application.
     */
    private LoginConfig loginConfig = null;


    /**
     * The Manager implementation with which this Container is associated.
     */
    protected Manager manager = null;
    private final ReadWriteLock managerLock = new ReentrantReadWriteLock();


    /**
     * The naming context listener for this web application.
     */
    private NamingContextListener namingContextListener = null;


    /**
     * The naming resources for this web application.
     */
    private NamingResourcesImpl namingResources = null;

    /**
     * The message destinations for this web application.
     */
    private HashMap<String,MessageDestination> messageDestinations = new HashMap<>();


    /**
     * The MIME mappings for this web application, keyed by extension.
     */
    private Map<String,String> mimeMappings = new HashMap<>();


    /**
     * The context initialization parameters for this web application, keyed by name.
     */
    private final Map<String,String> parameters = new ConcurrentHashMap<>();


    /**
     * The request processing pause flag (while reloading occurs)
     */
    private volatile boolean paused = false;


    /**
     * The public identifier of the DTD for the web application deployment descriptor version we are currently parsing.
     * This is used to support relaxed validation rules when processing version 2.2 web.xml files.
     */
    private String publicId = null;


    /**
     * The reloadable flag for this web application.
     */
    private boolean reloadable = false;


    /**
     * Unpack WAR property.
     */
    private boolean unpackWAR = true;


    /**
     * Context level override for default {@link StandardHost#isCopyXML()}.
     */
    private boolean copyXML = false;


    /**
     * The default context override flag for this web application.
     */
    private boolean override = false;


    /**
     * The original document root for this web application.
     */
    private String originalDocBase = null;


    /**
     * The privileged flag for this web application.
     */
    private boolean privileged = false;


    /**
     * Should the next call to <code>addWelcomeFile()</code> cause replacement of any existing welcome files? This will
     * be set before processing the web application's deployment descriptor, so that application specified choices
     * <strong>replace</strong>, rather than append to, those defined in the global descriptor.
     */
    private boolean replaceWelcomeFiles = false;


    /**
     * The security role mappings for this application, keyed by role name (as used within the application).
     */
    private Map<String,String> roleMappings = new HashMap<>();


    /**
     * The security roles for this application, keyed by role name.
     */
    private String securityRoles[] = new String[0];

    private final Object securityRolesLock = new Object();


    /**
     * The servlet mappings for this web application, keyed by matching pattern.
     */
    private Map<String,String> servletMappings = new HashMap<>();

    private final Object servletMappingsLock = new Object();


    /**
     * The session timeout (in minutes) for this web application.
     */
    private int sessionTimeout = 30;

    /**
     * The notification sequence number.
     */
    private AtomicLong sequenceNumber = new AtomicLong(0);


    /**
     * Set flag to true to cause the system.out and system.err to be redirected to the logger when executing a servlet.
     */
    private boolean swallowOutput = false;


    /**
     * Amount of ms that the container will wait for servlets to unload.
     */
    private long unloadDelay = 2000;


    /**
     * The watched resources for this application.
     */
    private String watchedResources[] = new String[0];

    private final Object watchedResourcesLock = new Object();


    /**
     * The welcome files for this application.
     */
    private String welcomeFiles[] = new String[0];

    private final Object welcomeFilesLock = new Object();


    /**
     * The set of classnames of LifecycleListeners that will be added to each newly created Wrapper by
     * <code>createWrapper()</code>.
     */
    private String wrapperLifecycles[] = new String[0];

    private final Object wrapperLifecyclesLock = new Object();

    /**
     * The set of classnames of ContainerListeners that will be added to each newly created Wrapper by
     * <code>createWrapper()</code>.
     */
    private String wrapperListeners[] = new String[0];

    private final Object wrapperListenersLock = new Object();

    /**
     * The pathname to the work directory for this context (relative to the server's home if not absolute).
     */
    private String workDir = null;


    /**
     * Java class name of the Wrapper class implementation we use.
     */
    private String wrapperClassName = StandardWrapper.class.getName();
    private Class<?> wrapperClass = null;


    /**
     * JNDI use flag.
     */
    private boolean useNaming = true;


    /**
     * Name of the associated naming context.
     */
    private String namingContextName = null;


    private WebResourceRoot resources;
    private final ReadWriteLock resourcesLock = new ReentrantReadWriteLock();

    private long startupTime;
    private long startTime;
    private long tldScanTime;

    /**
     * Name of the engine. If null, the domain is used.
     */
    private String j2EEApplication = "none";
    private String j2EEServer = "none";


    /**
     * Attribute value used to turn on/off XML validation for web.xml and web-fragment.xml files.
     */
    private boolean webXmlValidation = Globals.STRICT_SERVLET_COMPLIANCE;


    /**
     * Attribute value used to turn on/off XML namespace validation
     */
    private boolean webXmlNamespaceAware = Globals.STRICT_SERVLET_COMPLIANCE;


    /**
     * Attribute used to turn on/off the use of external entities.
     */
    private boolean xmlBlockExternal = true;


    /**
     * Attribute value used to turn on/off XML validation
     */
    private boolean tldValidation = Globals.STRICT_SERVLET_COMPLIANCE;


    /**
     * The name to use for session cookies. <code>null</code> indicates that the name is controlled by the application.
     */
    private String sessionCookieName;


    /**
     * The flag that indicates that session cookies should use HttpOnly
     */
    private boolean useHttpOnly = true;

    private boolean usePartitioned = false;


    /**
     * The domain to use for session cookies. <code>null</code> indicates that the domain is controlled by the
     * application.
     */
    private String sessionCookieDomain;


    /**
     * The path to use for session cookies. <code>null</code> indicates that the path is controlled by the application.
     */
    private String sessionCookiePath;


    /**
     * Is a / added to the end of the session cookie path to ensure browsers, particularly IE, don't send a session
     * cookie for context /foo with requests intended for context /foobar.
     */
    private boolean sessionCookiePathUsesTrailingSlash = false;


    /**
     * The Jar scanner to use to search for Jars that might contain configuration information such as TLDs or
     * web-fragment.xml files.
     */
    private JarScanner jarScanner = null;

    /**
     * Enables the RMI Target memory leak detection to be controlled. This is necessary since the detection can only
     * work if some of the modularity checks are disabled.
     */
    private boolean clearReferencesRmiTargets = true;

    /**
     * Should Tomcat attempt to terminate threads that have been started by the web application? Stopping threads is
     * performed via the deprecated (for good reason) <code>Thread.stop()</code> method and is likely to result in
     * instability. As such, enabling this should be viewed as an option of last resort in a development environment and
     * is not recommended in a production environment. If not specified, the default value of <code>false</code> will be
     * used.
     */
    private boolean clearReferencesStopThreads = false;

    /**
     * Should Tomcat attempt to terminate any {@link java.util.TimerThread}s that have been started by the web
     * application? If not specified, the default value of <code>false</code> will be used.
     */
    private boolean clearReferencesStopTimerThreads = false;

    /**
     * If an HttpClient keep-alive timer thread has been started by this web application and is still running, should
     * Tomcat change the context class loader from the current {@link ClassLoader} to {@link ClassLoader#getParent()} to
     * prevent a memory leak? Note that the keep-alive timer thread will stop on its own once the keep-alives all expire
     * however, on a busy system that might not happen for some time.
     */
    private boolean clearReferencesHttpClientKeepAliveThread = true;

    /**
     * Should Tomcat renew the threads of the thread pool when the application is stopped to avoid memory leaks because
     * of uncleaned ThreadLocal variables. This also requires that the threadRenewalDelay property of the
     * StandardThreadExecutor or ThreadPoolExecutor be set to a positive value.
     */
    private boolean renewThreadsWhenStoppingContext = true;

    /**
     * Should Tomcat attempt to clear references to classes loaded by the web application class loader from the
     * ObjectStreamClass caches?
     */
    private boolean clearReferencesObjectStreamClassCaches = true;

    /**
     * Should Tomcat attempt to clear references to classes loaded by this class loader from ThreadLocals?
     */
    private boolean clearReferencesThreadLocals = true;

    /**
     * Should Tomcat skip the memory leak checks when the web application is stopped as part of the process of shutting
     * down the JVM?
     */
    private boolean skipMemoryLeakChecksOnJvmShutdown = false;

    /**
     * Should the effective web.xml be logged when the context starts?
     */
    private boolean logEffectiveWebXml = false;

    private int effectiveMajorVersion = 3;

    private int effectiveMinorVersion = 0;

    private JspConfigDescriptor jspConfigDescriptor = null;

    private Set<String> resourceOnlyServlets = new HashSet<>();

    private String webappVersion = "";

    private boolean addWebinfClassesResources = false;

    private boolean fireRequestListenersOnForwards = false;

    /**
     * Servlets created via {@link ApplicationContext#createServlet(Class)} for tracking purposes.
     */
    private Set<Servlet> createdServlets = new HashSet<>();

    private boolean preemptiveAuthentication = false;

    private boolean sendRedirectBody = false;

    private boolean jndiExceptionOnFailedWrite = true;

    private Map<String,String> postConstructMethods = new HashMap<>();
    private Map<String,String> preDestroyMethods = new HashMap<>();

    private String containerSciFilter;

    private Boolean failCtxIfServletStartFails;

    protected static final ThreadBindingListener DEFAULT_NAMING_LISTENER = (new ThreadBindingListener() {
        @Override
        public void bind() {
        }

        @Override
        public void unbind() {
        }
    });
    protected ThreadBindingListener threadBindingListener = DEFAULT_NAMING_LISTENER;

    private final Object namingToken = new Object();

    private CookieProcessor cookieProcessor;

    private boolean validateClientProvidedNewSessionId = true;

    private boolean mapperContextRootRedirectEnabled = true;

    private boolean mapperDirectoryRedirectEnabled = false;

    private boolean useRelativeRedirects = !Globals.STRICT_SERVLET_COMPLIANCE;

    private boolean dispatchersUseEncodedPaths = true;

    private String requestEncoding = null;

    private String responseEncoding = null;

    private boolean allowMultipleLeadingForwardSlashInPath = false;

    private final AtomicLong inProgressAsyncCount = new AtomicLong(0);

    private boolean createUploadTargets = false;

    private boolean alwaysAccessSession = Globals.STRICT_SERVLET_COMPLIANCE;

    private boolean contextGetResourceRequiresSlash = Globals.STRICT_SERVLET_COMPLIANCE;

    private boolean dispatcherWrapsSameObject = Globals.STRICT_SERVLET_COMPLIANCE;

    private boolean parallelAnnotationScanning = false;

    private boolean useBloomFilterForArchives = false;

    // ----------------------------------------------------- Context Properties

    @Override
    public void setCreateUploadTargets(boolean createUploadTargets) {
        this.createUploadTargets = createUploadTargets;
    }


    @Override
    public boolean getCreateUploadTargets() {
        return createUploadTargets;
    }


    @Override
    public void incrementInProgressAsyncCount() {
        inProgressAsyncCount.incrementAndGet();
    }


    @Override
    public void decrementInProgressAsyncCount() {
        inProgressAsyncCount.decrementAndGet();
    }


    public long getInProgressAsyncCount() {
        return inProgressAsyncCount.get();
    }


    @Override
    public void setAllowMultipleLeadingForwardSlashInPath(boolean allowMultipleLeadingForwardSlashInPath) {
        this.allowMultipleLeadingForwardSlashInPath = allowMultipleLeadingForwardSlashInPath;
    }


    @Override
    public boolean getAllowMultipleLeadingForwardSlashInPath() {
        return allowMultipleLeadingForwardSlashInPath;
    }


    @Override
    public boolean getAlwaysAccessSession() {
        return alwaysAccessSession;
    }


    @Override
    public void setAlwaysAccessSession(boolean alwaysAccessSession) {
        this.alwaysAccessSession = alwaysAccessSession;
    }


    @Override
    public boolean getContextGetResourceRequiresSlash() {
        return contextGetResourceRequiresSlash;
    }


    @Override
    public void setContextGetResourceRequiresSlash(boolean contextGetResourceRequiresSlash) {
        this.contextGetResourceRequiresSlash = contextGetResourceRequiresSlash;
    }


    @Override
    public boolean getDispatcherWrapsSameObject() {
        return dispatcherWrapsSameObject;
    }


    @Override
    public void setDispatcherWrapsSameObject(boolean dispatcherWrapsSameObject) {
        this.dispatcherWrapsSameObject = dispatcherWrapsSameObject;
    }


    @Override
    public String getRequestCharacterEncoding() {
        return requestEncoding;
    }


    @Override
    public void setRequestCharacterEncoding(String requestEncoding) {
        this.requestEncoding = requestEncoding;
    }


    @Override
    public String getResponseCharacterEncoding() {
        return responseEncoding;
    }


    @Override
    public void setResponseCharacterEncoding(String responseEncoding) {
        /*
         * This ensures that the context response encoding is represented by a unique String object. This enables the
         * Default Servlet to differentiate between a Response using this default encoding and one that has been
         * explicitly configured.
         */
        if (responseEncoding == null) {
            this.responseEncoding = null;
        } else {
            this.responseEncoding = new String(responseEncoding);
        }
    }


    @Override
    public void setDispatchersUseEncodedPaths(boolean dispatchersUseEncodedPaths) {
        this.dispatchersUseEncodedPaths = dispatchersUseEncodedPaths;
    }


    /**
     * {@inheritDoc}
     * <p>
     * The default value for this implementation is {@code true}.
     */
    @Override
    public boolean getDispatchersUseEncodedPaths() {
        return dispatchersUseEncodedPaths;
    }


    @Override
    public void setUseRelativeRedirects(boolean useRelativeRedirects) {
        this.useRelativeRedirects = useRelativeRedirects;
    }


    /**
     * {@inheritDoc}
     * <p>
     * The default value for this implementation is {@code true}.
     */
    @Override
    public boolean getUseRelativeRedirects() {
        return useRelativeRedirects;
    }


    @Override
    public void setMapperContextRootRedirectEnabled(boolean mapperContextRootRedirectEnabled) {
        this.mapperContextRootRedirectEnabled = mapperContextRootRedirectEnabled;
    }


    /**
     * {@inheritDoc}
     * <p>
     * The default value for this implementation is {@code false}.
     */
    @Override
    public boolean getMapperContextRootRedirectEnabled() {
        return mapperContextRootRedirectEnabled;
    }


    @Override
    public void setMapperDirectoryRedirectEnabled(boolean mapperDirectoryRedirectEnabled) {
        this.mapperDirectoryRedirectEnabled = mapperDirectoryRedirectEnabled;
    }


    /**
     * {@inheritDoc}
     * <p>
     * The default value for this implementation is {@code false}.
     */
    @Override
    public boolean getMapperDirectoryRedirectEnabled() {
        return mapperDirectoryRedirectEnabled;
    }


    @Override
    public void setValidateClientProvidedNewSessionId(boolean validateClientProvidedNewSessionId) {
        this.validateClientProvidedNewSessionId = validateClientProvidedNewSessionId;
    }


    /**
     * {@inheritDoc}
     * <p>
     * The default value for this implementation is {@code true}.
     */
    @Override
    public boolean getValidateClientProvidedNewSessionId() {
        return validateClientProvidedNewSessionId;
    }


    @Override
    public void setCookieProcessor(CookieProcessor cookieProcessor) {
        if (cookieProcessor == null) {
            throw new IllegalArgumentException(sm.getString("standardContext.cookieProcessor.null"));
        }
        this.cookieProcessor = cookieProcessor;
    }


    @Override
    public CookieProcessor getCookieProcessor() {
        return cookieProcessor;
    }


    @Override
    public Object getNamingToken() {
        return namingToken;
    }


    @Override
    public void setContainerSciFilter(String containerSciFilter) {
        this.containerSciFilter = containerSciFilter;
    }


    @Override
    public String getContainerSciFilter() {
        return containerSciFilter;
    }


    @Override
    public boolean getSendRedirectBody() {
        return sendRedirectBody;
    }


    @Override
    public void setSendRedirectBody(boolean sendRedirectBody) {
        this.sendRedirectBody = sendRedirectBody;
    }


    @Override
    public boolean getPreemptiveAuthentication() {
        return preemptiveAuthentication;
    }


    @Override
    public void setPreemptiveAuthentication(boolean preemptiveAuthentication) {
        this.preemptiveAuthentication = preemptiveAuthentication;
    }


    @Override
    public void setFireRequestListenersOnForwards(boolean enable) {
        fireRequestListenersOnForwards = enable;
    }


    @Override
    public boolean getFireRequestListenersOnForwards() {
        return fireRequestListenersOnForwards;
    }


    @Override
    public void setAddWebinfClassesResources(boolean addWebinfClassesResources) {
        this.addWebinfClassesResources = addWebinfClassesResources;
    }


    @Override
    public boolean getAddWebinfClassesResources() {
        return addWebinfClassesResources;
    }


    @Override
    public void setWebappVersion(String webappVersion) {
        if (null == webappVersion) {
            this.webappVersion = "";
        } else {
            this.webappVersion = webappVersion;
        }
    }


    @Override
    public String getWebappVersion() {
        return webappVersion;
    }


    @Override
    public String getBaseName() {
        return new ContextName(path, webappVersion).getBaseName();
    }


    @Override
    public String getResourceOnlyServlets() {
        return StringUtils.join(resourceOnlyServlets);
    }


    @Override
    public void setResourceOnlyServlets(String resourceOnlyServlets) {
        this.resourceOnlyServlets.clear();
        if (resourceOnlyServlets == null) {
            return;
        }
        for (String servletName : resourceOnlyServlets.split(",")) {
            servletName = servletName.trim();
            if (servletName.length() > 0) {
                this.resourceOnlyServlets.add(servletName);
            }
        }
    }


    @Override
    public boolean isResourceOnlyServlet(String servletName) {
        return resourceOnlyServlets.contains(servletName);
    }


    @Override
    public int getEffectiveMajorVersion() {
        return effectiveMajorVersion;
    }

    @Override
    public void setEffectiveMajorVersion(int effectiveMajorVersion) {
        this.effectiveMajorVersion = effectiveMajorVersion;
    }

    @Override
    public int getEffectiveMinorVersion() {
        return effectiveMinorVersion;
    }

    @Override
    public void setEffectiveMinorVersion(int effectiveMinorVersion) {
        this.effectiveMinorVersion = effectiveMinorVersion;
    }

    @Override
    public void setLogEffectiveWebXml(boolean logEffectiveWebXml) {
        this.logEffectiveWebXml = logEffectiveWebXml;
    }

    @Override
    public boolean getLogEffectiveWebXml() {
        return logEffectiveWebXml;
    }

    @Override
    public Authenticator getAuthenticator() {
        Pipeline pipeline = getPipeline();
        if (pipeline != null) {
            Valve basic = pipeline.getBasic();
            if (basic instanceof Authenticator) {
                return (Authenticator) basic;
            }
            for (Valve valve : pipeline.getValves()) {
                if (valve instanceof Authenticator) {
                    return (Authenticator) valve;
                }
            }
        }
        return null;
    }

    @Override
    public JarScanner getJarScanner() {
        if (jarScanner == null) {
            jarScanner = new StandardJarScanner();
        }
        return jarScanner;
    }


    @Override
    public void setJarScanner(JarScanner jarScanner) {
        this.jarScanner = jarScanner;
    }


    @Override
    public InstanceManager getInstanceManager() {
        return instanceManager;
    }


    @Override
    public void setInstanceManager(InstanceManager instanceManager) {
        this.instanceManager = instanceManager;
    }


    @Override
    public String getEncodedPath() {
        return encodedPath;
    }


    /**
     * Set to <code>true</code> to allow requests mapped to servlets that do not explicitly declare @MultipartConfig or
     * have &lt;multipart-config&gt; specified in web.xml to parse multipart/form-data requests.
     *
     * @param allowCasualMultipartParsing <code>true</code> to allow such casual parsing, <code>false</code> otherwise.
     */
    @Override
    public void setAllowCasualMultipartParsing(boolean allowCasualMultipartParsing) {
        this.allowCasualMultipartParsing = allowCasualMultipartParsing;
    }

    /**
     * Returns <code>true</code> if requests mapped to servlets without "multipart config" to parse multipart/form-data
     * requests anyway.
     *
     * @return <code>true</code> if requests mapped to servlets without "multipart config" to parse multipart/form-data
     *             requests, <code>false</code> otherwise.
     */
    @Override
    public boolean getAllowCasualMultipartParsing() {
        return this.allowCasualMultipartParsing;
    }

    /**
     * Set to <code>false</code> to disable request data swallowing after an upload was aborted due to size constraints.
     *
     * @param swallowAbortedUploads <code>false</code> to disable swallowing, <code>true</code> otherwise (default).
     */
    @Override
    public void setSwallowAbortedUploads(boolean swallowAbortedUploads) {
        this.swallowAbortedUploads = swallowAbortedUploads;
    }

    /**
     * Returns <code>true</code> if remaining request data will be read (swallowed) even the request violates a data
     * size constraint.
     *
     * @return <code>true</code> if data will be swallowed (default), <code>false</code> otherwise.
     */
    @Override
    public boolean getSwallowAbortedUploads() {
        return this.swallowAbortedUploads;
    }

    /**
     * Add a ServletContainerInitializer instance to this web application.
     *
     * @param sci     The instance to add
     * @param classes The classes in which the initializer expressed an interest
     */
    @Override
    public void addServletContainerInitializer(ServletContainerInitializer sci, Set<Class<?>> classes) {
        initializers.put(sci, classes);
    }


    /**
     * Return the "follow standard delegation model" flag used to configure our ClassLoader.
     *
     * @return <code>true</code> if classloading delegates to the parent classloader first
     */
    public boolean getDelegate() {
        return this.delegate;
    }


    /**
     * Set the "follow standard delegation model" flag used to configure our ClassLoader.
     *
     * @param delegate The new flag
     */
    public void setDelegate(boolean delegate) {

        boolean oldDelegate = this.delegate;
        this.delegate = delegate;
        support.firePropertyChange("delegate", oldDelegate, this.delegate);

    }


    /**
     * @return true if the internal naming support is used.
     */
    public boolean isUseNaming() {
        return useNaming;
    }


    /**
     * Enables or disables naming.
     *
     * @param useNaming <code>true</code> to enable the naming environment
     */
    public void setUseNaming(boolean useNaming) {
        this.useNaming = useNaming;
    }


    @Override
    public Object[] getApplicationEventListeners() {
        return applicationEventListenersList.toArray();
    }


    /**
     * {@inheritDoc} Note that this implementation is not thread safe. If two threads call this method concurrently, the
     * result may be either set of listeners or a the union of both.
     */
    @Override
    public void setApplicationEventListeners(Object listeners[]) {
        applicationEventListenersList.clear();
        if (listeners != null && listeners.length > 0) {
            applicationEventListenersList.addAll(Arrays.asList(listeners));
        }
    }


    /**
     * Add a listener to the end of the list of initialized application event listeners.
     *
     * @param listener The listener to add
     */
    public void addApplicationEventListener(Object listener) {
        applicationEventListenersList.add(listener);
    }


    @Override
    public Object[] getApplicationLifecycleListeners() {
        return applicationLifecycleListenersObjects;
    }


    /**
     * Store the set of initialized application lifecycle listener objects, in the order they were specified in the web
     * application deployment descriptor, for this application.
     *
     * @param listeners The set of instantiated listener objects.
     */
    @Override
    public void setApplicationLifecycleListeners(Object listeners[]) {
        applicationLifecycleListenersObjects = listeners;
    }


    /**
     * Add a listener to the end of the list of initialized application lifecycle listeners.
     *
     * @param listener The listener to add
     */
    public void addApplicationLifecycleListener(Object listener) {
        int len = applicationLifecycleListenersObjects.length;
        Object[] newListeners = Arrays.copyOf(applicationLifecycleListenersObjects, len + 1);
        newListeners[len] = listener;
        applicationLifecycleListenersObjects = newListeners;
    }


    /**
     * @return the antiResourceLocking flag for this Context.
     */
    public boolean getAntiResourceLocking() {
        return this.antiResourceLocking;
    }


    /**
     * Set the antiResourceLocking feature for this Context.
     *
     * @param antiResourceLocking The new flag value
     */
    public void setAntiResourceLocking(boolean antiResourceLocking) {

        boolean oldAntiResourceLocking = this.antiResourceLocking;
        this.antiResourceLocking = antiResourceLocking;
        support.firePropertyChange("antiResourceLocking", oldAntiResourceLocking, this.antiResourceLocking);

    }


    @Override
    @Deprecated
    public boolean getUseBloomFilterForArchives() {
        return this.useBloomFilterForArchives;
    }


    @Override
    @Deprecated
    public void setUseBloomFilterForArchives(boolean useBloomFilterForArchives) {
        boolean oldUseBloomFilterForArchives = this.useBloomFilterForArchives;
        this.useBloomFilterForArchives = useBloomFilterForArchives;
        support.firePropertyChange("useBloomFilterForArchives", oldUseBloomFilterForArchives,
                this.useBloomFilterForArchives);
    }


    @Override
    public void setParallelAnnotationScanning(boolean parallelAnnotationScanning) {

        boolean oldParallelAnnotationScanning = this.parallelAnnotationScanning;
        this.parallelAnnotationScanning = parallelAnnotationScanning;
        support.firePropertyChange("parallelAnnotationScanning", oldParallelAnnotationScanning,
                this.parallelAnnotationScanning);

    }


    @Override
    public boolean getParallelAnnotationScanning() {
        return this.parallelAnnotationScanning;
    }


    /**
     * @return the Locale to character set mapper for this Context.
     */
    public CharsetMapper getCharsetMapper() {

        // Create a mapper the first time it is requested
        if (this.charsetMapper == null) {
            try {
                Class<?> clazz = Class.forName(charsetMapperClass);
                this.charsetMapper = (CharsetMapper) clazz.getConstructor().newInstance();
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                this.charsetMapper = new CharsetMapper();
            }
        }

        return this.charsetMapper;

    }


    /**
     * Set the Locale to character set mapper for this Context.
     *
     * @param mapper The new mapper
     */
    public void setCharsetMapper(CharsetMapper mapper) {

        CharsetMapper oldCharsetMapper = this.charsetMapper;
        this.charsetMapper = mapper;
        if (mapper != null) {
            this.charsetMapperClass = mapper.getClass().getName();
        }
        support.firePropertyChange("charsetMapper", oldCharsetMapper, this.charsetMapper);

    }


    @Override
    public String getCharset(Locale locale) {
        return getCharsetMapper().getCharset(locale);
    }


    @Override
    public URL getConfigFile() {
        return this.configFile;
    }


    @Override
    public void setConfigFile(URL configFile) {
        this.configFile = configFile;
    }


    @Override
    public boolean getConfigured() {
        return this.configured;
    }


    /**
     * Set the "correctly configured" flag for this Context. This can be set to false by startup listeners that detect a
     * fatal configuration error to avoid the application from being made available.
     *
     * @param configured The new correctly configured flag
     */
    @Override
    public void setConfigured(boolean configured) {

        boolean oldConfigured = this.configured;
        this.configured = configured;
        support.firePropertyChange("configured", oldConfigured, this.configured);

    }


    @Override
    public boolean getCookies() {
        return this.cookies;
    }


    /**
     * Set the "use cookies for session ids" flag.
     *
     * @param cookies The new flag
     */
    @Override
    public void setCookies(boolean cookies) {

        boolean oldCookies = this.cookies;
        this.cookies = cookies;
        support.firePropertyChange("cookies", oldCookies, this.cookies);

    }


    /**
     * Gets the name to use for session cookies. Overrides any setting that may be specified by the application.
     *
     * @return The value of the default session cookie name or null if not specified
     */
    @Override
    public String getSessionCookieName() {
        return sessionCookieName;
    }


    /**
     * Sets the name to use for session cookies. Overrides any setting that may be specified by the application.
     *
     * @param sessionCookieName The name to use
     */
    @Override
    public void setSessionCookieName(String sessionCookieName) {
        String oldSessionCookieName = this.sessionCookieName;
        this.sessionCookieName = sessionCookieName;
        support.firePropertyChange("sessionCookieName", oldSessionCookieName, sessionCookieName);
    }


    /**
     * Gets the value of the use HttpOnly cookies for session cookies flag.
     *
     * @return <code>true</code> if the HttpOnly flag should be set on session cookies
     */
    @Override
    public boolean getUseHttpOnly() {
        return useHttpOnly;
    }


    /**
     * Sets the use HttpOnly cookies for session cookies flag.
     *
     * @param useHttpOnly Set to <code>true</code> to use HttpOnly cookies for session cookies
     */
    @Override
    public void setUseHttpOnly(boolean useHttpOnly) {
        boolean oldUseHttpOnly = this.useHttpOnly;
        this.useHttpOnly = useHttpOnly;
        support.firePropertyChange("useHttpOnly", oldUseHttpOnly, this.useHttpOnly);
    }


    @Override
    public boolean getUsePartitioned() {
        return usePartitioned;
    }


    @Override
    public void setUsePartitioned(boolean usePartitioned) {
        boolean oldUsePartitioned = this.usePartitioned;
        this.usePartitioned = usePartitioned;
        support.firePropertyChange("usePartitioned", oldUsePartitioned, this.usePartitioned);
    }


    /**
     * Gets the domain to use for session cookies. Overrides any setting that may be specified by the application.
     *
     * @return The value of the default session cookie domain or null if not specified
     */
    @Override
    public String getSessionCookieDomain() {
        return sessionCookieDomain;
    }


    /**
     * Sets the domain to use for session cookies. Overrides any setting that may be specified by the application.
     *
     * @param sessionCookieDomain The domain to use
     */
    @Override
    public void setSessionCookieDomain(String sessionCookieDomain) {
        String oldSessionCookieDomain = this.sessionCookieDomain;
        this.sessionCookieDomain = sessionCookieDomain;
        support.firePropertyChange("sessionCookieDomain", oldSessionCookieDomain, sessionCookieDomain);
    }


    /**
     * Gets the path to use for session cookies. Overrides any setting that may be specified by the application.
     *
     * @return The value of the default session cookie path or null if not specified
     */
    @Override
    public String getSessionCookiePath() {
        return sessionCookiePath;
    }


    /**
     * Sets the path to use for session cookies. Overrides any setting that may be specified by the application.
     *
     * @param sessionCookiePath The path to use
     */
    @Override
    public void setSessionCookiePath(String sessionCookiePath) {
        String oldSessionCookiePath = this.sessionCookiePath;
        this.sessionCookiePath = sessionCookiePath;
        support.firePropertyChange("sessionCookiePath", oldSessionCookiePath, sessionCookiePath);
    }


    @Override
    public boolean getSessionCookiePathUsesTrailingSlash() {
        return sessionCookiePathUsesTrailingSlash;
    }


    @Override
    public void setSessionCookiePathUsesTrailingSlash(boolean sessionCookiePathUsesTrailingSlash) {
        this.sessionCookiePathUsesTrailingSlash = sessionCookiePathUsesTrailingSlash;
    }


    @Override
    public boolean getCrossContext() {
        return this.crossContext;
    }


    /**
     * Set the "allow crossing servlet contexts" flag.
     *
     * @param crossContext The new cross contexts flag
     */
    @Override
    public void setCrossContext(boolean crossContext) {

        boolean oldCrossContext = this.crossContext;
        this.crossContext = crossContext;
        support.firePropertyChange("crossContext", oldCrossContext, this.crossContext);

    }

    public String getDefaultContextXml() {
        return defaultContextXml;
    }

    /**
     * Set the location of the default context xml that will be used. If not absolute, it'll be made relative to the
     * engine's base dir ( which defaults to catalina.base system property ).
     *
     * @param defaultContextXml The default web xml
     */
    public void setDefaultContextXml(String defaultContextXml) {
        this.defaultContextXml = defaultContextXml;
    }

    public String getDefaultWebXml() {
        return defaultWebXml;
    }

    /**
     * Set the location of the default web xml that will be used. If not absolute, it'll be made relative to the
     * engine's base dir ( which defaults to catalina.base system property ).
     *
     * @param defaultWebXml The default web xml
     */
    public void setDefaultWebXml(String defaultWebXml) {
        this.defaultWebXml = defaultWebXml;
    }

    /**
     * Gets the time (in milliseconds) it took to start this context.
     *
     * @return Time (in milliseconds) it took to start this context.
     */
    public long getStartupTime() {
        return startupTime;
    }

    public void setStartupTime(long startupTime) {
        this.startupTime = startupTime;
    }

    public long getTldScanTime() {
        return tldScanTime;
    }

    public void setTldScanTime(long tldScanTime) {
        this.tldScanTime = tldScanTime;
    }


    @Override
    public boolean getDenyUncoveredHttpMethods() {
        return denyUncoveredHttpMethods;
    }


    @Override
    public void setDenyUncoveredHttpMethods(boolean denyUncoveredHttpMethods) {
        this.denyUncoveredHttpMethods = denyUncoveredHttpMethods;
    }


    /**
     * @return the display name of this web application.
     */
    @Override
    public String getDisplayName() {
        return this.displayName;
    }


    /**
     * @return the alternate Deployment Descriptor name.
     */
    @Override
    public String getAltDDName() {
        return altDDName;
    }


    /**
     * Set an alternate Deployment Descriptor name.
     *
     * @param altDDName The new name
     */
    @Override
    public void setAltDDName(String altDDName) {
        this.altDDName = altDDName;
        if (context != null) {
            context.setAttribute(Globals.ALT_DD_ATTR, altDDName);
        }
    }


    /**
     * Set the display name of this web application.
     *
     * @param displayName The new display name
     */
    @Override
    public void setDisplayName(String displayName) {

        String oldDisplayName = this.displayName;
        this.displayName = displayName;
        support.firePropertyChange("displayName", oldDisplayName, this.displayName);
    }


    /**
     * @return the distributable flag for this web application.
     */
    @Override
    public boolean getDistributable() {
        return this.distributable;
    }

    /**
     * Set the distributable flag for this web application.
     *
     * @param distributable The new distributable flag
     */
    @Override
    public void setDistributable(boolean distributable) {
        boolean oldDistributable = this.distributable;
        this.distributable = distributable;
        support.firePropertyChange("distributable", oldDistributable, this.distributable);
    }


    @Override
    public String getDocBase() {
        return this.docBase;
    }


    @Override
    public void setDocBase(String docBase) {
        this.docBase = docBase;
    }


    public String getJ2EEApplication() {
        return j2EEApplication;
    }

    public void setJ2EEApplication(String j2EEApplication) {
        this.j2EEApplication = j2EEApplication;
    }

    public String getJ2EEServer() {
        return j2EEServer;
    }

    public void setJ2EEServer(String j2EEServer) {
        this.j2EEServer = j2EEServer;
    }


    @Override
    public Loader getLoader() {
        Lock readLock = loaderLock.readLock();
        readLock.lock();
        try {
            return loader;
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public void setLoader(Loader loader) {

        Lock writeLock = loaderLock.writeLock();
        writeLock.lock();
        Loader oldLoader = null;
        try {
            // Change components if necessary
            oldLoader = this.loader;
            if (oldLoader == loader) {
                return;
            }
            this.loader = loader;

            // Stop the old component if necessary
            if (getState().isAvailable() && (oldLoader != null) && (oldLoader instanceof Lifecycle)) {
                try {
                    ((Lifecycle) oldLoader).stop();
                } catch (LifecycleException e) {
                    log.error(sm.getString("standardContext.setLoader.stop"), e);
                }
            }

            // Start the new component if necessary
            if (loader != null) {
                loader.setContext(this);
            }
            if (getState().isAvailable() && (loader != null) && (loader instanceof Lifecycle)) {
                try {
                    ((Lifecycle) loader).start();
                } catch (LifecycleException e) {
                    log.error(sm.getString("standardContext.setLoader.start"), e);
                }
            }
        } finally {
            writeLock.unlock();
        }

        // Report this property change to interested listeners
        support.firePropertyChange("loader", oldLoader, loader);
    }


    @Override
    public Manager getManager() {
        Lock readLock = managerLock.readLock();
        readLock.lock();
        try {
            return manager;
        } finally {
            readLock.unlock();
        }
    }


    @Override
    public void setManager(Manager manager) {

        Lock writeLock = managerLock.writeLock();
        writeLock.lock();
        Manager oldManager = null;
        try {
            // Change components if necessary
            oldManager = this.manager;
            if (oldManager == manager) {
                return;
            }
            this.manager = manager;

            // Stop the old component if necessary
            if (oldManager instanceof Lifecycle) {
                try {
                    ((Lifecycle) oldManager).stop();
                    ((Lifecycle) oldManager).destroy();
                } catch (LifecycleException e) {
                    log.error(sm.getString("standardContext.setManager.stop"), e);
                }
            }

            // Start the new component if necessary
            if (manager != null) {
                manager.setContext(this);
            }
            if (getState().isAvailable() && manager instanceof Lifecycle) {
                try {
                    ((Lifecycle) manager).start();
                } catch (LifecycleException e) {
                    log.error(sm.getString("standardContext.setManager.start"), e);
                }
            }
        } finally {
            writeLock.unlock();
        }

        // Report this property change to interested listeners
        support.firePropertyChange("manager", oldManager, manager);
    }


    /**
     * @return the boolean on the annotations parsing.
     */
    @Override
    public boolean getIgnoreAnnotations() {
        return this.ignoreAnnotations;
    }


    /**
     * Set the boolean on the annotations parsing for this web application.
     *
     * @param ignoreAnnotations The boolean on the annotations parsing
     */
    @Override
    public void setIgnoreAnnotations(boolean ignoreAnnotations) {
        boolean oldIgnoreAnnotations = this.ignoreAnnotations;
        this.ignoreAnnotations = ignoreAnnotations;
        support.firePropertyChange("ignoreAnnotations", oldIgnoreAnnotations, this.ignoreAnnotations);
    }


    /**
     * @return the login configuration descriptor for this web application.
     */
    @Override
    public LoginConfig getLoginConfig() {
        return this.loginConfig;
    }


    /**
     * Set the login configuration descriptor for this web application.
     *
     * @param config The new login configuration
     */
    @Override
    public void setLoginConfig(LoginConfig config) {

        // Validate the incoming property value
        if (config == null) {
            throw new IllegalArgumentException(sm.getString("standardContext.loginConfig.required"));
        }
        String loginPage = config.getLoginPage();
        if ((loginPage != null) && !loginPage.startsWith("/")) {
            if (isServlet22()) {
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString("standardContext.loginConfig.loginWarning", loginPage));
                }
                config.setLoginPage("/" + loginPage);
            } else {
                throw new IllegalArgumentException(sm.getString("standardContext.loginConfig.loginPage", loginPage));
            }
        }
        String errorPage = config.getErrorPage();
        if ((errorPage != null) && !errorPage.startsWith("/")) {
            if (isServlet22()) {
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString("standardContext.loginConfig.errorWarning", errorPage));
                }
                config.setErrorPage("/" + errorPage);
            } else {
                throw new IllegalArgumentException(sm.getString("standardContext.loginConfig.errorPage", errorPage));
            }
        }

        // Process the property setting change
        LoginConfig oldLoginConfig = this.loginConfig;
        this.loginConfig = config;
        support.firePropertyChange("loginConfig", oldLoginConfig, this.loginConfig);

    }


    /**
     * @return the naming resources associated with this web application.
     */
    @Override
    public NamingResourcesImpl getNamingResources() {
        if (namingResources == null) {
            setNamingResources(new NamingResourcesImpl());
        }
        return namingResources;
    }


    /**
     * Set the naming resources for this web application.
     *
     * @param namingResources The new naming resources
     */
    @Override
    public void setNamingResources(NamingResourcesImpl namingResources) {

        // Process the property setting change
        NamingResourcesImpl oldNamingResources = this.namingResources;
        this.namingResources = namingResources;
        if (namingResources != null) {
            namingResources.setContainer(this);
        }
        support.firePropertyChange("namingResources", oldNamingResources, this.namingResources);

        if (getState() == LifecycleState.NEW || getState() == LifecycleState.INITIALIZING ||
                getState() == LifecycleState.INITIALIZED) {
            // NEW will occur if Context is defined in server.xml
            // At this point getObjectKeyPropertiesNameOnly() will trigger an
            // NPE.
            // INITIALIZED will occur if the Context is defined in a context.xml
            // file
            // If started now, a second start will be attempted when the context
            // starts

            // In both cases, return and let context init the namingResources
            // when it starts
            return;
        }

        if (oldNamingResources != null) {
            try {
                oldNamingResources.stop();
                oldNamingResources.destroy();
            } catch (LifecycleException e) {
                log.error(sm.getString("standardContext.namingResource.destroy.fail"), e);
            }
        }
        if (namingResources != null) {
            try {
                namingResources.init();
                namingResources.start();
            } catch (LifecycleException e) {
                log.error(sm.getString("standardContext.namingResource.init.fail"), e);
            }
        }
    }


    /**
     * @return the context path for this Context.
     */
    @Override
    public String getPath() {
        return path;
    }


    /**
     * Set the context path for this Context.
     *
     * @param path The new context path
     */
    @Override
    public void setPath(String path) {
        boolean invalid = false;
        if (path == null || path.equals("/")) {
            invalid = true;
            this.path = "";
        } else if (path.isEmpty() || path.startsWith("/")) {
            this.path = path;
        } else {
            invalid = true;
            this.path = "/" + path;
        }
        if (this.path.endsWith("/")) {
            invalid = true;
            this.path = this.path.substring(0, this.path.length() - 1);
        }
        if (invalid) {
            log.warn(sm.getString("standardContext.pathInvalid", path, this.path));
        }
        encodedPath = URLEncoder.DEFAULT.encode(this.path, StandardCharsets.UTF_8);
        if (getName() == null) {
            setName(this.path);
        }
    }


    /**
     * @return the public identifier of the deployment descriptor DTD that is currently being parsed.
     */
    @Override
    public String getPublicId() {
        return this.publicId;
    }


    /**
     * Set the public identifier of the deployment descriptor DTD that is currently being parsed.
     *
     * @param publicId The public identifier
     */
    @Override
    public void setPublicId(String publicId) {

        if (log.isTraceEnabled()) {
            log.trace("Setting deployment descriptor public ID to '" + publicId + "'");
        }

        String oldPublicId = this.publicId;
        this.publicId = publicId;
        support.firePropertyChange("publicId", oldPublicId, publicId);

    }


    /**
     * @return the reloadable flag for this web application.
     */
    @Override
    public boolean getReloadable() {
        return this.reloadable;
    }


    /**
     * @return the default context override flag for this web application.
     */
    @Override
    public boolean getOverride() {
        return this.override;
    }


    /**
     * @return the original document root for this Context. This can be an absolute pathname, a relative pathname, or a
     *             URL. Is only set as deployment has change docRoot!
     */
    public String getOriginalDocBase() {
        return this.originalDocBase;
    }

    /**
     * Set the original document root for this Context. This can be an absolute pathname, a relative pathname, or a URL.
     *
     * @param docBase The original document root
     */
    public void setOriginalDocBase(String docBase) {

        this.originalDocBase = docBase;
    }


    /**
     * @return the parent class loader (if any) for this web application. This call is meaningful only
     *             <strong>after</strong> a Loader has been configured.
     */
    @Override
    public ClassLoader getParentClassLoader() {
        if (parentClassLoader != null) {
            return parentClassLoader;
        }
        if (getPrivileged()) {
            return this.getClass().getClassLoader();
        } else if (parent != null) {
            return parent.getParentClassLoader();
        }
        return ClassLoader.getSystemClassLoader();
    }


    /**
     * @return the privileged flag for this web application.
     */
    @Override
    public boolean getPrivileged() {
        return this.privileged;
    }


    /**
     * Set the privileged flag for this web application.
     *
     * @param privileged The new privileged flag
     */
    @Override
    public void setPrivileged(boolean privileged) {

        boolean oldPrivileged = this.privileged;
        this.privileged = privileged;
        support.firePropertyChange("privileged", oldPrivileged, this.privileged);

    }


    /**
     * Set the reloadable flag for this web application.
     *
     * @param reloadable The new reloadable flag
     */
    @Override
    public void setReloadable(boolean reloadable) {

        boolean oldReloadable = this.reloadable;
        this.reloadable = reloadable;
        support.firePropertyChange("reloadable", oldReloadable, this.reloadable);

    }


    /**
     * Set the default context override flag for this web application.
     *
     * @param override The new override flag
     */
    @Override
    public void setOverride(boolean override) {

        boolean oldOverride = this.override;
        this.override = override;
        support.firePropertyChange("override", oldOverride, this.override);

    }


    /**
     * Set the "replace welcome files" property.
     *
     * @param replaceWelcomeFiles The new property value
     */
    public void setReplaceWelcomeFiles(boolean replaceWelcomeFiles) {

        boolean oldReplaceWelcomeFiles = this.replaceWelcomeFiles;
        this.replaceWelcomeFiles = replaceWelcomeFiles;
        support.firePropertyChange("replaceWelcomeFiles", oldReplaceWelcomeFiles, this.replaceWelcomeFiles);

    }


    /**
     * @return the servlet context for which this Context is a facade.
     */
    @Override
    public ServletContext getServletContext() {
        /*
         *  This method is called (multiple times) during context start which is single threaded so there is concurrency
         *  issue here.
         */
        if (context == null) {
            context = new ApplicationContext(this);
            if (altDDName != null) {
                context.setAttribute(Globals.ALT_DD_ATTR, altDDName);
            }
        }
        return context.getFacade();
    }


    /**
     * @return the default session timeout (in minutes) for this web application.
     */
    @Override
    public int getSessionTimeout() {
        return this.sessionTimeout;
    }


    /**
     * Set the default session timeout (in minutes) for this web application.
     *
     * @param timeout The new default session timeout
     */
    @Override
    public void setSessionTimeout(int timeout) {

        int oldSessionTimeout = this.sessionTimeout;
        /*
         * SRV.13.4 ("Deployment Descriptor"): If the timeout is 0 or less, the container ensures the default behaviour
         * of sessions is never to time out.
         */
        this.sessionTimeout = (timeout == 0) ? -1 : timeout;
        support.firePropertyChange("sessionTimeout", oldSessionTimeout, this.sessionTimeout);

    }


    /**
     * @return the value of the swallowOutput flag.
     */
    @Override
    public boolean getSwallowOutput() {
        return this.swallowOutput;
    }


    /**
     * Set the value of the swallowOutput flag. If set to true, the system.out and system.err will be redirected to the
     * logger during a servlet execution.
     *
     * @param swallowOutput The new value
     */
    @Override
    public void setSwallowOutput(boolean swallowOutput) {

        boolean oldSwallowOutput = this.swallowOutput;
        this.swallowOutput = swallowOutput;
        support.firePropertyChange("swallowOutput", oldSwallowOutput, this.swallowOutput);

    }


    /**
     * @return the value of the unloadDelay flag.
     */
    public long getUnloadDelay() {
        return this.unloadDelay;
    }


    /**
     * Set the value of the unloadDelay flag, which represents the amount of ms that the container will wait when
     * unloading servlets. Setting this to a small value may cause more requests to fail to complete when stopping a web
     * application.
     *
     * @param unloadDelay The new value
     */
    public void setUnloadDelay(long unloadDelay) {

        long oldUnloadDelay = this.unloadDelay;
        this.unloadDelay = unloadDelay;
        support.firePropertyChange("unloadDelay", Long.valueOf(oldUnloadDelay), Long.valueOf(this.unloadDelay));

    }


    /**
     * @return unpack WAR flag.
     */
    public boolean getUnpackWAR() {
        return unpackWAR;
    }


    /**
     * Unpack WAR flag mutator.
     *
     * @param unpackWAR <code>true</code> to unpack WARs on deployment
     */
    public void setUnpackWAR(boolean unpackWAR) {
        this.unpackWAR = unpackWAR;
    }


    /**
     * Flag which indicates if bundled context.xml files should be copied to the config folder. The doesn't occur by
     * default.
     *
     * @return <code>true</code> if the <code>META-INF/context.xml</code> file included in a WAR will be copied to the
     *             host configuration base folder on deployment
     */
    public boolean getCopyXML() {
        return copyXML;
    }


    /**
     * Allows copying a bundled context.xml file to the host configuration base folder on deployment.
     *
     * @param copyXML the new flag value
     */
    public void setCopyXML(boolean copyXML) {
        this.copyXML = copyXML;
    }


    /**
     * @return the Java class name of the Wrapper implementation used for servlets registered in this Context.
     */
    @Override
    public String getWrapperClass() {
        return this.wrapperClassName;
    }


    /**
     * Set the Java class name of the Wrapper implementation used for servlets registered in this Context.
     *
     * @param wrapperClassName The new wrapper class name
     *
     * @throws IllegalArgumentException if the specified wrapper class cannot be found or is not a subclass of
     *                                      StandardWrapper
     */
    @Override
    public void setWrapperClass(String wrapperClassName) {

        this.wrapperClassName = wrapperClassName;

        try {
            wrapperClass = Class.forName(wrapperClassName);
            if (!StandardWrapper.class.isAssignableFrom(wrapperClass)) {
                throw new IllegalArgumentException(
                        sm.getString("standardContext.invalidWrapperClass", wrapperClassName));
            }
        } catch (ClassNotFoundException cnfe) {
            throw new IllegalArgumentException(cnfe.getMessage());
        }
    }


    @Override
    public WebResourceRoot getResources() {
        Lock readLock = resourcesLock.readLock();
        readLock.lock();
        try {
            return resources;
        } finally {
            readLock.unlock();
        }
    }


    @Override
    public void setResources(WebResourceRoot resources) {

        Lock writeLock = resourcesLock.writeLock();
        writeLock.lock();
        WebResourceRoot oldResources = null;
        try {
            if (getState().isAvailable()) {
                throw new IllegalStateException(sm.getString("standardContext.resourcesStart"));
            }

            oldResources = this.resources;
            if (oldResources == resources) {
                return;
            }

            this.resources = resources;
            if (oldResources != null) {
                oldResources.setContext(null);
            }
            if (resources != null) {
                resources.setContext(this);
            }

            support.firePropertyChange("resources", oldResources, resources);
        } finally {
            writeLock.unlock();
        }
    }


    @Override
    public JspConfigDescriptor getJspConfigDescriptor() {
        return jspConfigDescriptor;
    }

    @Override
    public void setJspConfigDescriptor(JspConfigDescriptor descriptor) {
        this.jspConfigDescriptor = descriptor;
    }

    @Override
    public ThreadBindingListener getThreadBindingListener() {
        return threadBindingListener;
    }

    @Override
    public void setThreadBindingListener(ThreadBindingListener threadBindingListener) {
        this.threadBindingListener = threadBindingListener;
    }


    // ------------------------------------------------------ Public Properties

    /**
     * @return whether or not an attempt to modify the JNDI context will trigger an exception or if the request will be
     *             ignored.
     */
    public boolean getJndiExceptionOnFailedWrite() {
        return jndiExceptionOnFailedWrite;
    }


    /**
     * Controls whether or not an attempt to modify the JNDI context will trigger an exception or if the request will be
     * ignored.
     *
     * @param jndiExceptionOnFailedWrite <code>false</code> to avoid an exception
     */
    public void setJndiExceptionOnFailedWrite(boolean jndiExceptionOnFailedWrite) {
        this.jndiExceptionOnFailedWrite = jndiExceptionOnFailedWrite;
    }


    /**
     * @return the Locale to character set mapper class for this Context.
     */
    public String getCharsetMapperClass() {
        return this.charsetMapperClass;
    }


    /**
     * Set the Locale to character set mapper class for this Context.
     *
     * @param mapper The new mapper class
     */
    public void setCharsetMapperClass(String mapper) {

        String oldCharsetMapperClass = this.charsetMapperClass;
        this.charsetMapperClass = mapper;
        support.firePropertyChange("charsetMapperClass", oldCharsetMapperClass, this.charsetMapperClass);

    }


    /**
     * Get the absolute path to the work dir. To avoid duplication.
     *
     * @return The work path
     */
    public String getWorkPath() {
        if (getWorkDir() == null) {
            return null;
        }
        File workDir = new File(getWorkDir());
        if (!workDir.isAbsolute()) {
            try {
                workDir = new File(getCatalinaBase().getCanonicalFile(), getWorkDir());
            } catch (IOException e) {
                log.warn(sm.getString("standardContext.workPath", getName()), e);
            }
        }
        return workDir.getAbsolutePath();
    }

    /**
     * @return the work directory for this Context.
     */
    public String getWorkDir() {
        return this.workDir;
    }


    /**
     * Set the work directory for this Context.
     *
     * @param workDir The new work directory
     */
    public void setWorkDir(String workDir) {

        this.workDir = workDir;

        if (getState().isAvailable()) {
            postWorkDirectory();
        }
    }


    public boolean getClearReferencesRmiTargets() {
        return this.clearReferencesRmiTargets;
    }


    public void setClearReferencesRmiTargets(boolean clearReferencesRmiTargets) {
        boolean oldClearReferencesRmiTargets = this.clearReferencesRmiTargets;
        this.clearReferencesRmiTargets = clearReferencesRmiTargets;
        support.firePropertyChange("clearReferencesRmiTargets", oldClearReferencesRmiTargets,
                this.clearReferencesRmiTargets);
    }


    /**
     * @return the clearReferencesStopThreads flag for this Context.
     */
    public boolean getClearReferencesStopThreads() {
        return this.clearReferencesStopThreads;
    }


    /**
     * Set the clearReferencesStopThreads feature for this Context.
     *
     * @param clearReferencesStopThreads The new flag value
     */
    public void setClearReferencesStopThreads(boolean clearReferencesStopThreads) {

        boolean oldClearReferencesStopThreads = this.clearReferencesStopThreads;
        this.clearReferencesStopThreads = clearReferencesStopThreads;
        support.firePropertyChange("clearReferencesStopThreads", oldClearReferencesStopThreads,
                this.clearReferencesStopThreads);

    }


    /**
     * @return the clearReferencesStopTimerThreads flag for this Context.
     */
    public boolean getClearReferencesStopTimerThreads() {
        return this.clearReferencesStopTimerThreads;
    }


    /**
     * Set the clearReferencesStopTimerThreads feature for this Context.
     *
     * @param clearReferencesStopTimerThreads The new flag value
     */
    public void setClearReferencesStopTimerThreads(boolean clearReferencesStopTimerThreads) {

        boolean oldClearReferencesStopTimerThreads = this.clearReferencesStopTimerThreads;
        this.clearReferencesStopTimerThreads = clearReferencesStopTimerThreads;
        support.firePropertyChange("clearReferencesStopTimerThreads", oldClearReferencesStopTimerThreads,
                this.clearReferencesStopTimerThreads);
    }


    /**
     * @return the clearReferencesHttpClientKeepAliveThread flag for this Context.
     */
    public boolean getClearReferencesHttpClientKeepAliveThread() {
        return this.clearReferencesHttpClientKeepAliveThread;
    }


    /**
     * Set the clearReferencesHttpClientKeepAliveThread feature for this Context.
     *
     * @param clearReferencesHttpClientKeepAliveThread The new flag value
     */
    public void setClearReferencesHttpClientKeepAliveThread(boolean clearReferencesHttpClientKeepAliveThread) {
        this.clearReferencesHttpClientKeepAliveThread = clearReferencesHttpClientKeepAliveThread;
    }


    public boolean getRenewThreadsWhenStoppingContext() {
        return this.renewThreadsWhenStoppingContext;
    }

    public void setRenewThreadsWhenStoppingContext(boolean renewThreadsWhenStoppingContext) {
        boolean oldRenewThreadsWhenStoppingContext = this.renewThreadsWhenStoppingContext;
        this.renewThreadsWhenStoppingContext = renewThreadsWhenStoppingContext;
        support.firePropertyChange("renewThreadsWhenStoppingContext", oldRenewThreadsWhenStoppingContext,
                this.renewThreadsWhenStoppingContext);
    }


    public boolean getClearReferencesObjectStreamClassCaches() {
        return clearReferencesObjectStreamClassCaches;
    }


    public void setClearReferencesObjectStreamClassCaches(boolean clearReferencesObjectStreamClassCaches) {
        boolean oldClearReferencesObjectStreamClassCaches = this.clearReferencesObjectStreamClassCaches;
        this.clearReferencesObjectStreamClassCaches = clearReferencesObjectStreamClassCaches;
        support.firePropertyChange("clearReferencesObjectStreamClassCaches", oldClearReferencesObjectStreamClassCaches,
                this.clearReferencesObjectStreamClassCaches);
    }


    public boolean getClearReferencesThreadLocals() {
        return clearReferencesThreadLocals;
    }


    public void setClearReferencesThreadLocals(boolean clearReferencesThreadLocals) {
        boolean oldClearReferencesThreadLocals = this.clearReferencesThreadLocals;
        this.clearReferencesThreadLocals = clearReferencesThreadLocals;
        support.firePropertyChange("clearReferencesThreadLocals", oldClearReferencesThreadLocals,
                this.clearReferencesThreadLocals);
    }


    public boolean getSkipMemoryLeakChecksOnJvmShutdown() {
        return skipMemoryLeakChecksOnJvmShutdown;
    }


    public void setSkipMemoryLeakChecksOnJvmShutdown(boolean skipMemoryLeakChecksOnJvmShutdown) {
        this.skipMemoryLeakChecksOnJvmShutdown = skipMemoryLeakChecksOnJvmShutdown;
    }


    public Boolean getFailCtxIfServletStartFails() {
        return failCtxIfServletStartFails;
    }

    public void setFailCtxIfServletStartFails(Boolean failCtxIfServletStartFails) {
        Boolean oldFailCtxIfServletStartFails = this.failCtxIfServletStartFails;
        this.failCtxIfServletStartFails = failCtxIfServletStartFails;
        support.firePropertyChange("failCtxIfServletStartFails", oldFailCtxIfServletStartFails,
                failCtxIfServletStartFails);
    }

    protected boolean getComputedFailCtxIfServletStartFails() {
        if (failCtxIfServletStartFails != null) {
            return failCtxIfServletStartFails.booleanValue();
        }
        // else look at Host config
        if (getParent() instanceof StandardHost) {
            return ((StandardHost) getParent()).isFailCtxIfServletStartFails();
        }
        // else
        return false;
    }

    // -------------------------------------------------------- Context Methods

    /**
     * Add a new Listener class name to the set of Listeners configured for this application.
     *
     * @param listener Java class name of a listener class
     */
    @Override
    public void addApplicationListener(String listener) {
        if (applicationListeners.addIfAbsent(listener)) {
            fireContainerEvent("addApplicationListener", listener);
        } else {
            log.info(sm.getString("standardContext.duplicateListener", listener));
        }
    }


    /**
     * Add a new application parameter for this application.
     *
     * @param parameter The new application parameter
     */
    @Override
    public void addApplicationParameter(ApplicationParameter parameter) {

        synchronized (applicationParametersLock) {
            String newName = parameter.getName();
            for (ApplicationParameter p : applicationParameters) {
                if (newName.equals(p.getName()) && !p.getOverride()) {
                    return;
                }
            }
            ApplicationParameter results[] = Arrays.copyOf(applicationParameters, applicationParameters.length + 1);
            results[applicationParameters.length] = parameter;
            applicationParameters = results;
        }
        fireContainerEvent("addApplicationParameter", parameter);

    }


    /**
     * Add a child Container, only if the proposed child is an implementation of Wrapper.
     *
     * @param child Child container to be added
     *
     * @exception IllegalArgumentException if the proposed container is not an implementation of Wrapper
     */
    @Override
    public void addChild(Container child) {

        // Global JspServlet
        Wrapper oldJspServlet = null;

        if (!(child instanceof Wrapper)) {
            throw new IllegalArgumentException(sm.getString("standardContext.notWrapper"));
        }

        boolean isJspServlet = "jsp".equals(child.getName());

        // Allow webapp to override JspServlet inherited from global web.xml.
        if (isJspServlet) {
            oldJspServlet = (Wrapper) findChild("jsp");
            if (oldJspServlet != null) {
                removeChild(oldJspServlet);
            }
        }

        super.addChild(child);

        if (isJspServlet && oldJspServlet != null) {
            /*
             * The webapp-specific JspServlet inherits all the mappings specified in the global web.xml, and may add
             * additional ones.
             */
            String[] jspMappings = oldJspServlet.findMappings();
            for (int i = 0; jspMappings != null && i < jspMappings.length; i++) {
                addServletMappingDecoded(jspMappings[i], child.getName());
            }
        }
    }


    /**
     * Add a security constraint to the set for this web application.
     *
     * @param constraint the new security constraint
     */
    @Override
    public void addConstraint(SecurityConstraint constraint) {

        // Validate the proposed constraint
        SecurityCollection collections[] = constraint.findCollections();
        for (SecurityCollection collection : collections) {
            String patterns[] = collection.findPatterns();
            for (int j = 0; j < patterns.length; j++) {
                patterns[j] = adjustURLPattern(patterns[j]);
                if (!validateURLPattern(patterns[j])) {
                    throw new IllegalArgumentException(
                            sm.getString("standardContext.securityConstraint.pattern", patterns[j]));
                }
            }
            if (collection.findMethods().length > 0 && collection.findOmittedMethods().length > 0) {
                throw new IllegalArgumentException(sm.getString("standardContext.securityConstraint.mixHttpMethod"));
            }
        }

        // Add this constraint to the set for our web application
        synchronized (constraintsLock) {
            SecurityConstraint[] results = Arrays.copyOf(constraints, constraints.length + 1);
            results[constraints.length] = constraint;
            constraints = results;
        }

    }


    /**
     * Add an error page for the specified error or Java exception.
     *
     * @param errorPage The error page definition to be added
     */
    @Override
    public void addErrorPage(ErrorPage errorPage) {
        // Validate the input parameters
        if (errorPage == null) {
            throw new IllegalArgumentException(sm.getString("standardContext.errorPage.required"));
        }
        String location = errorPage.getLocation();
        if ((location != null) && !location.startsWith("/")) {
            if (isServlet22()) {
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString("standardContext.errorPage.warning", location));
                }
                errorPage.setLocation("/" + location);
            } else {
                throw new IllegalArgumentException(sm.getString("standardContext.errorPage.error", location));
            }
        }

        errorPageSupport.add(errorPage);
        fireContainerEvent("addErrorPage", errorPage);
    }


    /**
     * Add a filter definition to this Context.
     *
     * @param filterDef The filter definition to be added
     */
    @Override
    public void addFilterDef(FilterDef filterDef) {

        synchronized (filterDefs) {
            filterDefs.put(filterDef.getFilterName(), filterDef);
        }
        fireContainerEvent("addFilterDef", filterDef);

    }


    /**
     * Add a filter mapping to this Context at the end of the current set of filter mappings.
     *
     * @param filterMap The filter mapping to be added
     *
     * @exception IllegalArgumentException if the specified filter name does not match an existing filter definition, or
     *                                         the filter mapping is malformed
     */
    @Override
    public void addFilterMap(FilterMap filterMap) {
        validateFilterMap(filterMap);
        // Add this filter mapping to our registered set
        filterMaps.add(filterMap);
        fireContainerEvent("addFilterMap", filterMap);
    }


    /**
     * Add a filter mapping to this Context before the mappings defined in the deployment descriptor but after any other
     * mappings added via this method.
     *
     * @param filterMap The filter mapping to be added
     *
     * @exception IllegalArgumentException if the specified filter name does not match an existing filter definition, or
     *                                         the filter mapping is malformed
     */
    @Override
    public void addFilterMapBefore(FilterMap filterMap) {
        validateFilterMap(filterMap);
        // Add this filter mapping to our registered set
        filterMaps.addBefore(filterMap);
        fireContainerEvent("addFilterMap", filterMap);
    }


    /**
     * Validate the supplied FilterMap.
     *
     * @param filterMap the filter mapping
     */
    private void validateFilterMap(FilterMap filterMap) {
        // Validate the proposed filter mapping
        String filterName = filterMap.getFilterName();
        String[] servletNames = filterMap.getServletNames();
        String[] urlPatterns = filterMap.getURLPatterns();
        if (findFilterDef(filterName) == null) {
            throw new IllegalArgumentException(sm.getString("standardContext.filterMap.name", filterName));
        }

        if (!filterMap.getMatchAllServletNames() && !filterMap.getMatchAllUrlPatterns() && (servletNames.length == 0) &&
                (urlPatterns.length == 0)) {
            throw new IllegalArgumentException(sm.getString("standardContext.filterMap.either"));
        }
        for (String urlPattern : urlPatterns) {
            if (!validateURLPattern(urlPattern)) {
                throw new IllegalArgumentException(sm.getString("standardContext.filterMap.pattern", urlPattern));
            }
        }
    }


    /**
     * Add a Locale Encoding Mapping (see Sec 5.4 of Servlet spec 2.4)
     *
     * @param locale   locale to map an encoding for
     * @param encoding encoding to be used for a give locale
     */
    @Override
    public void addLocaleEncodingMappingParameter(String locale, String encoding) {
        getCharsetMapper().addCharsetMappingFromDeploymentDescriptor(locale, encoding);
    }


    /**
     * Add a message destination for this web application.
     *
     * @param md New message destination
     */
    public void addMessageDestination(MessageDestination md) {

        synchronized (messageDestinations) {
            messageDestinations.put(md.getName(), md);
        }
        fireContainerEvent("addMessageDestination", md.getName());

    }


    /**
     * Add a new MIME mapping, replacing any existing mapping for the specified extension.
     *
     * @param extension Filename extension being mapped
     * @param mimeType  Corresponding MIME type
     */
    @Override
    public void addMimeMapping(String extension, String mimeType) {

        synchronized (mimeMappings) {
            mimeMappings.put(extension.toLowerCase(Locale.ENGLISH), mimeType);
        }
        fireContainerEvent("addMimeMapping", extension);

    }


    /**
     * Add a new context initialization parameter.
     *
     * @param name  Name of the new parameter
     * @param value Value of the new parameter
     *
     * @exception IllegalArgumentException if the name or value is missing, or if this context initialization parameter
     *                                         has already been registered
     */
    @Override
    public void addParameter(String name, String value) {
        // Validate the proposed context initialization parameter
        if (name == null || value == null) {
            throw new IllegalArgumentException(sm.getString("standardContext.parameter.required"));
        }

        // Add this parameter to our defined set if not already present
        String oldValue = parameters.putIfAbsent(name, value);

        if (oldValue != null) {
            throw new IllegalArgumentException(sm.getString("standardContext.parameter.duplicate", name));
        }

        fireContainerEvent("addParameter", name);
    }


    /**
     * Add a security role reference for this web application.
     *
     * @param role Security role used in the application
     * @param link Actual security role to check for
     */
    @Override
    public void addRoleMapping(String role, String link) {

        synchronized (roleMappings) {
            roleMappings.put(role, link);
        }
        fireContainerEvent("addRoleMapping", role);

    }


    /**
     * Add a new security role for this web application.
     *
     * @param role New security role
     */
    @Override
    public void addSecurityRole(String role) {

        synchronized (securityRolesLock) {
            String[] results = Arrays.copyOf(securityRoles, securityRoles.length + 1);
            results[securityRoles.length] = role;
            securityRoles = results;
        }
        fireContainerEvent("addSecurityRole", role);

    }


    /**
     * Add a new servlet mapping, replacing any existing mapping for the specified pattern.
     *
     * @param pattern     URL pattern to be mapped
     * @param name        Name of the corresponding servlet to execute
     * @param jspWildCard true if name identifies the JspServlet and pattern contains a wildcard; false otherwise
     *
     * @exception IllegalArgumentException if the specified servlet name is not known to this Context
     */
    @Override
    public void addServletMappingDecoded(String pattern, String name, boolean jspWildCard) {
        // Validate the proposed mapping
        if (findChild(name) == null) {
            throw new IllegalArgumentException(sm.getString("standardContext.servletMap.name", name));
        }
        String adjustedPattern = adjustURLPattern(pattern);
        if (!validateURLPattern(adjustedPattern)) {
            throw new IllegalArgumentException(sm.getString("standardContext.servletMap.pattern", adjustedPattern));
        }

        // Add this mapping to our registered set
        synchronized (servletMappingsLock) {
            String name2 = servletMappings.get(adjustedPattern);
            if (name2 != null) {
                // Don't allow more than one servlet on the same pattern
                Wrapper wrapper = (Wrapper) findChild(name2);
                wrapper.removeMapping(adjustedPattern);
            }
            servletMappings.put(adjustedPattern, name);
        }
        Wrapper wrapper = (Wrapper) findChild(name);
        wrapper.addMapping(adjustedPattern);

        fireContainerEvent("addServletMapping", adjustedPattern);
    }


    /**
     * Add a new watched resource to the set recognized by this Context.
     *
     * @param name New watched resource file name
     */
    @Override
    public void addWatchedResource(String name) {

        synchronized (watchedResourcesLock) {
            String[] results = Arrays.copyOf(watchedResources, watchedResources.length + 1);
            results[watchedResources.length] = name;
            watchedResources = results;
        }
        fireContainerEvent("addWatchedResource", name);
    }


    /**
     * Add a new welcome file to the set recognized by this Context.
     *
     * @param name New welcome file name
     */
    @Override
    public void addWelcomeFile(String name) {

        synchronized (welcomeFilesLock) {
            // Welcome files from the application deployment descriptor
            // completely replace those from the default conf/web.xml file
            if (replaceWelcomeFiles) {
                fireContainerEvent(CLEAR_WELCOME_FILES_EVENT, null);
                welcomeFiles = new String[0];
                setReplaceWelcomeFiles(false);
            }
            String[] results = Arrays.copyOf(welcomeFiles, welcomeFiles.length + 1);
            results[welcomeFiles.length] = name;
            welcomeFiles = results;
        }
        if (this.getState().equals(LifecycleState.STARTED)) {
            fireContainerEvent(ADD_WELCOME_FILE_EVENT, name);
        }
    }


    /**
     * Add the classname of a LifecycleListener to be added to each Wrapper appended to this Context.
     *
     * @param listener Java class name of a LifecycleListener class
     */
    @Override
    public void addWrapperLifecycle(String listener) {

        synchronized (wrapperLifecyclesLock) {
            String[] results = Arrays.copyOf(wrapperLifecycles, wrapperLifecycles.length + 1);
            results[wrapperLifecycles.length] = listener;
            wrapperLifecycles = results;
        }
        fireContainerEvent("addWrapperLifecycle", listener);

    }


    /**
     * Add the classname of a ContainerListener to be added to each Wrapper appended to this Context.
     *
     * @param listener Java class name of a ContainerListener class
     */
    @Override
    public void addWrapperListener(String listener) {

        synchronized (wrapperListenersLock) {
            String[] results = Arrays.copyOf(wrapperListeners, wrapperListeners.length + 1);
            results[wrapperListeners.length] = listener;
            wrapperListeners = results;
        }
        fireContainerEvent("addWrapperListener", listener);

    }


    /**
     * Factory method to create and return a new Wrapper instance, of the Java implementation class appropriate for this
     * Context implementation. The constructor of the instantiated Wrapper will have been called, but no properties will
     * have been set.
     */
    @Override
    public Wrapper createWrapper() {

        Wrapper wrapper = null;
        if (wrapperClass != null) {
            try {
                wrapper = (Wrapper) wrapperClass.getConstructor().newInstance();
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                log.error(sm.getString("standardContext.createWrapper.error"), t);
                return null;
            }
        } else {
            wrapper = new StandardWrapper();
        }

        synchronized (wrapperLifecyclesLock) {
            for (String wrapperLifecycle : wrapperLifecycles) {
                try {
                    Class<?> clazz = Class.forName(wrapperLifecycle);
                    LifecycleListener listener = (LifecycleListener) clazz.getConstructor().newInstance();
                    wrapper.addLifecycleListener(listener);
                } catch (Throwable t) {
                    ExceptionUtils.handleThrowable(t);
                    log.error(sm.getString("standardContext.createWrapper.listenerError"), t);
                    return null;
                }
            }
        }

        synchronized (wrapperListenersLock) {
            for (String wrapperListener : wrapperListeners) {
                try {
                    Class<?> clazz = Class.forName(wrapperListener);
                    ContainerListener listener = (ContainerListener) clazz.getConstructor().newInstance();
                    wrapper.addContainerListener(listener);
                } catch (Throwable t) {
                    ExceptionUtils.handleThrowable(t);
                    log.error(sm.getString("standardContext.createWrapper.containerListenerError"), t);
                    return null;
                }
            }
        }

        return wrapper;
    }


    /**
     * Return the set of application listener class names configured for this application.
     */
    @Override
    public String[] findApplicationListeners() {
        return applicationListeners.toArray(new String[0]);
    }


    /**
     * Return the set of application parameters for this application.
     */
    @Override
    public ApplicationParameter[] findApplicationParameters() {

        synchronized (applicationParametersLock) {
            return applicationParameters;
        }

    }


    /**
     * Return the security constraints for this web application. If there are none, a zero-length array is returned.
     */
    @Override
    public SecurityConstraint[] findConstraints() {
        return constraints;
    }


    /**
     * Return the error page entry for the specified HTTP error code, if any; otherwise return <code>null</code>.
     *
     * @param errorCode Error code to look up
     */
    @Override
    public ErrorPage findErrorPage(int errorCode) {
        return errorPageSupport.find(errorCode);
    }


    @Override
    public ErrorPage findErrorPage(Throwable exceptionType) {
        return errorPageSupport.find(exceptionType);
    }


    /**
     * Return the set of defined error pages for all specified error codes and exception types.
     */
    @Override
    public ErrorPage[] findErrorPages() {
        return errorPageSupport.findAll();
    }


    /**
     * Return the filter definition for the specified filter name, if any; otherwise return <code>null</code>.
     *
     * @param filterName Filter name to look up
     */
    @Override
    public FilterDef findFilterDef(String filterName) {
        synchronized (filterDefs) {
            return filterDefs.get(filterName);
        }
    }


    /**
     * @return the set of defined filters for this Context.
     */
    @Override
    public FilterDef[] findFilterDefs() {
        synchronized (filterDefs) {
            return filterDefs.values().toArray(new FilterDef[0]);
        }
    }


    /**
     * @return the set of filter mappings for this Context.
     */
    @Override
    public FilterMap[] findFilterMaps() {
        return filterMaps.asArray();
    }


    /**
     * @return the message destination with the specified name, if any; otherwise, return <code>null</code>.
     *
     * @param name Name of the desired message destination
     */
    public MessageDestination findMessageDestination(String name) {
        synchronized (messageDestinations) {
            return messageDestinations.get(name);
        }
    }


    /**
     * @return the set of defined message destinations for this web application. If none have been defined, a
     *             zero-length array is returned.
     */
    public MessageDestination[] findMessageDestinations() {
        synchronized (messageDestinations) {
            return messageDestinations.values().toArray(new MessageDestination[0]);
        }
    }


    /**
     * @return the MIME type to which the specified extension is mapped, if any; otherwise return <code>null</code>.
     *
     * @param extension Extension to map to a MIME type
     */
    @Override
    public String findMimeMapping(String extension) {
        return mimeMappings.get(extension.toLowerCase(Locale.ENGLISH));
    }


    /**
     * @return the extensions for which MIME mappings are defined. If there are none, a zero-length array is returned.
     */
    @Override
    public String[] findMimeMappings() {
        synchronized (mimeMappings) {
            return mimeMappings.keySet().toArray(new String[0]);
        }
    }


    /**
     * @return the value for the specified context initialization parameter name, if any; otherwise return
     *             <code>null</code>.
     *
     * @param name Name of the parameter to return
     */
    @Override
    public String findParameter(String name) {
        return parameters.get(name);
    }


    /**
     * @return the names of all defined context initialization parameters for this Context. If no parameters are
     *             defined, a zero-length array is returned.
     */
    @Override
    public String[] findParameters() {
        return parameters.keySet().toArray(new String[0]);
    }


    /**
     * For the given security role (as used by an application), return the corresponding role name (as defined by the
     * underlying Realm) if there is one. Otherwise, return the specified role unchanged.
     *
     * @param role Security role to map
     *
     * @return the role name
     */
    @Override
    public String findRoleMapping(String role) {
        String realRole = null;
        synchronized (roleMappings) {
            realRole = roleMappings.get(role);
        }
        if (realRole != null) {
            return realRole;
        } else {
            return role;
        }
    }


    /**
     * @return <code>true</code> if the specified security role is defined for this application; otherwise return
     *             <code>false</code>.
     *
     * @param role Security role to verify
     */
    @Override
    public boolean findSecurityRole(String role) {

        synchronized (securityRolesLock) {
            for (String securityRole : securityRoles) {
                if (role.equals(securityRole)) {
                    return true;
                }
            }
        }
        return false;

    }


    /**
     * @return the security roles defined for this application. If none have been defined, a zero-length array is
     *             returned.
     */
    @Override
    public String[] findSecurityRoles() {
        synchronized (securityRolesLock) {
            return securityRoles;
        }
    }


    /**
     * @return the servlet name mapped by the specified pattern (if any); otherwise return <code>null</code>.
     *
     * @param pattern Pattern for which a mapping is requested
     */
    @Override
    public String findServletMapping(String pattern) {
        synchronized (servletMappingsLock) {
            return servletMappings.get(pattern);
        }
    }


    /**
     * @return the patterns of all defined servlet mappings for this Context. If no mappings are defined, a zero-length
     *             array is returned.
     */
    @Override
    public String[] findServletMappings() {
        synchronized (servletMappingsLock) {
            return servletMappings.keySet().toArray(new String[0]);
        }
    }


    /**
     * @return <code>true</code> if the specified welcome file is defined for this Context; otherwise return
     *             <code>false</code>.
     *
     * @param name Welcome file to verify
     */
    @Override
    public boolean findWelcomeFile(String name) {

        synchronized (welcomeFilesLock) {
            for (String welcomeFile : welcomeFiles) {
                if (name.equals(welcomeFile)) {
                    return true;
                }
            }
        }
        return false;

    }


    /**
     * @return the set of watched resources for this Context. If none are defined, a zero length array will be returned.
     */
    @Override
    public String[] findWatchedResources() {
        synchronized (watchedResourcesLock) {
            return watchedResources;
        }
    }


    /**
     * @return the set of welcome files defined for this Context. If none are defined, a zero-length array is returned.
     */
    @Override
    public String[] findWelcomeFiles() {
        synchronized (welcomeFilesLock) {
            return welcomeFiles;
        }
    }


    /**
     * @return the set of LifecycleListener classes that will be added to newly created Wrappers automatically.
     */
    @Override
    public String[] findWrapperLifecycles() {
        synchronized (wrapperLifecyclesLock) {
            return wrapperLifecycles;
        }
    }


    /**
     * @return the set of ContainerListener classes that will be added to newly created Wrappers automatically.
     */
    @Override
    public String[] findWrapperListeners() {
        synchronized (wrapperListenersLock) {
            return wrapperListeners;
        }
    }


    /**
     * Reload this web application, if reloading is supported.
     * <p>
     * <b>IMPLEMENTATION NOTE</b>: This method is designed to deal with reloads required by changes to classes in the
     * underlying repositories of our class loader and changes to the web.xml file. It does not handle changes to any
     * context.xml file. If the context.xml has changed, you should stop this Context and create (and start) a new
     * Context instance instead. Note that there is additional code in <code>CoyoteAdapter#postParseRequest()</code> to
     * handle mapping requests to paused Contexts.
     *
     * @exception IllegalStateException if the <code>reloadable</code> property is set to <code>false</code>.
     */
    @Override
    public synchronized void reload() {

        // Validate our current component state
        if (!getState().isAvailable()) {
            throw new IllegalStateException(sm.getString("standardContext.notStarted", getName()));
        }

        if (log.isInfoEnabled()) {
            log.info(sm.getString("standardContext.reloadingStarted", getName()));
        }

        // Stop accepting requests temporarily.
        setPaused(true);

        try {
            stop();
        } catch (LifecycleException e) {
            log.error(sm.getString("standardContext.stoppingContext", getName()), e);
        }

        try {
            start();
        } catch (LifecycleException e) {
            log.error(sm.getString("standardContext.startingContext", getName()), e);
        }

        setPaused(false);

        if (log.isInfoEnabled()) {
            log.info(sm.getString("standardContext.reloadingCompleted", getName()));
        }

    }


    /**
     * Remove the specified application listener class from the set of listeners for this application.
     *
     * @param listener Java class name of the listener to be removed
     */
    @Override
    public void removeApplicationListener(String listener) {
        if (applicationListeners.remove(listener)) {
            // Inform interested listeners if the specified listener was present and has been removed
            fireContainerEvent("removeApplicationListener", listener);
        }
    }


    /**
     * Remove the application parameter with the specified name from the set for this application.
     *
     * @param name Name of the application parameter to remove
     */
    @Override
    public void removeApplicationParameter(String name) {

        synchronized (applicationParametersLock) {

            // Make sure this parameter is currently present
            int n = -1;
            for (int i = 0; i < applicationParameters.length; i++) {
                if (name.equals(applicationParameters[i].getName())) {
                    n = i;
                    break;
                }
            }
            if (n < 0) {
                return;
            }

            // Remove the specified parameter
            int j = 0;
            ApplicationParameter results[] = new ApplicationParameter[applicationParameters.length - 1];
            for (int i = 0; i < applicationParameters.length; i++) {
                if (i != n) {
                    results[j++] = applicationParameters[i];
                }
            }
            applicationParameters = results;

        }

        // Inform interested listeners
        fireContainerEvent("removeApplicationParameter", name);

    }


    /**
     * Add a child Container, only if the proposed child is an implementation of Wrapper.
     *
     * @param child Child container to be added
     *
     * @exception IllegalArgumentException if the proposed container is not an implementation of Wrapper
     */
    @Override
    public void removeChild(Container child) {

        if (!(child instanceof Wrapper)) {
            throw new IllegalArgumentException(sm.getString("standardContext.notWrapper"));
        }

        super.removeChild(child);

    }


    /**
     * Remove the specified security constraint from this web application.
     *
     * @param constraint Constraint to be removed
     */
    @Override
    public void removeConstraint(SecurityConstraint constraint) {

        synchronized (constraintsLock) {

            // Make sure this constraint is currently present
            int n = -1;
            for (int i = 0; i < constraints.length; i++) {
                if (constraints[i].equals(constraint)) {
                    n = i;
                    break;
                }
            }
            if (n < 0) {
                return;
            }

            // Remove the specified constraint
            int j = 0;
            SecurityConstraint results[] = new SecurityConstraint[constraints.length - 1];
            for (int i = 0; i < constraints.length; i++) {
                if (i != n) {
                    results[j++] = constraints[i];
                }
            }
            constraints = results;

        }

        // Inform interested listeners
        fireContainerEvent("removeConstraint", constraint);

    }


    /**
     * Remove the error page for the specified error code or Java language exception, if it exists; otherwise, no action
     * is taken.
     *
     * @param errorPage The error page definition to be removed
     */
    @Override
    public void removeErrorPage(ErrorPage errorPage) {
        errorPageSupport.remove(errorPage);
        fireContainerEvent("removeErrorPage", errorPage);
    }


    /**
     * Remove the specified filter definition from this Context, if it exists; otherwise, no action is taken.
     *
     * @param filterDef Filter definition to be removed
     */
    @Override
    public void removeFilterDef(FilterDef filterDef) {

        synchronized (filterDefs) {
            filterDefs.remove(filterDef.getFilterName());
        }
        fireContainerEvent("removeFilterDef", filterDef);

    }


    /**
     * Remove a filter mapping from this Context.
     *
     * @param filterMap The filter mapping to be removed
     */
    @Override
    public void removeFilterMap(FilterMap filterMap) {
        filterMaps.remove(filterMap);
        // Inform interested listeners
        fireContainerEvent("removeFilterMap", filterMap);
    }


    /**
     * Remove any message destination with the specified name.
     *
     * @param name Name of the message destination to remove
     */
    public void removeMessageDestination(String name) {

        synchronized (messageDestinations) {
            messageDestinations.remove(name);
        }
        fireContainerEvent("removeMessageDestination", name);

    }


    /**
     * Remove the MIME mapping for the specified extension, if it exists; otherwise, no action is taken.
     *
     * @param extension Extension to remove the mapping for
     */
    @Override
    public void removeMimeMapping(String extension) {

        synchronized (mimeMappings) {
            mimeMappings.remove(extension);
        }
        fireContainerEvent("removeMimeMapping", extension);

    }


    /**
     * Remove the context initialization parameter with the specified name, if it exists; otherwise, no action is taken.
     *
     * @param name Name of the parameter to remove
     */
    @Override
    public void removeParameter(String name) {
        parameters.remove(name);
        fireContainerEvent("removeParameter", name);
    }


    /**
     * Remove any security role reference for the specified name
     *
     * @param role Security role (as used in the application) to remove
     */
    @Override
    public void removeRoleMapping(String role) {

        synchronized (roleMappings) {
            roleMappings.remove(role);
        }
        fireContainerEvent("removeRoleMapping", role);

    }


    /**
     * Remove any security role with the specified name.
     *
     * @param role Security role to remove
     */
    @Override
    public void removeSecurityRole(String role) {

        synchronized (securityRolesLock) {

            // Make sure this security role is currently present
            int n = -1;
            for (int i = 0; i < securityRoles.length; i++) {
                if (role.equals(securityRoles[i])) {
                    n = i;
                    break;
                }
            }
            if (n < 0) {
                return;
            }

            // Remove the specified security role
            int j = 0;
            String results[] = new String[securityRoles.length - 1];
            for (int i = 0; i < securityRoles.length; i++) {
                if (i != n) {
                    results[j++] = securityRoles[i];
                }
            }
            securityRoles = results;

        }

        // Inform interested listeners
        fireContainerEvent("removeSecurityRole", role);

    }


    /**
     * Remove any servlet mapping for the specified pattern, if it exists; otherwise, no action is taken.
     *
     * @param pattern URL pattern of the mapping to remove
     */
    @Override
    public void removeServletMapping(String pattern) {

        String name = null;
        synchronized (servletMappingsLock) {
            name = servletMappings.remove(pattern);
        }
        Wrapper wrapper = (Wrapper) findChild(name);
        if (wrapper != null) {
            wrapper.removeMapping(pattern);
        }
        fireContainerEvent("removeServletMapping", pattern);
    }


    /**
     * Remove the specified watched resource name from the list associated with this Context.
     *
     * @param name Name of the watched resource to be removed
     */
    @Override
    public void removeWatchedResource(String name) {

        synchronized (watchedResourcesLock) {

            // Make sure this watched resource is currently present
            int n = -1;
            for (int i = 0; i < watchedResources.length; i++) {
                if (watchedResources[i].equals(name)) {
                    n = i;
                    break;
                }
            }
            if (n < 0) {
                return;
            }

            // Remove the specified watched resource
            int j = 0;
            String results[] = new String[watchedResources.length - 1];
            for (int i = 0; i < watchedResources.length; i++) {
                if (i != n) {
                    results[j++] = watchedResources[i];
                }
            }
            watchedResources = results;

        }

        fireContainerEvent("removeWatchedResource", name);

    }


    /**
     * Remove the specified welcome file name from the list recognized by this Context.
     *
     * @param name Name of the welcome file to be removed
     */
    @Override
    public void removeWelcomeFile(String name) {

        synchronized (welcomeFilesLock) {

            // Make sure this welcome file is currently present
            int n = -1;
            for (int i = 0; i < welcomeFiles.length; i++) {
                if (welcomeFiles[i].equals(name)) {
                    n = i;
                    break;
                }
            }
            if (n < 0) {
                return;
            }

            // Remove the specified welcome file
            int j = 0;
            String results[] = new String[welcomeFiles.length - 1];
            for (int i = 0; i < welcomeFiles.length; i++) {
                if (i != n) {
                    results[j++] = welcomeFiles[i];
                }
            }
            welcomeFiles = results;

        }

        // Inform interested listeners
        if (this.getState().equals(LifecycleState.STARTED)) {
            fireContainerEvent(REMOVE_WELCOME_FILE_EVENT, name);
        }

    }


    /**
     * Remove a class name from the set of LifecycleListener classes that will be added to newly created Wrappers.
     *
     * @param listener Class name of a LifecycleListener class to be removed
     */
    @Override
    public void removeWrapperLifecycle(String listener) {


        synchronized (wrapperLifecyclesLock) {

            // Make sure this lifecycle listener is currently present
            int n = -1;
            for (int i = 0; i < wrapperLifecycles.length; i++) {
                if (wrapperLifecycles[i].equals(listener)) {
                    n = i;
                    break;
                }
            }
            if (n < 0) {
                return;
            }

            // Remove the specified lifecycle listener
            int j = 0;
            String results[] = new String[wrapperLifecycles.length - 1];
            for (int i = 0; i < wrapperLifecycles.length; i++) {
                if (i != n) {
                    results[j++] = wrapperLifecycles[i];
                }
            }
            wrapperLifecycles = results;

        }

        // Inform interested listeners
        fireContainerEvent("removeWrapperLifecycle", listener);

    }


    /**
     * Remove a class name from the set of ContainerListener classes that will be added to newly created Wrappers.
     *
     * @param listener Class name of a ContainerListener class to be removed
     */
    @Override
    public void removeWrapperListener(String listener) {


        synchronized (wrapperListenersLock) {

            // Make sure this listener is currently present
            int n = -1;
            for (int i = 0; i < wrapperListeners.length; i++) {
                if (wrapperListeners[i].equals(listener)) {
                    n = i;
                    break;
                }
            }
            if (n < 0) {
                return;
            }

            // Remove the specified listener
            int j = 0;
            String results[] = new String[wrapperListeners.length - 1];
            for (int i = 0; i < wrapperListeners.length; i++) {
                if (i != n) {
                    results[j++] = wrapperListeners[i];
                }
            }
            wrapperListeners = results;

        }

        // Inform interested listeners
        fireContainerEvent("removeWrapperListener", listener);

    }


    /**
     * Gets the cumulative processing times of all servlets in this StandardContext.
     *
     * @return Cumulative processing times of all servlets in this StandardContext
     */
    public long getProcessingTime() {

        long result = 0;

        Container[] children = findChildren();
        if (children != null) {
            for (Container child : children) {
                result += ((StandardWrapper) child).getProcessingTime();
            }
        }

        return result;
    }

    /**
     * Gets the maximum processing time of all servlets in this StandardContext.
     *
     * @return Maximum processing time of all servlets in this StandardContext
     */
    public long getMaxTime() {

        long result = 0;
        long time;

        Container[] children = findChildren();
        if (children != null) {
            for (Container child : children) {
                time = ((StandardWrapper) child).getMaxTime();
                if (time > result) {
                    result = time;
                }
            }
        }

        return result;
    }

    /**
     * Gets the minimum processing time of all servlets in this StandardContext.
     *
     * @return Minimum processing time of all servlets in this StandardContext
     */
    public long getMinTime() {

        long result = -1;
        long time;

        Container[] children = findChildren();
        if (children != null) {
            for (Container child : children) {
                time = ((StandardWrapper) child).getMinTime();
                if (result < 0 || time < result) {
                    result = time;
                }
            }
        }

        return result;
    }

    /**
     * Gets the cumulative request count of all servlets in this StandardContext.
     *
     * @return Cumulative request count of all servlets in this StandardContext
     *
     * @deprecated The return type will change to long in Tomcat 11 onwards. Callers of this method should switch to
     *                 storing the result of calls to this method in a long value rather than an int.
     */
    @Deprecated
    public int getRequestCount() {

        int result = 0;

        Container[] children = findChildren();
        if (children != null) {
            for (Container child : children) {
                result += ((StandardWrapper) child).getRequestCount();
            }
        }

        return result;
    }

    /**
     * Gets the cumulative error count of all servlets in this StandardContext.
     *
     * @return Cumulative error count of all servlets in this StandardContext
     *
     * @deprecated The return type will change to long in Tomcat 11 onwards. Callers of this method should switch to
     *                 storing the result of calls to this method in a long value rather than an int.
     */
    @Deprecated
    public int getErrorCount() {

        int result = 0;

        Container[] children = findChildren();
        if (children != null) {
            for (Container child : children) {
                result += ((StandardWrapper) child).getErrorCount();
            }
        }

        return result;
    }


    /**
     * Return the real path for a given virtual path, if possible; otherwise return <code>null</code>.
     *
     * @param path The path to the desired resource
     */
    @Override
    public String getRealPath(String path) {
        // The WebResources API expects all paths to start with /. This is a
        // special case for consistency with earlier Tomcat versions.
        if ("".equals(path)) {
            path = "/";
        }
        if (resources != null) {
            try {
                WebResource resource = resources.getResource(path);
                String canonicalPath = resource.getCanonicalPath();
                if (canonicalPath == null) {
                    return null;
                } else if ((resource.isDirectory() && !canonicalPath.endsWith(File.separator) || !resource.exists()) &&
                        path.endsWith("/")) {
                    return canonicalPath + File.separatorChar;
                } else {
                    return canonicalPath;
                }
            } catch (IllegalArgumentException iae) {
                // ServletContext.getRealPath() does not allow this to be thrown
            }
        }
        return null;
    }


    /**
     * Hook to track which Servlets were created via {@link ServletContext#createServlet(Class)}.
     *
     * @param servlet the created Servlet
     */
    public void dynamicServletCreated(Servlet servlet) {
        createdServlets.add(servlet);
    }


    public boolean wasCreatedDynamicServlet(Servlet servlet) {
        return createdServlets.contains(servlet);
    }


    /**
     * A helper class to manage the filter mappings in a Context.
     */
    private static final class ContextFilterMaps {
        private final Object lock = new Object();

        /**
         * The set of filter mappings for this application, in the order they were defined in the deployment descriptor
         * with additional mappings added via the {@link ServletContext} possibly both before and after those defined in
         * the deployment descriptor.
         */
        private FilterMap[] array = new FilterMap[0];

        /**
         * Filter mappings added via {@link ServletContext} may have to be inserted before the mappings in the
         * deployment descriptor but must be inserted in the order the {@link ServletContext} methods are called. This
         * isn't an issue for the mappings added after the deployment descriptor - they are just added to the end - but
         * correctly the adding mappings before the deployment descriptor mappings requires knowing where the last
         * 'before' mapping was added.
         */
        private int insertPoint = 0;

        /**
         * @return The set of filter mappings
         */
        public FilterMap[] asArray() {
            synchronized (lock) {
                return array;
            }
        }

        /**
         * Add a filter mapping at the end of the current set of filter mappings.
         *
         * @param filterMap The filter mapping to be added
         */
        public void add(FilterMap filterMap) {
            synchronized (lock) {
                FilterMap results[] = Arrays.copyOf(array, array.length + 1);
                results[array.length] = filterMap;
                array = results;
            }
        }

        /**
         * Add a filter mapping before the mappings defined in the deployment descriptor but after any other mappings
         * added via this method.
         *
         * @param filterMap The filter mapping to be added
         */
        public void addBefore(FilterMap filterMap) {
            synchronized (lock) {
                FilterMap results[] = new FilterMap[array.length + 1];
                System.arraycopy(array, 0, results, 0, insertPoint);
                System.arraycopy(array, insertPoint, results, insertPoint + 1, array.length - insertPoint);
                results[insertPoint] = filterMap;
                array = results;
                insertPoint++;
            }
        }

        /**
         * Remove a filter mapping.
         *
         * @param filterMap The filter mapping to be removed
         */
        public void remove(FilterMap filterMap) {
            synchronized (lock) {
                // Make sure this filter mapping is currently present
                int n = -1;
                for (int i = 0; i < array.length; i++) {
                    if (array[i] == filterMap) {
                        n = i;
                        break;
                    }
                }
                if (n < 0) {
                    return;
                }

                // Remove the specified filter mapping
                FilterMap results[] = new FilterMap[array.length - 1];
                System.arraycopy(array, 0, results, 0, n);
                System.arraycopy(array, n + 1, results, n, (array.length - 1) - n);
                array = results;
                if (n < insertPoint) {
                    insertPoint--;
                }
            }
        }
    }

    // --------------------------------------------------------- Public Methods


    /**
     * Configure and initialize the set of filters for this Context.
     *
     * @return <code>true</code> if all filter initialization completed successfully, or <code>false</code> otherwise.
     */
    public boolean filterStart() {

        if (getLogger().isTraceEnabled()) {
            getLogger().trace("Starting filters");
        }
        // Instantiate and record a FilterConfig for each defined filter
        boolean ok = true;
        synchronized (filterDefs) {
            filterConfigs.clear();
            for (Entry<String,FilterDef> entry : filterDefs.entrySet()) {
                String name = entry.getKey();
                if (getLogger().isTraceEnabled()) {
                    getLogger().trace(" Starting filter '" + name + "'");
                }
                try {
                    ApplicationFilterConfig filterConfig = new ApplicationFilterConfig(this, entry.getValue());
                    filterConfigs.put(name, filterConfig);
                } catch (Throwable t) {
                    t = ExceptionUtils.unwrapInvocationTargetException(t);
                    ExceptionUtils.handleThrowable(t);
                    getLogger().error(sm.getString("standardContext.filterStart", name), t);
                    ok = false;
                }
            }
        }

        return ok;
    }


    /**
     * Finalize and release the set of filters for this Context.
     *
     * @return <code>true</code> if all filter finalization completed successfully, or <code>false</code> otherwise.
     */
    public boolean filterStop() {

        if (getLogger().isTraceEnabled()) {
            getLogger().trace("Stopping filters");
        }

        // Release all Filter and FilterConfig instances
        synchronized (filterDefs) {
            for (Entry<String,ApplicationFilterConfig> entry : filterConfigs.entrySet()) {
                if (getLogger().isTraceEnabled()) {
                    getLogger().trace(" Stopping filter '" + entry.getKey() + "'");
                }
                ApplicationFilterConfig filterConfig = entry.getValue();
                filterConfig.release();
            }
            filterConfigs.clear();
        }
        return true;

    }


    /**
     * Find and return the initialized <code>FilterConfig</code> for the specified filter name, if any; otherwise return
     * <code>null</code>.
     *
     * @param name Name of the desired filter
     *
     * @return the filter config object
     */
    public FilterConfig findFilterConfig(String name) {
        synchronized (filterDefs) {
            return filterConfigs.get(name);
        }
    }


    /**
     * Configure the set of instantiated application event listeners for this Context.
     *
     * @return <code>true</code> if all listeners wre initialized successfully, or <code>false</code> otherwise.
     */
    public boolean listenerStart() {

        if (log.isTraceEnabled()) {
            log.trace("Configuring application event listeners");
        }

        // Instantiate the required listeners
        String listeners[] = findApplicationListeners();
        Object results[] = new Object[listeners.length];
        boolean ok = true;
        for (int i = 0; i < results.length; i++) {
            if (getLogger().isTraceEnabled()) {
                getLogger().trace(" Configuring event listener class '" + listeners[i] + "'");
            }
            try {
                String listener = listeners[i];
                results[i] = getInstanceManager().newInstance(listener);
            } catch (Throwable t) {
                t = ExceptionUtils.unwrapInvocationTargetException(t);
                ExceptionUtils.handleThrowable(t);
                getLogger().error(sm.getString("standardContext.applicationListener", listeners[i]), t);
                ok = false;
            }
        }
        if (!ok) {
            getLogger().error(sm.getString("standardContext.applicationSkipped"));
            return false;
        }

        // Sort listeners in two arrays
        List<Object> eventListeners = new ArrayList<>();
        List<Object> lifecycleListeners = new ArrayList<>();
        for (Object result : results) {
            if ((result instanceof ServletContextAttributeListener) ||
                    (result instanceof ServletRequestAttributeListener) || (result instanceof ServletRequestListener) ||
                    (result instanceof HttpSessionIdListener) || (result instanceof HttpSessionAttributeListener)) {
                eventListeners.add(result);
            }
            if ((result instanceof ServletContextListener) || (result instanceof HttpSessionListener)) {
                lifecycleListeners.add(result);
            }
        }

        // Listener instances may have been added directly to this Context by
        // ServletContextInitializers and other code via the pluggability APIs.
        // Put them these listeners after the ones defined in web.xml and/or
        // annotations then overwrite the list of instances with the new, full
        // list.
        eventListeners.addAll(Arrays.asList(getApplicationEventListeners()));
        setApplicationEventListeners(eventListeners.toArray());
        for (Object lifecycleListener : getApplicationLifecycleListeners()) {
            lifecycleListeners.add(lifecycleListener);
            if (lifecycleListener instanceof ServletContextListener) {
                noPluggabilityListeners.add(lifecycleListener);
            }
        }
        setApplicationLifecycleListeners(lifecycleListeners.toArray());

        // Send application start events

        if (getLogger().isTraceEnabled()) {
            getLogger().trace("Sending application start events");
        }

        // Ensure context is not null
        getServletContext();
        context.setNewServletContextListenerAllowed(false);

        Object instances[] = getApplicationLifecycleListeners();
        if (instances == null || instances.length == 0) {
            return ok;
        }

        ServletContextEvent event = new ServletContextEvent(getServletContext());
        ServletContextEvent tldEvent = null;
        if (noPluggabilityListeners.size() > 0) {
            noPluggabilityServletContext = new NoPluggabilityServletContext(getServletContext());
            tldEvent = new ServletContextEvent(noPluggabilityServletContext);
        }
        for (Object instance : instances) {
            if (!(instance instanceof ServletContextListener)) {
                continue;
            }
            ServletContextListener listener = (ServletContextListener) instance;
            try {
                fireContainerEvent("beforeContextInitialized", listener);
                if (noPluggabilityListeners.contains(listener)) {
                    listener.contextInitialized(tldEvent);
                } else {
                    listener.contextInitialized(event);
                }
                fireContainerEvent("afterContextInitialized", listener);
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                fireContainerEvent("afterContextInitialized", listener);
                getLogger().error(sm.getString("standardContext.listenerStart", instance.getClass().getName()), t);
                ok = false;
            }
        }
        return ok;

    }


    /**
     * Send an application stop event to all interested listeners.
     *
     * @return <code>true</code> if all events were sent successfully, or <code>false</code> otherwise.
     */
    public boolean listenerStop() {

        if (log.isTraceEnabled()) {
            log.trace("Sending application stop events");
        }

        boolean ok = true;
        Object listeners[] = getApplicationLifecycleListeners();
        if (listeners != null && listeners.length > 0) {
            ServletContextEvent event = new ServletContextEvent(getServletContext());
            ServletContextEvent tldEvent = null;
            if (noPluggabilityServletContext != null) {
                tldEvent = new ServletContextEvent(noPluggabilityServletContext);
            }
            for (int i = 0; i < listeners.length; i++) {
                int j = (listeners.length - 1) - i;
                if (listeners[j] == null) {
                    continue;
                }
                if (listeners[j] instanceof ServletContextListener) {
                    ServletContextListener listener = (ServletContextListener) listeners[j];
                    try {
                        fireContainerEvent("beforeContextDestroyed", listener);
                        if (noPluggabilityListeners.contains(listener)) {
                            listener.contextDestroyed(tldEvent);
                        } else {
                            listener.contextDestroyed(event);
                        }
                        fireContainerEvent("afterContextDestroyed", listener);
                    } catch (Throwable t) {
                        ExceptionUtils.handleThrowable(t);
                        fireContainerEvent("afterContextDestroyed", listener);
                        getLogger().error(
                                sm.getString("standardContext.listenerStop", listeners[j].getClass().getName()), t);
                        ok = false;
                    }
                }
                try {
                    if (getInstanceManager() != null) {
                        getInstanceManager().destroyInstance(listeners[j]);
                    }
                } catch (Throwable t) {
                    t = ExceptionUtils.unwrapInvocationTargetException(t);
                    ExceptionUtils.handleThrowable(t);
                    getLogger().error(sm.getString("standardContext.listenerStop", listeners[j].getClass().getName()),
                            t);
                    ok = false;
                }
            }
        }

        // Annotation processing
        listeners = getApplicationEventListeners();
        if (listeners != null) {
            for (int i = 0; i < listeners.length; i++) {
                int j = (listeners.length - 1) - i;
                if (listeners[j] == null) {
                    continue;
                }
                try {
                    if (getInstanceManager() != null) {
                        getInstanceManager().destroyInstance(listeners[j]);
                    }
                } catch (Throwable t) {
                    t = ExceptionUtils.unwrapInvocationTargetException(t);
                    ExceptionUtils.handleThrowable(t);
                    getLogger().error(sm.getString("standardContext.listenerStop", listeners[j].getClass().getName()),
                            t);
                    ok = false;
                }
            }
        }

        setApplicationEventListeners(null);
        setApplicationLifecycleListeners(null);

        noPluggabilityServletContext = null;
        noPluggabilityListeners.clear();

        return ok;
    }


    /**
     * Allocate resources, including proxy.
     *
     * @throws LifecycleException if a start error occurs
     */
    public void resourcesStart() throws LifecycleException {

        // Check current status in case resources were added that had already
        // been started
        if (!resources.getState().isAvailable()) {
            resources.start();
        }

        if (effectiveMajorVersion >= 3 && addWebinfClassesResources) {
            WebResource webinfClassesResource = resources.getResource("/WEB-INF/classes/META-INF/resources");
            if (webinfClassesResource.isDirectory()) {
                getResources().createWebResourceSet(WebResourceRoot.ResourceSetType.RESOURCE_JAR, "/",
                        webinfClassesResource.getURL(), "/");
            }
        }
    }


    /**
     * Deallocate resources and destroy proxy.
     *
     * @return <code>true</code> if no error occurred
     */
    public boolean resourcesStop() {

        boolean ok = true;

        Lock writeLock = resourcesLock.writeLock();
        writeLock.lock();
        try {
            if (resources != null) {
                resources.stop();
            }
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            log.error(sm.getString("standardContext.resourcesStop"), t);
            ok = false;
        } finally {
            writeLock.unlock();
        }

        return ok;
    }


    /**
     * Load and initialize all servlets marked "load on startup" in the web application deployment descriptor.
     *
     * @param children Array of wrappers for all currently defined servlets (including those not declared load on
     *                     startup)
     *
     * @return <code>true</code> if load on startup was considered successful
     */
    public boolean loadOnStartup(Container children[]) {

        // Collect "load on startup" servlets that need to be initialized
        TreeMap<Integer,ArrayList<Wrapper>> map = new TreeMap<>();
        for (Container child : children) {
            Wrapper wrapper = (Wrapper) child;
            int loadOnStartup = wrapper.getLoadOnStartup();
            if (loadOnStartup < 0) {
                continue;
            }
            Integer key = Integer.valueOf(loadOnStartup);
            map.computeIfAbsent(key, k -> new ArrayList<>()).add(wrapper);
        }

        // Load the collected "load on startup" servlets
        for (ArrayList<Wrapper> list : map.values()) {
            for (Wrapper wrapper : list) {
                try {
                    wrapper.load();
                } catch (ServletException e) {
                    getLogger().error(
                            sm.getString("standardContext.loadOnStartup.loadException", getName(), wrapper.getName()),
                            StandardWrapper.getRootCause(e));
                    // NOTE: load errors (including a servlet that throws
                    // UnavailableException from the init() method) are NOT
                    // fatal to application startup
                    // unless failCtxIfServletStartFails="true" is specified
                    if (getComputedFailCtxIfServletStartFails()) {
                        return false;
                    }
                }
            }
        }
        return true;

    }


    /**
     * Start this component and implement the requirements of
     * {@link org.apache.catalina.util.LifecycleBase#startInternal()}.
     *
     * @exception LifecycleException if this component detects a fatal error that prevents this component from being
     *                                   used
     */
    @Override
    protected void startInternal() throws LifecycleException {

        if (log.isTraceEnabled()) {
            log.trace("Starting " + getBaseName());
        }

        // Send j2ee.state.starting notification
        if (this.getObjectName() != null) {
            Notification notification =
                    new Notification("j2ee.state.starting", this.getObjectName(), sequenceNumber.getAndIncrement());
            broadcaster.sendNotification(notification);
        }

        setConfigured(false);
        boolean ok = true;

        // Currently this is effectively a NO-OP but needs to be called to
        // ensure the NamingResources follows the correct lifecycle
        if (namingResources != null) {
            namingResources.start();
        }

        // Post work directory
        postWorkDirectory();

        // Add missing components as necessary
        if (getResources() == null) { // (1) Required by Loader
            if (log.isTraceEnabled()) {
                log.trace("Configuring default Resources");
            }

            try {
                setResources(new StandardRoot(this));
            } catch (IllegalArgumentException e) {
                log.error(sm.getString("standardContext.resourcesInit"), e);
                ok = false;
            }
        }
        if (ok) {
            resourcesStart();
        }

        if (getLoader() == null) {
            WebappLoader webappLoader = new WebappLoader();
            webappLoader.setDelegate(getDelegate());
            setLoader(webappLoader);
        }

        // An explicit cookie processor hasn't been specified; use the default
        if (cookieProcessor == null) {
            cookieProcessor = new Rfc6265CookieProcessor();
        }

        // Initialize character set mapper
        getCharsetMapper();

        // Reading the "catalina.useNaming" environment variable
        String useNamingProperty = System.getProperty("catalina.useNaming");
        if ((useNamingProperty != null) && (useNamingProperty.equals("false"))) {
            useNaming = false;
        }

        if (ok && isUseNaming()) {
            if (getNamingContextListener() == null) {
                NamingContextListener ncl = new NamingContextListener();
                ncl.setName(getNamingContextName());
                ncl.setExceptionOnFailedWrite(getJndiExceptionOnFailedWrite());
                addLifecycleListener(ncl);
                setNamingContextListener(ncl);
            }
        }

        // Standard container startup
        if (log.isTraceEnabled()) {
            log.trace("Processing standard container startup");
        }


        // Binding thread
        ClassLoader oldCCL = bindThread();

        try {
            if (ok) {
                // Start our subordinate components, if any
                Loader loader = getLoader();
                if (loader instanceof Lifecycle) {
                    ((Lifecycle) loader).start();
                }

                // since the loader just started, the webapp classloader is now
                // created.
                if (loader.getClassLoader() instanceof WebappClassLoaderBase) {
                    WebappClassLoaderBase cl = (WebappClassLoaderBase) loader.getClassLoader();
                    cl.setClearReferencesRmiTargets(getClearReferencesRmiTargets());
                    cl.setClearReferencesStopThreads(getClearReferencesStopThreads());
                    cl.setClearReferencesStopTimerThreads(getClearReferencesStopTimerThreads());
                    cl.setClearReferencesHttpClientKeepAliveThread(getClearReferencesHttpClientKeepAliveThread());
                    cl.setClearReferencesObjectStreamClassCaches(getClearReferencesObjectStreamClassCaches());
                    cl.setClearReferencesThreadLocals(getClearReferencesThreadLocals());
                    cl.setSkipMemoryLeakChecksOnJvmShutdown(getSkipMemoryLeakChecksOnJvmShutdown());
                }

                // By calling unbindThread and bindThread in a row, we setup the
                // current Thread CCL to be the webapp classloader
                unbindThread(oldCCL);
                oldCCL = bindThread();

                // Initialize logger again. Other components might have used it
                // too early, so it should be reset.
                logger = null;
                getLogger();

                Realm realm = getRealmInternal();
                if (null != realm) {
                    if (realm instanceof Lifecycle) {
                        ((Lifecycle) realm).start();
                    }

                    // Place the CredentialHandler into the ServletContext so
                    // applications can have access to it. Wrap it in a "safe"
                    // handler so application's can't modify it.
                    CredentialHandler safeHandler = new CredentialHandler() {
                        @Override
                        public boolean matches(String inputCredentials, String storedCredentials) {
                            return getRealmInternal().getCredentialHandler().matches(inputCredentials,
                                    storedCredentials);
                        }

                        @Override
                        public String mutate(String inputCredentials) {
                            return getRealmInternal().getCredentialHandler().mutate(inputCredentials);
                        }
                    };
                    context.setAttribute(Globals.CREDENTIAL_HANDLER, safeHandler);
                }

                // Notify our interested LifecycleListeners
                fireLifecycleEvent(CONFIGURE_START_EVENT, null);

                // Start our child containers, if not already started
                for (Container child : findChildren()) {
                    if (!child.getState().isAvailable()) {
                        child.start();
                    }
                }

                // Start the Valves in our pipeline (including the basic),
                // if any
                if (pipeline instanceof Lifecycle) {
                    ((Lifecycle) pipeline).start();
                }

                // Acquire clustered manager
                Manager contextManager = null;
                Manager manager = getManager();
                if (manager == null) {
                    if (log.isDebugEnabled()) {
                        log.debug(sm.getString("standardContext.cluster.noManager",
                                Boolean.valueOf((getCluster() != null)), Boolean.valueOf(distributable)));
                    }
                    if ((getCluster() != null) && distributable) {
                        try {
                            contextManager = getCluster().createManager(getName());
                        } catch (Exception ex) {
                            log.error(sm.getString("standardContext.cluster.managerError"), ex);
                            ok = false;
                        }
                    } else {
                        contextManager = new StandardManager();
                    }
                }

                // Configure default manager if none was specified
                if (contextManager != null) {
                    if (log.isDebugEnabled()) {
                        log.debug(sm.getString("standardContext.manager", contextManager.getClass().getName()));
                    }
                    setManager(contextManager);
                }

                if (manager != null && (getCluster() != null) && distributable) {
                    // let the cluster know that there is a context that is distributable
                    // and that it has its own manager
                    getCluster().registerManager(manager);
                }
            }

            if (!getConfigured()) {
                log.error(sm.getString("standardContext.configurationFail"));
                ok = false;
            }

            // We put the resources into the servlet context
            if (ok) {
                getServletContext().setAttribute(Globals.RESOURCES_ATTR, getResources());

                if (getInstanceManager() == null) {
                    setInstanceManager(createInstanceManager());
                }
                getServletContext().setAttribute(InstanceManager.class.getName(), getInstanceManager());
                InstanceManagerBindings.bind(getLoader().getClassLoader(), getInstanceManager());

                // Create context attributes that will be required
                getServletContext().setAttribute(JarScanner.class.getName(), getJarScanner());

                // Make the version info available
                getServletContext().setAttribute(Globals.WEBAPP_VERSION, getWebappVersion());

                // Make the utility executor available
                if (!Globals.IS_SECURITY_ENABLED) {
                    getServletContext().setAttribute(ScheduledThreadPoolExecutor.class.getName(),
                            Container.getService(this).getServer().getUtilityExecutor());
                }
            }

            // Set up the context init params
            mergeParameters();

            // Call ServletContainerInitializers
            for (Map.Entry<ServletContainerInitializer,Set<Class<?>>> entry : initializers.entrySet()) {
                try {
                    entry.getKey().onStartup(entry.getValue(), getServletContext());
                } catch (ServletException e) {
                    log.error(sm.getString("standardContext.sciFail"), e);
                    ok = false;
                    break;
                }
            }

            // Configure and call application event listeners
            if (ok) {
                if (!listenerStart()) {
                    log.error(sm.getString("standardContext.listenerFail"));
                    ok = false;
                }
            }

            // Check constraints for uncovered HTTP methods
            // Needs to be after SCIs and listeners as they may programmatically
            // change constraints
            if (ok) {
                checkConstraintsForUncoveredMethods(findConstraints());
            }

            try {
                // Start manager
                Manager manager = getManager();
                if (manager instanceof Lifecycle) {
                    ((Lifecycle) manager).start();
                }
            } catch (Exception e) {
                log.error(sm.getString("standardContext.managerFail"), e);
                ok = false;
            }

            // Configure and call application filters
            if (ok) {
                if (!filterStart()) {
                    log.error(sm.getString("standardContext.filterFail"));
                    ok = false;
                }
            }

            // Load and initialize all "load on startup" servlets
            if (ok) {
                if (!loadOnStartup(findChildren())) {
                    log.error(sm.getString("standardContext.servletFail"));
                    ok = false;
                }
            }

            // Start ContainerBackgroundProcessor thread
            super.threadStart();
        } finally {
            // Unbinding thread
            unbindThread(oldCCL);
        }

        // Set available status depending upon startup success
        if (ok) {
            if (log.isTraceEnabled()) {
                log.trace("Starting completed");
            }
        } else {
            log.error(sm.getString("standardContext.startFailed", getName()));
        }

        startTime = System.currentTimeMillis();

        // Send j2ee.state.running notification
        if (ok && (this.getObjectName() != null)) {
            Notification notification =
                    new Notification("j2ee.state.running", this.getObjectName(), sequenceNumber.getAndIncrement());
            broadcaster.sendNotification(notification);
        }

        // The WebResources implementation caches references to JAR files. On
        // some platforms these references may lock the JAR files. Since web
        // application start is likely to have read from lots of JARs, trigger
        // a clean-up now.
        getResources().gc();

        // Reinitializing if something went wrong
        if (!ok) {
            setState(LifecycleState.FAILED);
            // Send j2ee.object.failed notification
            if (this.getObjectName() != null) {
                Notification notification =
                        new Notification("j2ee.object.failed", this.getObjectName(), sequenceNumber.getAndIncrement());
                broadcaster.sendNotification(notification);
            }
        } else {
            setState(LifecycleState.STARTING);
        }
    }


    private void checkConstraintsForUncoveredMethods(SecurityConstraint[] constraints) {
        SecurityConstraint[] newConstraints =
                SecurityConstraint.findUncoveredHttpMethods(constraints, getDenyUncoveredHttpMethods(), getLogger());
        for (SecurityConstraint constraint : newConstraints) {
            addConstraint(constraint);
        }
    }


    @Override
    public InstanceManager createInstanceManager() {
        javax.naming.Context context = null;
        if (isUseNaming() && getNamingContextListener() != null) {
            context = getNamingContextListener().getEnvContext();
        }
        Map<String,Map<String,String>> injectionMap =
                buildInjectionMap(getIgnoreAnnotations() ? new NamingResourcesImpl() : getNamingResources());
        return new DefaultInstanceManager(context, injectionMap, this, this.getClass().getClassLoader());
    }

    private Map<String,Map<String,String>> buildInjectionMap(NamingResourcesImpl namingResources) {
        Map<String,Map<String,String>> injectionMap = new HashMap<>();
        for (Injectable resource : namingResources.findLocalEjbs()) {
            addInjectionTarget(resource, injectionMap);
        }
        for (Injectable resource : namingResources.findEjbs()) {
            addInjectionTarget(resource, injectionMap);
        }
        for (Injectable resource : namingResources.findEnvironments()) {
            addInjectionTarget(resource, injectionMap);
        }
        for (Injectable resource : namingResources.findMessageDestinationRefs()) {
            addInjectionTarget(resource, injectionMap);
        }
        for (Injectable resource : namingResources.findResourceEnvRefs()) {
            addInjectionTarget(resource, injectionMap);
        }
        for (Injectable resource : namingResources.findResources()) {
            addInjectionTarget(resource, injectionMap);
        }
        for (Injectable resource : namingResources.findServices()) {
            addInjectionTarget(resource, injectionMap);
        }
        return injectionMap;
    }

    private void addInjectionTarget(Injectable resource, Map<String,Map<String,String>> injectionMap) {
        List<InjectionTarget> injectionTargets = resource.getInjectionTargets();
        if (injectionTargets != null && injectionTargets.size() > 0) {
            String jndiName = resource.getName();
            for (InjectionTarget injectionTarget : injectionTargets) {
                String clazz = injectionTarget.getTargetClass();
                injectionMap.computeIfAbsent(clazz, k -> new HashMap<>()).put(injectionTarget.getTargetName(),
                        jndiName);
            }
        }
    }


    /**
     * Merge the context initialization parameters specified in the application deployment descriptor with the
     * application parameters described in the server configuration, respecting the <code>override</code> property of
     * the application parameters appropriately.
     */
    private void mergeParameters() {
        Map<String,String> mergedParams = new HashMap<>();

        String names[] = findParameters();
        for (String s : names) {
            mergedParams.put(s, findParameter(s));
        }

        ApplicationParameter params[] = findApplicationParameters();
        for (ApplicationParameter param : params) {
            if (param.getOverride()) {
                mergedParams.computeIfAbsent(param.getName(), k -> param.getValue());
            } else {
                mergedParams.put(param.getName(), param.getValue());
            }
        }

        ServletContext sc = getServletContext();
        for (Map.Entry<String,String> entry : mergedParams.entrySet()) {
            sc.setInitParameter(entry.getKey(), entry.getValue());
        }

    }


    /**
     * Stop this component and implement the requirements of
     * {@link org.apache.catalina.util.LifecycleBase#stopInternal()}.
     *
     * @exception LifecycleException if this component detects a fatal error that prevents this component from being
     *                                   used
     */
    @Override
    protected void stopInternal() throws LifecycleException {

        // Send j2ee.state.stopping notification
        if (this.getObjectName() != null) {
            Notification notification =
                    new Notification("j2ee.state.stopping", this.getObjectName(), sequenceNumber.getAndIncrement());
            broadcaster.sendNotification(notification);
        }

        // Context has been removed from Mapper at this point (so no new
        // requests will be mapped) but is still available.

        // Give the in progress async requests a chance to complete
        long limit = System.currentTimeMillis() + unloadDelay;
        while (inProgressAsyncCount.get() > 0 && System.currentTimeMillis() < limit) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                log.info(sm.getString("standardContext.stop.asyncWaitInterrupted"), e);
                break;
            }
        }

        // Once the state is set to STOPPING, the Context will report itself as
        // not available and any in progress async requests will timeout
        setState(LifecycleState.STOPPING);

        // Binding thread
        ClassLoader oldCCL = bindThread();

        try {
            // Stop our child containers, if any
            final Container[] children = findChildren();

            // Stop ContainerBackgroundProcessor thread
            threadStop();

            for (Container child : children) {
                child.stop();
            }

            // Stop our filters
            filterStop();

            Manager manager = getManager();
            if (manager instanceof Lifecycle && ((Lifecycle) manager).getState().isAvailable()) {
                ((Lifecycle) manager).stop();
            }

            // Stop our application listeners
            listenerStop();

            // Finalize our character set mapper
            setCharsetMapper(null);

            // Normal container shutdown processing
            if (log.isTraceEnabled()) {
                log.trace("Processing standard container shutdown");
            }

            // JNDI resources are unbound in CONFIGURE_STOP_EVENT so stop
            // naming resources before they are unbound since NamingResources
            // does a JNDI lookup to retrieve the resource. This needs to be
            // after the application has finished with the resource
            if (namingResources != null) {
                namingResources.stop();
            }

            fireLifecycleEvent(CONFIGURE_STOP_EVENT, null);

            // Stop the Valves in our pipeline (including the basic), if any
            if (pipeline instanceof Lifecycle && ((Lifecycle) pipeline).getState().isAvailable()) {
                ((Lifecycle) pipeline).stop();
            }

            // Clear all application-originated servlet context attributes
            if (context != null) {
                context.clearAttributes();
            }

            Realm realm = getRealmInternal();
            if (realm instanceof Lifecycle) {
                ((Lifecycle) realm).stop();
            }
            Loader loader = getLoader();
            if (loader instanceof Lifecycle) {
                ClassLoader classLoader = loader.getClassLoader();
                ((Lifecycle) loader).stop();
                if (classLoader != null) {
                    InstanceManagerBindings.unbind(classLoader);
                }
            }

            // Stop resources
            resourcesStop();

        } finally {

            // Unbinding thread
            unbindThread(oldCCL);

        }

        // Send j2ee.state.stopped notification
        if (this.getObjectName() != null) {
            Notification notification =
                    new Notification("j2ee.state.stopped", this.getObjectName(), sequenceNumber.getAndIncrement());
            broadcaster.sendNotification(notification);
        }

        // Reset application context
        context = null;

        // This object will no longer be visible or used.
        try {
            resetContext();
        } catch (Exception ex) {
            log.error(sm.getString("standardContext.resetContextFail", getName()), ex);
        }

        // reset the instance manager
        setInstanceManager(null);

        if (log.isTraceEnabled()) {
            log.trace("Stopping complete");
        }

    }

    /**
     * Destroy needs to clean up the context completely. The problem is that undoing all the config in start() and
     * restoring a 'fresh' state is impossible. After stop()/destroy()/init()/start() we should have the same state as
     * if a fresh start was done - i.e read modified web.xml, etc. This can only be done by completely removing the
     * context object and remapping a new one, or by cleaning up everything.
     */
    @Override
    protected void destroyInternal() throws LifecycleException {

        // If in state NEW when destroy is called, the object name will never
        // have been set so the notification can't be created
        if (getObjectName() != null) {
            // Send j2ee.object.deleted notification
            Notification notification =
                    new Notification("j2ee.object.deleted", this.getObjectName(), sequenceNumber.getAndIncrement());
            broadcaster.sendNotification(notification);
        }

        if (namingResources != null) {
            namingResources.destroy();
        }

        Loader loader = getLoader();
        if (loader instanceof Lifecycle) {
            ((Lifecycle) loader).destroy();
        }

        Manager manager = getManager();
        if (manager instanceof Lifecycle) {
            ((Lifecycle) manager).destroy();
        }

        if (resources != null) {
            resources.destroy();
        }

        super.destroyInternal();
    }


    @Override
    public synchronized void backgroundProcess() {

        if (!getState().isAvailable()) {
            return;
        }

        Loader loader = getLoader();
        if (loader != null) {
            try {
                loader.backgroundProcess();
            } catch (Exception e) {
                log.warn(sm.getString("standardContext.backgroundProcess.loader", loader), e);
            }
        }
        Manager manager = getManager();
        if (manager != null) {
            try {
                manager.backgroundProcess();
            } catch (Exception e) {
                log.warn(sm.getString("standardContext.backgroundProcess.manager", manager), e);
            }
        }
        WebResourceRoot resources = getResources();
        if (resources != null) {
            try {
                resources.backgroundProcess();
            } catch (Exception e) {
                log.warn(sm.getString("standardContext.backgroundProcess.resources", resources), e);
            }
        }
        InstanceManager instanceManager = getInstanceManager();
        if (instanceManager != null) {
            try {
                instanceManager.backgroundProcess();
            } catch (Exception e) {
                log.warn(sm.getString("standardContext.backgroundProcess.instanceManager", resources), e);
            }
        }
        super.backgroundProcess();
    }


    private void resetContext() throws Exception {
        // Restore the original state ( pre reading web.xml in start )
        // If you extend this - override this method and make sure to clean up

        // Don't reset anything that is read from a <Context.../> element since
        // <Context .../> elements are read at initialisation will not be read
        // again for this object
        for (Container child : findChildren()) {
            removeChild(child);
        }
        startupTime = 0;
        startTime = 0;
        tldScanTime = 0;

        // Bugzilla 32867
        distributable = false;

        applicationListeners.clear();
        applicationEventListenersList.clear();
        applicationLifecycleListenersObjects = new Object[0];
        jspConfigDescriptor = null;

        initializers.clear();

        createdServlets.clear();

        postConstructMethods.clear();
        preDestroyMethods.clear();

        if (log.isTraceEnabled()) {
            log.trace("resetContext " + getObjectName());
        }
    }


    // ------------------------------------------------------ Protected Methods

    /**
     * Adjust the URL pattern to begin with a leading slash, if appropriate (i.e. we are running a servlet 2.2
     * application). Otherwise, return the specified URL pattern unchanged.
     *
     * @param urlPattern The URL pattern to be adjusted (if needed) and returned
     *
     * @return the URL pattern with a leading slash if needed
     */
    protected String adjustURLPattern(String urlPattern) {

        if (urlPattern == null) {
            return urlPattern;
        }
        if (urlPattern.startsWith("/") || urlPattern.startsWith("*.")) {
            return urlPattern;
        }
        if (!isServlet22()) {
            return urlPattern;
        }
        if (log.isDebugEnabled()) {
            log.debug(sm.getString("standardContext.urlPattern.patternWarning", urlPattern));
        }
        return "/" + urlPattern;

    }


    /**
     * Are we processing a version 2.2 deployment descriptor?
     *
     * @return <code>true</code> if running a legacy Servlet 2.2 application
     */
    @Override
    public boolean isServlet22() {
        return XmlIdentifiers.WEB_22_PUBLIC.equals(publicId);
    }


    @Override
    public Set<String> addServletSecurity(ServletRegistration.Dynamic registration,
            ServletSecurityElement servletSecurityElement) {

        Set<String> conflicts = new HashSet<>();

        Collection<String> urlPatterns = registration.getMappings();
        for (String urlPattern : urlPatterns) {
            boolean foundConflict = false;

            SecurityConstraint[] securityConstraints = findConstraints();
            for (SecurityConstraint securityConstraint : securityConstraints) {

                SecurityCollection[] collections = securityConstraint.findCollections();
                for (SecurityCollection collection : collections) {
                    if (collection.findPattern(urlPattern)) {
                        // First pattern found will indicate if there is a
                        // conflict since for any given pattern all matching
                        // constraints will be from either the descriptor or
                        // not. It is not permitted to have a mixture
                        if (collection.isFromDescriptor()) {
                            // Skip this pattern
                            foundConflict = true;
                            conflicts.add(urlPattern);
                            break;
                        } else {
                            // Need to overwrite constraint for this pattern
                            collection.removePattern(urlPattern);
                            // If the collection is now empty, remove it
                            if (collection.findPatterns().length == 0) {
                                securityConstraint.removeCollection(collection);
                            }
                        }
                    }
                }

                // If the constraint now has no collections - remove it
                if (securityConstraint.findCollections().length == 0) {
                    removeConstraint(securityConstraint);
                }

                // No need to check other constraints for the current pattern
                // once a conflict has been found
                if (foundConflict) {
                    break;
                }
            }

            // Note: For programmatically added Servlets this may not be the
            // complete set of security constraints since additional
            // URL patterns can be added after the application has called
            // setSecurity. For all programmatically added servlets, the
            // #dynamicServletAdded() method sets a flag that ensures that
            // the constraints are re-evaluated before the servlet is
            // first used

            // If the pattern did not conflict, add the new constraint(s).
            if (!foundConflict) {
                SecurityConstraint[] newSecurityConstraints =
                        SecurityConstraint.createConstraints(servletSecurityElement, urlPattern);
                for (SecurityConstraint securityConstraint : newSecurityConstraints) {
                    addConstraint(securityConstraint);
                }
            }
        }

        return conflicts;
    }


    /**
     * Bind current thread, both for CL purposes and for JNDI ENC support during : startup, shutdown and reloading of
     * the context.
     *
     * @return the previous context class loader
     */
    protected ClassLoader bindThread() {

        ClassLoader oldContextClassLoader = bind(false, null);

        if (isUseNaming()) {
            try {
                ContextBindings.bindThread(this, getNamingToken());
            } catch (NamingException e) {
                // Silent catch, as this is a normal case during the early
                // startup stages
            }
        }

        return oldContextClassLoader;
    }


    /**
     * Unbind thread and restore the specified context classloader.
     *
     * @param oldContextClassLoader the previous classloader
     */
    protected void unbindThread(ClassLoader oldContextClassLoader) {

        if (isUseNaming()) {
            ContextBindings.unbindThread(this, getNamingToken());
        }

        unbind(false, oldContextClassLoader);
    }


    @Override
    public ClassLoader bind(boolean usePrivilegedAction, ClassLoader originalClassLoader) {
        Loader loader = getLoader();
        ClassLoader webApplicationClassLoader = null;
        if (loader != null) {
            webApplicationClassLoader = loader.getClassLoader();
        }

        Thread currentThread = Thread.currentThread();
        if (originalClassLoader == null) {
            if (usePrivilegedAction) {
                PrivilegedAction<ClassLoader> pa = new PrivilegedGetTccl(currentThread);
                originalClassLoader = AccessController.doPrivileged(pa);
            } else {
                originalClassLoader = currentThread.getContextClassLoader();
            }
        }

        if (webApplicationClassLoader == null || webApplicationClassLoader == originalClassLoader) {
            // Not possible or not necessary to switch class loaders. Return
            // null to indicate this.
            return null;
        }

        ThreadBindingListener threadBindingListener = getThreadBindingListener();

        if (usePrivilegedAction) {
            PrivilegedAction<Void> pa = new PrivilegedSetTccl(currentThread, webApplicationClassLoader);
            AccessController.doPrivileged(pa);
        } else {
            currentThread.setContextClassLoader(webApplicationClassLoader);
        }
        if (threadBindingListener != null) {
            try {
                threadBindingListener.bind();
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                log.error(sm.getString("standardContext.threadBindingListenerError", getName()), t);
            }
        }

        return originalClassLoader;
    }


    @Override
    public void unbind(boolean usePrivilegedAction, ClassLoader originalClassLoader) {
        if (originalClassLoader == null) {
            return;
        }

        if (threadBindingListener != null) {
            try {
                threadBindingListener.unbind();
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                log.error(sm.getString("standardContext.threadBindingListenerError", getName()), t);
            }
        }

        Thread currentThread = Thread.currentThread();
        if (usePrivilegedAction) {
            PrivilegedAction<Void> pa = new PrivilegedSetTccl(currentThread, originalClassLoader);
            AccessController.doPrivileged(pa);
        } else {
            currentThread.setContextClassLoader(originalClassLoader);
        }
    }


    /**
     * Get naming context full name.
     *
     * @return the context name
     */
    private String getNamingContextName() {
        if (namingContextName == null) {
            Container parent = getParent();
            if (parent == null) {
                namingContextName = getName();
            } else {
                Deque<String> stk = new ArrayDeque<>();
                StringBuilder buff = new StringBuilder();
                while (parent != null) {
                    stk.addFirst(parent.getName());
                    parent = parent.getParent();
                }
                while (!stk.isEmpty()) {
                    buff.append('/').append(stk.remove());
                }
                buff.append(getName());
                namingContextName = buff.toString();
            }
        }
        return namingContextName;
    }


    /**
     * Naming context listener accessor.
     *
     * @return the naming context listener associated with the webapp
     */
    public NamingContextListener getNamingContextListener() {
        return namingContextListener;
    }


    /**
     * Naming context listener setter.
     *
     * @param namingContextListener the new naming context listener
     */
    public void setNamingContextListener(NamingContextListener namingContextListener) {
        this.namingContextListener = namingContextListener;
    }


    /**
     * @return the request processing paused flag for this Context.
     */
    @Override
    public boolean getPaused() {
        return this.paused;
    }


    @Override
    public boolean fireRequestInitEvent(ServletRequest request) {

        Object instances[] = getApplicationEventListeners();

        if ((instances != null) && (instances.length > 0)) {

            ServletRequestEvent event = new ServletRequestEvent(getServletContext(), request);

            for (Object instance : instances) {
                if (instance == null) {
                    continue;
                }
                if (!(instance instanceof ServletRequestListener)) {
                    continue;
                }
                ServletRequestListener listener = (ServletRequestListener) instance;

                try {
                    listener.requestInitialized(event);
                } catch (Throwable t) {
                    ExceptionUtils.handleThrowable(t);
                    getLogger().error(
                            sm.getString("standardContext.requestListener.requestInit", instance.getClass().getName()),
                            t);
                    request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, t);
                    return false;
                }
            }
        }
        return true;
    }


    @Override
    public boolean fireRequestDestroyEvent(ServletRequest request) {
        Object instances[] = getApplicationEventListeners();

        if ((instances != null) && (instances.length > 0)) {

            ServletRequestEvent event = new ServletRequestEvent(getServletContext(), request);

            for (int i = 0; i < instances.length; i++) {
                int j = (instances.length - 1) - i;
                if (instances[j] == null) {
                    continue;
                }
                if (!(instances[j] instanceof ServletRequestListener)) {
                    continue;
                }
                ServletRequestListener listener = (ServletRequestListener) instances[j];

                try {
                    listener.requestDestroyed(event);
                } catch (Throwable t) {
                    ExceptionUtils.handleThrowable(t);
                    getLogger().error(sm.getString("standardContext.requestListener.requestDestroyed",
                            instances[j].getClass().getName()), t);
                    request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, t);
                    return false;
                }
            }
        }
        return true;
    }


    @Override
    public void addPostConstructMethod(String clazz, String method) {
        if (clazz == null || method == null) {
            throw new IllegalArgumentException(sm.getString("standardContext.postconstruct.required"));
        }
        if (postConstructMethods.get(clazz) != null) {
            throw new IllegalArgumentException(sm.getString("standardContext.postconstruct.duplicate", clazz));
        }

        postConstructMethods.put(clazz, method);
        fireContainerEvent("addPostConstructMethod", clazz);
    }


    @Override
    public void removePostConstructMethod(String clazz) {
        postConstructMethods.remove(clazz);
        fireContainerEvent("removePostConstructMethod", clazz);
    }


    @Override
    public void addPreDestroyMethod(String clazz, String method) {
        if (clazz == null || method == null) {
            throw new IllegalArgumentException(sm.getString("standardContext.predestroy.required"));
        }
        if (preDestroyMethods.get(clazz) != null) {
            throw new IllegalArgumentException(sm.getString("standardContext.predestroy.duplicate", clazz));
        }

        preDestroyMethods.put(clazz, method);
        fireContainerEvent("addPreDestroyMethod", clazz);
    }


    @Override
    public void removePreDestroyMethod(String clazz) {
        preDestroyMethods.remove(clazz);
        fireContainerEvent("removePreDestroyMethod", clazz);
    }


    @Override
    public String findPostConstructMethod(String clazz) {
        return postConstructMethods.get(clazz);
    }


    @Override
    public String findPreDestroyMethod(String clazz) {
        return preDestroyMethods.get(clazz);
    }


    @Override
    public Map<String,String> findPostConstructMethods() {
        return postConstructMethods;
    }


    @Override
    public Map<String,String> findPreDestroyMethods() {
        return preDestroyMethods;
    }


    /**
     * Set the appropriate context attribute for our work directory.
     */
    protected void postWorkDirectory() {

        // Acquire (or calculate) the work directory path
        String workDir = getWorkDir();
        if (workDir == null || workDir.length() == 0) {

            // Retrieve our parent (normally a host) name
            String hostName = null;
            String engineName = null;
            String hostWorkDir = null;
            Container parentHost = getParent();
            if (parentHost != null) {
                hostName = parentHost.getName();
                if (parentHost instanceof StandardHost) {
                    hostWorkDir = ((StandardHost) parentHost).getWorkDir();
                }
                Container parentEngine = parentHost.getParent();
                if (parentEngine != null) {
                    engineName = parentEngine.getName();
                }
            }
            if ((hostName == null) || (hostName.length() < 1)) {
                hostName = "_";
            }
            if ((engineName == null) || (engineName.length() < 1)) {
                engineName = "_";
            }

            String temp = getBaseName();
            if (temp.startsWith("/")) {
                temp = temp.substring(1);
            }
            temp = temp.replace('/', '_');
            temp = temp.replace('\\', '_');
            if (temp.length() < 1) {
                temp = ContextName.ROOT_NAME;
            }
            if (hostWorkDir != null) {
                workDir = hostWorkDir + File.separator + temp;
            } else {
                workDir = "work" + File.separator + engineName + File.separator + hostName + File.separator + temp;
            }
            setWorkDir(workDir);
        }

        // Create this directory if necessary
        File dir = new File(workDir);
        if (!dir.isAbsolute()) {
            String catalinaHomePath = null;
            try {
                catalinaHomePath = getCatalinaBase().getCanonicalPath();
                dir = new File(catalinaHomePath, workDir);
            } catch (IOException e) {
                log.warn(sm.getString("standardContext.workCreateException", workDir, catalinaHomePath, getName()), e);
            }
        }
        if (!dir.mkdirs() && !dir.isDirectory()) {
            log.warn(sm.getString("standardContext.workCreateFail", dir, getName()));
        }

        // Set the appropriate servlet context attribute
        if (context == null) {
            getServletContext();
        }
        context.setAttribute(ServletContext.TEMPDIR, dir);
        context.setAttributeReadOnly(ServletContext.TEMPDIR);
    }


    /**
     * Set the request processing paused flag for this Context.
     *
     * @param paused The new request processing paused flag
     */
    private void setPaused(boolean paused) {

        this.paused = paused;

    }


    /**
     * Validate the syntax of a proposed <code>&lt;url-pattern&gt;</code> for conformance with specification
     * requirements.
     *
     * @param urlPattern URL pattern to be validated
     *
     * @return <code>true</code> if the URL pattern is conformant
     */
    private boolean validateURLPattern(String urlPattern) {

        if (urlPattern == null) {
            return false;
        }
        if (urlPattern.indexOf('\n') >= 0 || urlPattern.indexOf('\r') >= 0) {
            return false;
        }
        if (urlPattern.equals("")) {
            return true;
        }
        if (urlPattern.startsWith("*.")) {
            if (urlPattern.indexOf('/') < 0) {
                checkUnusualURLPattern(urlPattern);
                return true;
            } else {
                return false;
            }
        }
        if (urlPattern.startsWith("/") && !urlPattern.contains("*.")) {
            checkUnusualURLPattern(urlPattern);
            return true;
        } else {
            return false;
        }

    }


    /**
     * Check for unusual but valid <code>&lt;url-pattern&gt;</code>s. See Bugzilla 34805, 43079 & 43080
     */
    private void checkUnusualURLPattern(String urlPattern) {
        if (log.isInfoEnabled()) {
            // First group checks for '*' or '/foo*' style patterns
            // Second group checks for *.foo.bar style patterns
            if ((urlPattern.endsWith("*") &&
                    (urlPattern.length() < 2 || urlPattern.charAt(urlPattern.length() - 2) != '/')) ||
                    urlPattern.startsWith("*.") && urlPattern.length() > 2 && urlPattern.lastIndexOf('.') > 1) {
                log.info(sm.getString("standardContext.suspiciousUrl", urlPattern, getName()));
            }
        }
    }


    // ------------------------------------------------------------- Operations

    @Override
    protected String getObjectNameKeyProperties() {

        StringBuilder keyProperties = new StringBuilder("j2eeType=WebModule,");
        keyProperties.append(getObjectKeyPropertiesNameOnly());
        keyProperties.append(",J2EEApplication=");
        keyProperties.append(getJ2EEApplication());
        keyProperties.append(",J2EEServer=");
        keyProperties.append(getJ2EEServer());

        return keyProperties.toString();
    }

    private String getObjectKeyPropertiesNameOnly() {
        StringBuilder result = new StringBuilder("name=//");
        String hostname = getParent().getName();
        if (hostname == null) {
            result.append("DEFAULT");
        } else {
            result.append(hostname);
        }

        String contextName = getName();
        if (!contextName.startsWith("/")) {
            result.append('/');
        }
        result.append(contextName);

        return result.toString();
    }

    @Override
    protected void initInternal() throws LifecycleException {
        super.initInternal();

        // Register the naming resources
        if (namingResources != null) {
            namingResources.init();
        }

        // Send j2ee.object.created notification
        if (this.getObjectName() != null) {
            Notification notification =
                    new Notification("j2ee.object.created", this.getObjectName(), sequenceNumber.getAndIncrement());
            broadcaster.sendNotification(notification);
        }
    }


    /**
     * Remove a JMX notificationListener
     *
     * @see javax.management.NotificationEmitter#removeNotificationListener(javax.management.NotificationListener,
     *          javax.management.NotificationFilter, java.lang.Object)
     */
    @Override
    public void removeNotificationListener(NotificationListener listener, NotificationFilter filter, Object object)
            throws ListenerNotFoundException {
        broadcaster.removeNotificationListener(listener, filter, object);
    }

    private MBeanNotificationInfo[] notificationInfo;

    /**
     * Get JMX Broadcaster Info
     *
     * @see javax.management.NotificationBroadcaster#getNotificationInfo()
     */
    @Override
    public MBeanNotificationInfo[] getNotificationInfo() {
        // FIXME: we not send j2ee.attribute.changed
        if (notificationInfo == null) {
            notificationInfo = new MBeanNotificationInfo[] {
                    new MBeanNotificationInfo(new String[] { "j2ee.object.created" }, Notification.class.getName(),
                            "web application is created"),
                    new MBeanNotificationInfo(new String[] { "j2ee.state.starting" }, Notification.class.getName(),
                            "change web application is starting"),
                    new MBeanNotificationInfo(new String[] { "j2ee.state.running" }, Notification.class.getName(),
                            "web application is running"),
                    new MBeanNotificationInfo(new String[] { "j2ee.state.stopping" }, Notification.class.getName(),
                            "web application start to stopped"),
                    new MBeanNotificationInfo(new String[] { "j2ee.object.stopped" }, Notification.class.getName(),
                            "web application is stopped"),
                    new MBeanNotificationInfo(new String[] { "j2ee.object.deleted" }, Notification.class.getName(),
                            "web application is deleted"),
                    new MBeanNotificationInfo(new String[] { "j2ee.object.failed" }, Notification.class.getName(),
                            "web application failed") };
        }

        return notificationInfo;
    }


    /**
     * Add a JMX NotificationListener
     *
     * @see javax.management.NotificationBroadcaster#addNotificationListener(javax.management.NotificationListener,
     *          javax.management.NotificationFilter, java.lang.Object)
     */
    @Override
    public void addNotificationListener(NotificationListener listener, NotificationFilter filter, Object object)
            throws IllegalArgumentException {
        broadcaster.addNotificationListener(listener, filter, object);
    }


    /**
     * Remove a JMX-NotificationListener
     *
     * @see javax.management.NotificationBroadcaster#removeNotificationListener(javax.management.NotificationListener)
     */
    @Override
    public void removeNotificationListener(NotificationListener listener) throws ListenerNotFoundException {
        broadcaster.removeNotificationListener(listener);
    }


    // ------------------------------------------------------------- Attributes

    /**
     * @return the naming resources associated with this web application.
     */
    public String[] getWelcomeFiles() {

        return findWelcomeFiles();

    }


    @Override
    public boolean getXmlNamespaceAware() {
        return webXmlNamespaceAware;
    }


    @Override
    public void setXmlNamespaceAware(boolean webXmlNamespaceAware) {
        this.webXmlNamespaceAware = webXmlNamespaceAware;
    }


    @Override
    public void setXmlValidation(boolean webXmlValidation) {
        this.webXmlValidation = webXmlValidation;
    }


    @Override
    public boolean getXmlValidation() {
        return webXmlValidation;
    }


    @Override
    public void setXmlBlockExternal(boolean xmlBlockExternal) {
        this.xmlBlockExternal = xmlBlockExternal;
    }


    @Override
    public boolean getXmlBlockExternal() {
        return xmlBlockExternal;
    }


    @Override
    public void setTldValidation(boolean tldValidation) {
        this.tldValidation = tldValidation;
    }


    @Override
    public boolean getTldValidation() {
        return tldValidation;
    }


    /**
     * The J2EE Server ObjectName this module is deployed on.
     */
    private String server = null;

    public String getServer() {
        return server;
    }

    public String setServer(String server) {
        return this.server = server;
    }

    /**
     * Gets the time this context was started.
     *
     * @return Time (in milliseconds since January 1, 1970, 00:00:00) when this context was started
     */
    public long getStartTime() {
        return startTime;
    }


    private static class NoPluggabilityServletContext implements ServletContext {

        private final ServletContext sc;

        NoPluggabilityServletContext(ServletContext sc) {
            this.sc = sc;
        }

        @Override
        public String getContextPath() {
            return sc.getContextPath();
        }

        @Override
        public ServletContext getContext(String uripath) {
            return sc.getContext(uripath);
        }

        @Override
        public int getMajorVersion() {
            return sc.getMajorVersion();
        }

        @Override
        public int getMinorVersion() {
            return sc.getMinorVersion();
        }

        @Override
        public int getEffectiveMajorVersion() {
            return sc.getEffectiveMajorVersion();
        }

        @Override
        public int getEffectiveMinorVersion() {
            return sc.getEffectiveMinorVersion();
        }

        @Override
        public String getMimeType(String file) {
            return sc.getMimeType(file);
        }

        @Override
        public Set<String> getResourcePaths(String path) {
            return sc.getResourcePaths(path);
        }

        @Override
        public URL getResource(String path) throws MalformedURLException {
            return sc.getResource(path);
        }

        @Override
        public InputStream getResourceAsStream(String path) {
            return sc.getResourceAsStream(path);
        }

        @Override
        public RequestDispatcher getRequestDispatcher(String path) {
            return sc.getRequestDispatcher(path);
        }

        @Override
        public RequestDispatcher getNamedDispatcher(String name) {
            return sc.getNamedDispatcher(name);
        }

        @Override
        public void log(String msg) {
            sc.log(msg);
        }

        @Override
        public void log(String message, Throwable throwable) {
            sc.log(message, throwable);
        }

        @Override
        public String getRealPath(String path) {
            return sc.getRealPath(path);
        }

        @Override
        public String getServerInfo() {
            return sc.getServerInfo();
        }

        @Override
        public String getInitParameter(String name) {
            return sc.getInitParameter(name);
        }

        @Override
        public Enumeration<String> getInitParameterNames() {
            return sc.getInitParameterNames();
        }

        @Override
        public boolean setInitParameter(String name, String value) {
            throw new UnsupportedOperationException(sm.getString("noPluggabilityServletContext.notAllowed"));
        }

        @Override
        public Object getAttribute(String name) {
            return sc.getAttribute(name);
        }

        @Override
        public Enumeration<String> getAttributeNames() {
            return sc.getAttributeNames();
        }

        @Override
        public void setAttribute(String name, Object object) {
            sc.setAttribute(name, object);
        }

        @Override
        public void removeAttribute(String name) {
            sc.removeAttribute(name);
        }

        @Override
        public String getServletContextName() {
            return sc.getServletContextName();
        }

        @Override
        public Dynamic addServlet(String servletName, String className) {
            throw new UnsupportedOperationException(sm.getString("noPluggabilityServletContext.notAllowed"));
        }

        @Override
        public Dynamic addServlet(String servletName, Servlet servlet) {
            throw new UnsupportedOperationException(sm.getString("noPluggabilityServletContext.notAllowed"));
        }

        @Override
        public Dynamic addServlet(String servletName, Class<? extends Servlet> servletClass) {
            throw new UnsupportedOperationException(sm.getString("noPluggabilityServletContext.notAllowed"));
        }

        @Override
        public Dynamic addJspFile(String jspName, String jspFile) {
            throw new UnsupportedOperationException(sm.getString("noPluggabilityServletContext.notAllowed"));
        }

        @Override
        public <T extends Servlet> T createServlet(Class<T> c) throws ServletException {
            throw new UnsupportedOperationException(sm.getString("noPluggabilityServletContext.notAllowed"));
        }

        @Override
        public ServletRegistration getServletRegistration(String servletName) {
            throw new UnsupportedOperationException(sm.getString("noPluggabilityServletContext.notAllowed"));
        }

        @Override
        public Map<String,? extends ServletRegistration> getServletRegistrations() {
            throw new UnsupportedOperationException(sm.getString("noPluggabilityServletContext.notAllowed"));
        }

        @Override
        public FilterRegistration.Dynamic addFilter(String filterName, String className) {
            throw new UnsupportedOperationException(sm.getString("noPluggabilityServletContext.notAllowed"));
        }

        @Override
        public FilterRegistration.Dynamic addFilter(String filterName, Filter filter) {
            throw new UnsupportedOperationException(sm.getString("noPluggabilityServletContext.notAllowed"));
        }

        @Override
        public FilterRegistration.Dynamic addFilter(String filterName,
                Class<? extends Filter> filterClass) {
            throw new UnsupportedOperationException(sm.getString("noPluggabilityServletContext.notAllowed"));
        }

        @Override
        public <T extends Filter> T createFilter(Class<T> c) throws ServletException {
            throw new UnsupportedOperationException(sm.getString("noPluggabilityServletContext.notAllowed"));
        }

        @Override
        public FilterRegistration getFilterRegistration(String filterName) {
            throw new UnsupportedOperationException(sm.getString("noPluggabilityServletContext.notAllowed"));
        }

        @Override
        public Map<String,? extends FilterRegistration> getFilterRegistrations() {
            throw new UnsupportedOperationException(sm.getString("noPluggabilityServletContext.notAllowed"));
        }

        @Override
        public SessionCookieConfig getSessionCookieConfig() {
            throw new UnsupportedOperationException(sm.getString("noPluggabilityServletContext.notAllowed"));
        }

        @Override
        public void setSessionTrackingModes(Set<SessionTrackingMode> sessionTrackingModes) {
            throw new UnsupportedOperationException(sm.getString("noPluggabilityServletContext.notAllowed"));
        }

        @Override
        public Set<SessionTrackingMode> getDefaultSessionTrackingModes() {
            return sc.getDefaultSessionTrackingModes();
        }

        @Override
        public Set<SessionTrackingMode> getEffectiveSessionTrackingModes() {
            return sc.getEffectiveSessionTrackingModes();
        }

        @Override
        public void addListener(String className) {
            throw new UnsupportedOperationException(sm.getString("noPluggabilityServletContext.notAllowed"));
        }

        @Override
        public <T extends EventListener> void addListener(T t) {
            throw new UnsupportedOperationException(sm.getString("noPluggabilityServletContext.notAllowed"));
        }

        @Override
        public void addListener(Class<? extends EventListener> listenerClass) {
            throw new UnsupportedOperationException(sm.getString("noPluggabilityServletContext.notAllowed"));
        }

        @Override
        public <T extends EventListener> T createListener(Class<T> c) throws ServletException {
            throw new UnsupportedOperationException(sm.getString("noPluggabilityServletContext.notAllowed"));
        }

        @Override
        public JspConfigDescriptor getJspConfigDescriptor() {
            return sc.getJspConfigDescriptor();
        }

        @Override
        public ClassLoader getClassLoader() {
            return sc.getClassLoader();
        }

        @Override
        public void declareRoles(String... roleNames) {
            throw new UnsupportedOperationException(sm.getString("noPluggabilityServletContext.notAllowed"));
        }

        @Override
        public String getVirtualServerName() {
            return sc.getVirtualServerName();
        }

        @Override
        public int getSessionTimeout() {
            return sc.getSessionTimeout();
        }

        @Override
        public void setSessionTimeout(int sessionTimeout) {
            throw new UnsupportedOperationException(sm.getString("noPluggabilityServletContext.notAllowed"));
        }

        @Override
        public String getRequestCharacterEncoding() {
            return sc.getRequestCharacterEncoding();
        }

        @Override
        public void setRequestCharacterEncoding(String encoding) {
            throw new UnsupportedOperationException(sm.getString("noPluggabilityServletContext.notAllowed"));
        }

        @Override
        public String getResponseCharacterEncoding() {
            return sc.getResponseCharacterEncoding();
        }

        @Override
        public void setResponseCharacterEncoding(String encoding) {
            throw new UnsupportedOperationException(sm.getString("noPluggabilityServletContext.notAllowed"));
        }
    }
}
