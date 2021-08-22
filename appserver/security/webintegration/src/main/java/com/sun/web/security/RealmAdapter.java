/*
 * Copyright (c) 1997, 2020 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package com.sun.web.security;

import static com.sun.enterprise.security.auth.digest.api.Constants.A1;
import static com.sun.enterprise.security.web.integration.WebSecurityManager.getContextID;
import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_SERVICE_UNAVAILABLE;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;
import static org.apache.catalina.ContainerEvent.AFTER_AUTHENTICATION;
import static org.apache.catalina.ContainerEvent.AFTER_POST_AUTHENTICATION;
import static org.apache.catalina.ContainerEvent.BEFORE_AUTHENTICATION;
import static org.apache.catalina.ContainerEvent.BEFORE_POST_AUTHENTICATION;
import static org.apache.catalina.Globals.WRAPPED_REQUEST;
import static org.apache.catalina.Globals.WRAPPED_RESPONSE;

import java.io.IOException;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.net.URLEncoder;
/*V3:Comment
import com.sun.enterprise.webservice.monitoring.WebServiceEngineImpl;
import com.sun.enterprise.webservice.monitoring.AuthenticationListener;
 */
import java.security.AccessController;
import java.security.Principal;
import java.security.PrivilegedAction;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.security.auth.Subject;
import javax.security.auth.x500.X500Principal;

import org.apache.catalina.Authenticator;
import org.apache.catalina.Container;
import org.apache.catalina.ContainerEvent;
import org.apache.catalina.Context;
import org.apache.catalina.Globals;
import org.apache.catalina.HttpRequest;
import org.apache.catalina.HttpResponse;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.authenticator.AuthenticatorBase;
import org.apache.catalina.deploy.LoginConfig;
import org.apache.catalina.deploy.SecurityConstraint;
import org.apache.catalina.realm.Constants;
import org.apache.catalina.realm.RealmBase;
import org.glassfish.api.admin.ServerEnvironment;
import org.glassfish.api.invocation.ComponentInvocation;
import org.glassfish.grizzly.config.dom.NetworkConfig;
import org.glassfish.grizzly.config.dom.NetworkListener;
import org.glassfish.grizzly.config.dom.NetworkListeners;
import org.glassfish.hk2.api.PerLookup;
import org.glassfish.hk2.api.PostConstruct;
import org.glassfish.internal.api.ServerContext;
import org.glassfish.security.common.CNonceCache;
import org.glassfish.security.common.NonceInfo;
import org.jvnet.hk2.annotations.Service;

//import com.sun.enterprise.Switch;
import com.sun.enterprise.deployment.Application;
import com.sun.enterprise.deployment.RunAsIdentityDescriptor;
import com.sun.enterprise.deployment.WebBundleDescriptor;
import com.sun.enterprise.deployment.WebComponentDescriptor;
//import org.glassfish.deployment.common.SecurityRoleMapper;
import com.sun.enterprise.deployment.web.LoginConfiguration;
import com.sun.enterprise.security.AppCNonceCacheMap;
import com.sun.enterprise.security.CNonceCacheFactory;
import com.sun.enterprise.security.SecurityContext;
import com.sun.enterprise.security.WebSecurityDeployerProbeProvider;
import com.sun.enterprise.security.auth.digest.api.DigestAlgorithmParameter;
import com.sun.enterprise.security.auth.digest.api.Key;
import com.sun.enterprise.security.auth.digest.impl.DigestParameterGenerator;
import com.sun.enterprise.security.auth.digest.impl.HttpAlgorithmParameterImpl;
import com.sun.enterprise.security.auth.digest.impl.NestedDigestAlgoParamImpl;
import com.sun.enterprise.security.auth.login.DigestCredentials;
import com.sun.enterprise.security.auth.login.LoginContextDriver;
import com.sun.enterprise.security.auth.realm.certificate.CertificateRealm;
import com.sun.enterprise.security.authorize.PolicyContextHandlerImpl;
import com.sun.enterprise.security.integration.RealmInitializer;
import com.sun.enterprise.security.jmac.config.HttpServletConstants;
import com.sun.enterprise.security.jmac.config.HttpServletHelper;
import com.sun.enterprise.security.web.integration.WebPrincipal;
import com.sun.enterprise.security.web.integration.WebSecurityManager;
import com.sun.enterprise.security.web.integration.WebSecurityManagerFactory;
import com.sun.enterprise.util.net.NetUtils;
import com.sun.logging.LogDomains;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Provider;
import jakarta.security.auth.message.AuthException;
import jakarta.security.auth.message.AuthStatus;
import jakarta.security.auth.message.MessageInfo;
import jakarta.security.auth.message.config.ServerAuthConfig;
import jakarta.security.auth.message.config.ServerAuthContext;
import jakarta.security.jacc.PolicyContext;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * This is the realm adapter used to authenticate users and authorize access to web resources. The authenticate method is called
 * by Tomcat to authenticate users. The hasRole method is called by Tomcat during the authorization process.
 *
 * @author Harpreet Singh
 * @author JeanFrancois Arcand
 */
@Service
@PerLookup
public class RealmAdapter extends RealmBase implements RealmInitializer, PostConstruct {

    public static final String SECURITY_CONTEXT = "SecurityContext";
    public static final String BASIC = "BASIC";
    public static final String FORM = "FORM";

    protected static final String CONF_FILE_NAME = "auth.conf";
    protected static final String HTTP_SERVLET_LAYER = "HttpServlet";

    private static final Logger _logger = LogDomains.getLogger(RealmAdapter.class, LogDomains.WEB_LOGGER);
    private static final ResourceBundle rb = _logger.getResourceBundle();

    private static final String SERVER_AUTH_CONTEXT = "__jakarta.security.auth.message.ServerAuthContext";
    private static final String MESSAGE_INFO = "__jakarta.security.auth.message.MessageInfo";
    private static final WebSecurityDeployerProbeProvider websecurityProbeProvider = new WebSecurityDeployerProbeProvider();

    // name of system property that can be used to define
    // corresponding default provider for system apps.
    private static final String SYSTEM_HTTPSERVLET_SECURITY_PROVIDER = "system_httpservlet_security_provider";

    private WebBundleDescriptor webBundleDescriptor;

    private HashMap<String, String> runAsPrincipals;
    // required for realm-per-app login
    private String _realmName;
    /**
     * Descriptive information about this Realm implementation.
     */
    protected static final String name = "J2EE-RI-RealmAdapter";

    /**
     * The context Id value needed for Jakarta Authorization
     */
    private String contextId;
    private Container virtualServer;

    /**
     * A <code>WebSecurityManager</code> object associated with a CONTEXT_ID
     */
    protected volatile WebSecurityManager webSecurityManager;
    /**
     * The factory used for creating <code>WebSecurityManager</code> object.
     */
    @Inject
    protected WebSecurityManagerFactory webSecurityManagerFactory;

    protected boolean isCurrentURIincluded = false;
    // private ArrayList roles = null;
    /*
     * the following fields are used to implement a bypass of FBL related targets
     */
    protected final ReadWriteLock rwLock = new ReentrantReadWriteLock();
    private boolean contextEvaluated = false;
    private String loginPage;
    private String errorPage;
    private final static SecurityConstraint[] emptyConstraints = new SecurityConstraint[] {};
    /**
     * the default provider id for system apps if one has been established. the default provider for system apps is established by
     * defining a system property.
     */
    private static String defaultSystemProviderID = getDefaultSystemProviderID();
    // private String appID;
    private String moduleID;
    private boolean isSystemApp;
    private HttpServletHelper helper;
    // PERF Fix.
    // there maybe a race condition but since its a boolean it does not matter.
    // as all threads would evaluate the same result.
    private Boolean secExtEnabled;

    @Inject
    private ServerContext serverContext;

    @Inject
    private Provider<AppCNonceCacheMap> appCNonceCacheMapProvider;

    @Inject
    private Provider<CNonceCacheFactory> cNonceCacheFactoryProvider;

    @Inject
    @Named(ServerEnvironment.DEFAULT_INSTANCE_NAME)
    private NetworkConfig networkConfig;

    private CNonceCacheFactory cNonceCacheFactory;
    private CNonceCache cnonces;
    private AppCNonceCacheMap haCNonceCacheMap;

    private NetworkListeners nwListeners;

    /**
     * ThreadLocal object to keep track of the reentrancy status of each thread. It contains a byte[] object whose single element is
     * either 0 (initial value or no reentrancy), or 1 (current thread is reentrant). When a thread exits the implies method, byte[0]
     * is always reset to 0.
     */
    private static ThreadLocal reentrancyStatus;

    static {
        reentrancyStatus = new ThreadLocal() {

            @Override
            protected synchronized Object initialValue() {
                return new byte[] { 0 };
            }
        };
    }

    public RealmAdapter() {
        // used during Injection in WebContainer (glue code)
    }

    /**
     * Create for WS Ejb endpoint authentication. Roles related data is not available here.
     */

    public RealmAdapter(String realmName, String moduleID) {
        _realmName = realmName;
        this.moduleID = moduleID;
    }

    @Override
    public void initializeRealm(Object descriptor, boolean isSystemApp, String realmName) {
        this.isSystemApp = isSystemApp;
        webBundleDescriptor = (WebBundleDescriptor) descriptor;
        Application application = webBundleDescriptor.getApplication();

        LoginConfiguration loginConfig = webBundleDescriptor.getLoginConfiguration();
        _realmName = application.getRealm();
        if (_realmName == null && loginConfig != null) {
            _realmName = loginConfig.getRealmName();
        }
        if (realmName != null && (_realmName == null || _realmName.equals(""))) {
            _realmName = realmName;
        }

        contextId = WebSecurityManager.getContextID(webBundleDescriptor);
        runAsPrincipals = new HashMap<>();

        for (WebComponentDescriptor componentDescriptor : webBundleDescriptor.getWebComponentDescriptors()) {
            RunAsIdentityDescriptor runAsDescriptor = componentDescriptor.getRunAsIdentity();

            if (runAsDescriptor != null) {
                String principal = runAsDescriptor.getPrincipal();
                String servlet = componentDescriptor.getCanonicalName();

                if (principal == null || servlet == null) {
                    _logger.warning("web.realmadapter.norunas");
                } else {
                    runAsPrincipals.put(servlet, principal);
                    _logger.fine(() -> "Servlet " + servlet + " will run-as: " + principal);
                }
            }
        }

        this.moduleID = webBundleDescriptor.getModuleID();
    }

    /**
     * Return <tt>true</tt> if a Security Extension is available.
     *
     * @return <tt>true</tt> if a Security Extension is available. 1171
     */
    @Override
    public boolean isSecurityExtensionEnabled(final ServletContext context) {
        if (helper == null) {
            initConfigHelper(context);
        }

        try {
            return (helper.getServerAuthConfig() != null);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }

    }

    /**
     * Returns null 1. if there are no security constraints defined on any of the web resources within the context, or 2. if the
     * target is a form login related page or target.
     *
     * otherwise return an empty array of SecurityConstraint.
     */
    @Override
    public SecurityConstraint[] findSecurityConstraints(HttpRequest request, Context context) {
        if (helper == null) {
            initConfigHelper(context.getServletContext());
        }

        WebSecurityManager webSecurityManager = getWebSecurityManager(false);
        if (webSecurityManager != null && webSecurityManager.hasNoConstrainedResources() && !isSecurityExtensionEnabled(context.getServletContext())) {
            return null;
        }

        return emptyConstraints;
    }

    /**
     * Returns null 1. if there are no security constraints defined on any of the web resources within the context, or 2. if the
     * target is a form login related page or target.
     *
     * otherwise return an empty array of SecurityConstraint.
     */
    @Override
    public SecurityConstraint[] findSecurityConstraints(String requestPathMB, String httpMethod, Context context) {
        if (helper == null) {
            initConfigHelper(context.getServletContext());
        }

        WebSecurityManager webSecurityManager = getWebSecurityManager(false);
        if (webSecurityManager != null && webSecurityManager.hasNoConstrainedResources() && !isSecurityExtensionEnabled(context.getServletContext())) {
            return null;
        }

        return RealmAdapter.emptyConstraints;
    }

    /**
     * Enforce any user data constraint required by the security constraint guarding this request URI.
     *
     * @param request Request we are processing
     * @param response Response we are creating
     * @param constraints Security constraint being checked
     *
     * @exception IOException if an input/output error occurs
     *
     * @return <code>true</code> if this constraint was not violated and processing should continue, or <code>false</code> if we have
     * created a response already
     */
    @Override
    public boolean hasUserDataPermission(HttpRequest request, HttpResponse response, SecurityConstraint[] constraints) throws IOException {
        return hasUserDataPermission(request, response, constraints, null, null);
    }

    /**
     * Checks if the given request URI and method are the target of any user-data-constraint with a transport-guarantee of
     * CONFIDENTIAL, and whether any such constraint is already satisfied.
     *
     * If <tt>uri</tt> and <tt>method</tt> are null, then the URI and method of the given <tt>request</tt> are checked.
     *
     * If a user-data-constraint exists that is not satisfied, then the given <tt>request</tt> will be redirected to HTTPS.
     *
     * @param request the request that may be redirected
     * @param response the response that may be redirected
     * @param constraints the security constraints to check against
     * @param uri the request URI (minus the context path) to check
     * @param method the request method to check
     *
     * @return true if the request URI and method are not the target of any unsatisfied user-data-constraint with a
     * transport-guarantee of CONFIDENTIAL, and false if they are (in which case the given request will have been redirected to
     * HTTPS)
     */
    @Override
    public boolean hasUserDataPermission(HttpRequest request, HttpResponse response, SecurityConstraint[] constraints, String uri, String method) throws IOException {
        HttpServletRequest httpServletRequest = (HttpServletRequest) request;

        if (httpServletRequest.getServletPath() == null) {
            request.setServletPath(getResourceName(httpServletRequest.getRequestURI(), httpServletRequest.getContextPath()));
        }

        _logger.fine(() -> "[Web-Security][ hasUserDataPermission ]" +
            " Principal: " + httpServletRequest.getUserPrincipal() +
            " ContextPath: " + httpServletRequest.getContextPath());

        if (request.getRequest().isSecure()) {
            _logger.fine(() -> "[Web-Security] request.getRequest().isSecure(): " + request.getRequest().isSecure());
            return true;
        }

        WebSecurityManager webSecurityManager = getWebSecurityManager(true);
        if (webSecurityManager == null) {
            return false;
        }

        int isGranted = 0;
        try {
            isGranted = webSecurityManager.hasUserDataPermission(httpServletRequest, uri, method);
        } catch (IllegalArgumentException e) {
            // end the request after getting IllegalArgumentException while checking
            // user data permission
            _logger.log(WARNING, e, () -> rb.getString("realmAdapter.badRequestWithId"));
            ((HttpServletResponse) response.getResponse()).sendError(SC_BAD_REQUEST, rb.getString("realmAdapter.badRequest"));
            return false;
        }

        // Only redirect if we are sure the user will be granted.
        // See bug 4947698

        // This method will return:
        // 1 - if granted
        // 0 - if not granted
        // -1 - if the current transport is not granted, but a redirection can occur
        // so the grand will succeed.
        if (isGranted == -1) {
            _logger.fine(() -> "[Web-Security] redirecting using SSL");
            return redirect(request, response);
        }

        if (isGranted == 0) {
            ((HttpServletResponse) response.getResponse()).sendError(SC_FORBIDDEN, rb.getString("realmBase.forbidden"));
            return false;
        }

        return true;
    }

    /**
     * Checks whether or not authentication is needed. Returns an int, one of AUTHENTICATE_NOT_NEEDED, AUTHENTICATE_NEEDED, or
     * AUTHENTICATED_NOT_AUTHORIZED
     *
     * @param request Request we are processing
     * @param response Response we are creating
     * @param constraints Security constraint we are enforcing
     * @param disableProxyCaching whether or not to disable proxy caching for protected resources.
     * @param securePagesWithPragma true if we add headers which are incompatible with downloading office documents in IE under SSL
     * but which fix a caching problem in Mozilla.
     * @param ssoEnabled true if sso is enabled
     *
     * @exception IOException if an input/output error occurs
     */
    @Override
    public int preAuthenticateCheck(HttpRequest request, HttpResponse response, SecurityConstraint[] constraints,
        boolean disableProxyCaching, boolean securePagesWithPragma, boolean ssoEnabled) throws IOException {
        boolean isGranted = false;

        try {
            HttpServletRequest httpServletRequest = (HttpServletRequest) request.getRequest();
            if (httpServletRequest.getUserPrincipal() == null) {
                SecurityContext.setUnauthenticatedContext();
            }
            if (helper != null && helper.getServerAuthConfig() != null) {
                return AUTHENTICATE_NEEDED;
            }
            isGranted = invokeWebSecurityManager(request, response, constraints);
        } catch (IOException iex) {
            throw iex;
        } catch (Throwable ex) {
            _logger.log(SEVERE, ex, () -> "web_server.excep_authenticate_realmadapter");
            ((HttpServletResponse) response.getResponse()).sendError(SC_SERVICE_UNAVAILABLE);
            response.setDetailMessage(rb.getString("realmBase.forbidden"));

            return AUTHENTICATED_NOT_AUTHORIZED;
        }

        if (isGranted) {
            if (((HttpServletRequest) request).getUserPrincipal() != null) {
                disableProxyCaching(request, response, disableProxyCaching, securePagesWithPragma);
                if (ssoEnabled) {
                    HttpServletRequest hreq = (HttpServletRequest) request.getRequest();
                    WebSecurityManager webSecMgr = getWebSecurityManager(true);
                    if (!webSecMgr.permitAll(hreq)) {
                        // create a session for protected sso association
                        hreq.getSession(true);
                    }
                }
            }

            return AUTHENTICATE_NOT_NEEDED;
        }

        if (((HttpServletRequest) request).getUserPrincipal() != null) {
            ((HttpServletResponse) response.getResponse()).sendError(SC_FORBIDDEN);
            response.setDetailMessage(rb.getString("realmBase.forbidden"));
            return AUTHENTICATED_NOT_AUTHORIZED;
        } else {
            disableProxyCaching(request, response, disableProxyCaching, securePagesWithPragma);
            return AUTHENTICATE_NEEDED;
        }
    }

    /**
     * Authenticates the user making this request, based on the specified login configuration. Return <code>true</code> if any
     * specified requirements have been satisfied, or <code>false</code> if we have created a response challenge already.
     *
     * @param request Request we are processing
     * @param response Response we are creating
     * @param context The Context to which client of this class is attached.
     * @param authenticantion the current authenticator.
     * @exception IOException if an input/output error occurs
     */
    @Override
    public boolean invokeAuthenticateDelegate(HttpRequest request, HttpResponse response, Context context, Authenticator authenticator,
        boolean calledFromAuthenticate) throws IOException {

        LoginConfig config = context.getLoginConfig();
        ServerAuthConfig serverAuthConfig = null;
        try {
            if (helper != null) {
                serverAuthConfig = helper.getServerAuthConfig();
            }
        } catch (Exception ex) {
            throw new IOException(ex);
        }

        if (serverAuthConfig != null) {
            // Jakarta Authentication is enabled for this application
            try {
                context.fireContainerEvent(BEFORE_AUTHENTICATION, null);
                return validate(request, response, config, authenticator, calledFromAuthenticate);
            } finally {
                context.fireContainerEvent(AFTER_AUTHENTICATION, null);
            }
        }

        // Jakarta Authentication is not enabled. Use the current authenticator.
        return ((AuthenticatorBase) authenticator).authenticate(request, response, config);
    }

    /**
     * Return a short name for this Realm Adapter implementation.
     */
    @Override
    protected String getName() {
        return name;
    }

    /**
     * Return the name of the realm this RealmAdapter uses.
     *
     * @return realm name
     *
     */
    @Override
    public String getRealmName() {
        return _realmName;
    }

    /**
     * Sets the virtual server on which the web module (with which this RealmAdapter is associated with) has been deployed.
     *
     * @param container The virtual server
     */
    @Override
    public void setVirtualServer(Object container) {
        this.virtualServer = (Container) container;
    }

    @Override
    public void updateWebSecurityManager() {
        if (webSecurityManager == null) {
            webSecurityManager = getWebSecurityManager(true);
        }

        if (webSecurityManager != null) {
            try {
                webSecurityManager.release();
                webSecurityManager.destroy();
            } catch (Exception ex) {
                ex.printStackTrace();
            }

            webSecurityManager = webSecurityManagerFactory.createManager(webBundleDescriptor, true, serverContext);
            _logger.fine(() -> "WebSecurityManager for " + contextId + " has been updated");
        }
    }

    /**
     * Authenticates and sets the SecurityContext in the TLS.
     *
     * @return the authenticated principal.
     * @param the user name.
     * @param the password.
     */
    @Override
    public Principal authenticate(String username, char[] password) {
        _logger.fine(() -> "Tomcat callback for authenticate user/password");
        _logger.fine(() -> "usename = " + username);

        if (authenticate(username, password, null)) {
            return new WebPrincipal(username, password, SecurityContext.getCurrent());

        }

        return null;
    }

    @Override
    public Principal authenticate(HttpServletRequest httpServletRequest) {
        try {
            DigestParameterGenerator generator = DigestParameterGenerator.getInstance(DigestParameterGenerator.HTTP_DIGEST);
            DigestAlgorithmParameter[] params = generator.generateParameters(new HttpAlgorithmParameterImpl(httpServletRequest));
            Key key = null;

            if (cnonces == null) {
                String appName = webBundleDescriptor.getApplication().getAppName();
                synchronized (this) {
                    if (this.haCNonceCacheMap == null) {
                        this.haCNonceCacheMap = appCNonceCacheMapProvider.get();
                    }
                    if (this.haCNonceCacheMap != null) {
                        // get the initialized HA CNonceCache
                        cnonces = haCNonceCacheMap.get(appName);
                    }

                    if (cnonces == null) {
                        if (this.cNonceCacheFactory == null) {
                            this.cNonceCacheFactory = cNonceCacheFactoryProvider.get();
                        }
                        // create a Non-HA CNonce Cache
                        cnonces = cNonceCacheFactory.createCNonceCache(webBundleDescriptor.getApplication().getAppName(), null, null, null);
                    }
                }

            }

            String nc = null;
            String cnonce = null;
            for (DigestAlgorithmParameter p : params) {
                if (p instanceof NestedDigestAlgoParamImpl) {
                    NestedDigestAlgoParamImpl np = (NestedDigestAlgoParamImpl) p;
                    DigestAlgorithmParameter[] nps = (DigestAlgorithmParameter[]) np.getNestedParams();
                    for (DigestAlgorithmParameter p1 : nps) {
                        if ("cnonce".equals(p1.getName())) {
                            cnonce = new String(p1.getValue());
                        } else if ("nc".equals(p1.getName())) {
                            nc = new String(p1.getValue());
                        }
                        if (cnonce != null && nc != null) {
                            break;
                        }
                    }
                    if (cnonce != null && nc != null) {
                        break;
                    }
                }
                if ("cnonce".equals(p.getName())) {
                    cnonce = new String(p.getValue());
                } else if ("nc".equals(p.getName())) {
                    nc = new String(p.getValue());
                }
            }

            long count;
            long currentTime = System.currentTimeMillis();
            try {
                count = Long.parseLong(nc, 16);
            } catch (NumberFormatException nfe) {
                throw new RuntimeException(nfe);
            }
            NonceInfo info;
            synchronized (cnonces) {
                info = cnonces.get(cnonce);
            }
            if (info == null) {
                info = new NonceInfo();
            } else if (count <= info.getCount()) {
                throw new RuntimeException("Invalid Request : Possible Replay Attack detected ?");
            }
            info.setCount(count);
            info.setTimestamp(currentTime);
            synchronized (cnonces) {
                cnonces.put(cnonce, info);
            }

            for (DigestAlgorithmParameter dap : params) {
                if (A1.equals(dap.getName()) && (dap instanceof Key)) {
                    key = (Key) dap;
                    break;
                }
            }

            if (key != null) {
                DigestCredentials creds = new DigestCredentials(_realmName, key.getUsername(), params);
                LoginContextDriver.login(creds);
                SecurityContext secCtx = SecurityContext.getCurrent();
                return new WebPrincipal(creds.getUserName(), (char[]) null, secCtx);
            }
            throw new RuntimeException("No key found in parameters");

        } catch (Exception le) {
            if (_logger.isLoggable(WARNING)) {
                _logger.log(WARNING, "web.login.failed", le.toString());
            }
        }

        return null;
    }

    @Override
    public Principal authenticate(X509Certificate certificates[]) {
        if (authenticate(null, null, certificates)) {
            return new WebPrincipal(certificates, SecurityContext.getCurrent());
        }

        return null;
    }

    /**
     * Perform access control based on the specified authorization constraint. Return <code>true</code> if this constraint is
     * satisfied and processing should continue, or <code>false</code> otherwise.
     *
     * @param request Request we are processing
     * @param response Response we are creating
     * @param constraint Security constraint we are enforcing
     * @param The Context to which client of this class is attached.
     *
     * @exception IOException if an input/output error occurs
     */
    @Override
    public boolean hasResourcePermission(HttpRequest request, HttpResponse response, SecurityConstraint[] constraints, Context context)
        throws IOException {
        boolean isGranted = false;

        try {
            isGranted = invokeWebSecurityManager(request, response, constraints);
        } catch (IOException iex) {
            throw iex;
        } catch (Throwable ex) {
            _logger.log(SEVERE, ex, () -> "web_server.excep_authenticate_realmadapter");
            ((HttpServletResponse) response.getResponse()).sendError(SC_SERVICE_UNAVAILABLE);
            response.setDetailMessage(rb.getString("realmBase.forbidden"));

            return isGranted;
        }

        if (isGranted) {
            return isGranted;
        }

        ((HttpServletResponse) response.getResponse()).sendError(SC_FORBIDDEN);
        response.setDetailMessage(rb.getString("realmBase.forbidden"));

        // invoking secureResponse
        invokePostAuthenticateDelegate(request, response, context);

        return isGranted;
    }

    /**
     * Post authentication for given request and response.
     *
     * @param request Request we are processing
     * @param response Response we are creating
     * @param context The Context to which client of this class is attached.
     * @exception IOException if an input/output error occurs
     */
    @Override
    public boolean invokePostAuthenticateDelegate(HttpRequest request, HttpResponse response, Context context) throws IOException {
        boolean result = false;
        ServerAuthContext serverAuthContext = null;
        try {
            if (helper != null) {
                HttpServletRequest httpServletRequest = (HttpServletRequest) request.getRequest();
                MessageInfo messageInfo = (MessageInfo) httpServletRequest.getAttribute(MESSAGE_INFO);
                if (messageInfo != null) {
                    // Jakarta Authentication is enabled for this application
                    serverAuthContext = (ServerAuthContext) messageInfo.getMap().get(SERVER_AUTH_CONTEXT);
                    if (serverAuthContext != null) {
                        try {
                            context.fireContainerEvent(BEFORE_POST_AUTHENTICATION, null);
                            AuthStatus authStatus = serverAuthContext.secureResponse(messageInfo, null); // null serviceSubject
                            result = AuthStatus.SUCCESS.equals(authStatus);
                        } finally {
                            context.fireContainerEvent(AFTER_POST_AUTHENTICATION, null);
                        }
                    }
                }
            }
        } catch (AuthException ex) {
            throw new IOException(ex);
        } finally {
            if (helper != null && serverAuthContext != null) {
                if (request instanceof HttpRequestWrapper) {
                    request.removeNote(WRAPPED_REQUEST);
                }
                if (response instanceof HttpResponseWrapper) {
                    request.removeNote(WRAPPED_RESPONSE);
                }
            }
        }

        return result;
    }

    /**
     * Check if the given principal has the provided role. Returns true if the principal has the specified role, false otherwise.
     *
     * @return true if the principal has the specified role.
     * @param request Request we are processing
     * @param response Response we are creating
     * @param the principal
     * @param the role
     */
    @Override
    public boolean hasRole(HttpRequest request, HttpResponse response, Principal principal, String role) {
        WebSecurityManager webSecurityManager = getWebSecurityManager(true);
        if (webSecurityManager == null) {
            return false;
        }

        // add HttpResponse and HttpResponse to the parameters, and remove
        // instance variable currentRequest from this class. References to
        // this.currentRequest are also removed from other methods.
        // String servletName = getResourceName( currentRequest.getRequestURI(),
        // currentRequest.getContextPath());
        String servletName = getCanonicalName(request);

        boolean isGranted = webSecurityManager.hasRoleRefPermission(servletName, role, principal);

        _logger.fine(() ->
            "Checking if servlet " + servletName + " with principal " + principal + " has role " + role + " isGranted: " + isGranted);

        return isGranted;
    }

    /**
     * Create the realm adapter. Extracts the role to user/group mapping from the runtime deployment descriptor.
     *
     * @param the web bundle deployment descriptor.
     * @param isSystemApp if the app is a system app.
     *
     * public RealmAdapter(WebBundleDescriptor descriptor, boolean isSystemApp) { this(descriptor, isSystemApp, null); }
     */
    @Override
    public void destroy() {
        super.destroy();
        if (helper != null) {
            helper.disable();
        }
    }


    public WebBundleDescriptor getWebDescriptor() {
        return webBundleDescriptor;
    }

    // utility method to get web security anager.
    // will log warning if the manager is not found in the factory, and
    // logNull is true.
    public WebSecurityManager getWebSecurityManager(boolean logNull) {
        if (webSecurityManager == null) {
            synchronized (this) {
                webSecurityManager = webSecurityManagerFactory.getManager(contextId);
            }
            if (webSecurityManager == null && logNull) {
                _logger.log(WARNING, "realmAdapter.noWebSecMgr", contextId);
            }
        }

        return webSecurityManager;
    }

    public boolean hasRole(String servletName, Principal principal, String role) {
        WebSecurityManager secMgr = getWebSecurityManager(true);
        if (secMgr == null) {
            return false;
        }
        return secMgr.hasRoleRefPermission(servletName, role, principal);
    }

    @Override
    public void logout(final HttpRequest req) {
        boolean securityExtensionEnabled = isSecurityExtensionEnabled(req.getRequest().getServletContext());
        byte[] alreadyCalled = (byte[]) reentrancyStatus.get();
        if (securityExtensionEnabled && helper != null && alreadyCalled[0] == 0) {
            alreadyCalled[0] = 1;
            MessageInfo messageInfo = (MessageInfo) req.getRequest().getAttribute(MESSAGE_INFO);
            if (messageInfo == null) {
                messageInfo = new HttpMessageInfo((HttpServletRequest) req.getRequest(),
                    (HttpServletResponse) req.getResponse().getResponse());
            }
            messageInfo.getMap().put(HttpServletConstants.IS_MANDATORY, Boolean.TRUE.toString());
            try {
                ServerAuthContext sAC = helper.getServerAuthContext(messageInfo, null);
                if (sAC != null) {
                    /*
                     * Check for the default/server-generated/unauthenticated security context.
                     */
                    final SecurityContext securityContext = SecurityContext.getCurrent();
                    Subject subject = securityContext.didServerGenerateCredentials() ? new Subject() : securityContext.getSubject();

                    if (subject == null) {
                        subject = new Subject();
                    }
                    if (subject.isReadOnly()) {
                        _logger.log(WARNING, "Read-only subject found during logout processing");
                    }
                    try {
                        req.getContext().fireContainerEvent(ContainerEvent.BEFORE_LOGOUT, null);
                        sAC.cleanSubject(messageInfo, subject);
                    } finally {
                        req.getContext().fireContainerEvent(ContainerEvent.AFTER_LOGOUT, null);
                    }
                }
            } catch (AuthException ex) {
                throw new RuntimeException(ex);
            } finally {
                doLogout(req, true);
                alreadyCalled[0] = 0;
            }
        } else {
            doLogout(req, alreadyCalled[0] == 1);
        }
    }

    private void doLogout(HttpRequest request, boolean extensionEnabled) {
        Context context = request.getContext();
        Authenticator authenticator = null;
        if (context != null) {
            authenticator = context.getAuthenticator();
        }
        if (authenticator == null) {
            throw new RuntimeException("Context or Authenticator is null");
        }
        try {
            if (extensionEnabled) {
                AuthenticatorProxy proxy = new AuthenticatorProxy(authenticator, null, null);
                proxy.logout(request);
            } else {
                authenticator.logout(request);
            }
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }

        logout();
    }

    @Override
    public void logout() {
        setSecurityContext(null);
        AccessController.doPrivileged(new PrivilegedAction<Void>() {
            @Override
            public Void run() {
                resetPolicyContext();
                return null;
            }
        });
    }

    /*
     * IASRI 4688449 This method was only used by J2EEInstanceListener to set the security context prior to invocations by
     * re-authenticating a previously set WebPrincipal. This is now cached so no need.
     */
    public boolean authenticate(WebPrincipal prin) {
        if (prin.isUsingCertificate()) {
            return authenticate(null, null, prin.getCertificates());
        }
        return authenticate(prin.getName(), prin.getPassword(), null);
    }

    /**
     * Authenticates and sets the SecurityContext in the TLS.
     *
     * @return true if authentication succeeded, false otherwise.
     * @param the username.
     * @param the authentication method.
     * @param the authentication data.
     */
    protected boolean authenticate(String username, char[] password, X509Certificate[] certs) {

        String realm_name = null;
        boolean success = false;
        try {
            if (certs != null) {
                Subject subject = new Subject();
                X509Certificate certificate = certs[0];
                X500Principal x500Principal = certificate.getSubjectX500Principal();
                subject.getPublicCredentials().add(x500Principal);
                // Put the certificate chain as an List in the subject, to be accessed by user's LoginModule.
                final List<X509Certificate> certificateCred = Arrays.asList(certs);
                subject.getPublicCredentials().add(certificateCred);
                LoginContextDriver.doX500Login(subject, moduleID);
                realm_name = CertificateRealm.AUTH_TYPE;
            } else {
                realm_name = _realmName;

                LoginContextDriver.login(username, password, realm_name);
            }
            success = true;
        } catch (Exception le) {
            success = false;
            if (_logger.isLoggable(WARNING)) {
                _logger.log(WARNING, "web.login.failed", le.toString());
                if (_logger.isLoggable(FINE)) {
                    _logger.log(FINE, "Exception", le);
                }
            }
        }
        if (success) {
            if (_logger.isLoggable(FINE)) {
                _logger.log(FINE, "Web login succeeded for: " + username);
            }
        }

        return success;
    }

    // BEGIN IASRI 4747594
    /**
     * Set the run-as principal into the SecurityContext when needed.
     *
     * <P>
     * This method will attempt to obtain the name of the servlet from the ComponentInvocation. Note that there may not be one since
     * this gets called also during internal processing (not clear..) not just part of servlet requests. However, if it is not a
     * servlet request there is no need (or possibility) to have a run-as setting so no further action is taken.
     *
     * <P>
     * If the servlet name is present the runAsPrincipals cache is checked to find the run-as principal to use (if any). If one is
     * set, the SecurityContext is switched to this principal.
     *
     * @param inv The invocation object to process.
     *
     */
    public void preSetRunAsIdentity(ComponentInvocation inv) {

        // Optimization to avoid the expensivce call to getServletName
        // for cases with no run-as descriptors

        if (runAsPrincipals != null && runAsPrincipals.isEmpty()) {
            return;
        }

        String servletName = this.getServletName(inv);
        if (servletName == null) {
            return;
        }

        String runAs = runAsPrincipals.get(servletName);

        if (runAs != null) {
            // The existing SecurityContext is saved - however, this seems
            // meaningless - see bug 4757733. For now, keep it unchanged
            // in case there are some dependencies elsewhere in RI.
            SecurityContext old = getSecurityContext();
            inv.setOldSecurityContext(old);

            // Set the run-as principal into SecurityContext
            loginForRunAs(runAs);

            if (_logger.isLoggable(FINE)) {
                _logger.fine("run-as principal for " + servletName + " set to: " + runAs);
            }
        }
    }

    /**
     * Obtain servlet name from invocation.
     *
     * <P>
     * In order to obtain the servlet name one of the following must be true: 1. The instanceName of the ComponentInvocation is not
     * null 2. The ComponentInvocation contains a 'class' of type HttpServlet, which contains a valid ServletConfig object. This
     * method returns the value returned by getServletName() on the ServletConfig.
     *
     * <P>
     * If the above is not met, null is returned.
     *
     * @param inv The invocation object to process.
     * @return Servlet name or null.
     *
     */
    private String getServletName(ComponentInvocation inv) {

        String servletName = inv.getInstanceName();
        if (servletName != null) {
            return servletName;
        }

        Object invInstance = inv.getInstance();

        if (invInstance instanceof HttpServlet) {

            HttpServlet thisServlet = (HttpServlet) invInstance;
            ServletConfig svc = thisServlet.getServletConfig();

            if (svc != null) {
                return thisServlet.getServletName();
            }
        }
        return null;
    }

    /**
     * Attempts to restore old SecurityContext (but fails).
     *
     * <P>
     * In theory this method seems to attempt to check if a run-as principal was set by preSetRunAsIdentity() (based on the indirect
     * assumption that if the servlet in the given invocation has a run-as this must've been the case). If so, it retrieves the
     * oldSecurityContext from the invocation object and set it in the SecurityContext.
     *
     * <P>
     * The problem is that the invocation object is not the same object as was passed in to preSetRunAsIdentity() so it will never
     * contain the right info - see bug 4757733.
     *
     * <P>
     * In practice it means this method only ever sets the SecurityContext to null (if run-as matched) or does nothing. In particular
     * note the implication that it <i>will</i> be set to null after a run-as invocation completes. This behavior will be retained
     * for the time being for consistency with RI. It must be fixed later.
     *
     * @param inv The invocation object to process.
     *
     */
    public void postSetRunAsIdentity(ComponentInvocation inv) {

        // Optimization to avoid the expensivce call to getServletName
        // for cases with no run-as descriptors

        if (runAsPrincipals != null && runAsPrincipals.isEmpty()) {
            return;
        }

        String servletName = this.getServletName(inv);
        if (servletName == null) {
            return;
        }

        String runAs = runAsPrincipals.get(servletName);
        if (runAs != null) {
            setSecurityContext((SecurityContext) inv.getOldSecurityContext()); // always null

        }
    }

    // END IASRI 4747594
    private void loginForRunAs(String principal) {
        LoginContextDriver.loginPrincipal(principal, _realmName);
    }

    private SecurityContext getSecurityContext() {
        return SecurityContext.getCurrent();
    }

    private void setSecurityContext(SecurityContext sc) {
        SecurityContext.setCurrent(sc);
    }

    /**
     * Used to detect when the principals in the subject correspond to the default or "ANONYMOUS" principal, and therefore a null
     * principal should be set in the HttpServletRequest.
     *
     * @param principalSet
     * @return true whe a null principal is to be set.
     */
    private boolean principalSetContainsOnlyAnonymousPrincipal(Set<Principal> principalSet) {
        boolean rvalue = false;
        Principal defaultPrincipal = SecurityContext.getDefaultCallerPrincipal();
        if (defaultPrincipal != null && principalSet != null) {
            rvalue = principalSet.contains(defaultPrincipal);
        }
        if (rvalue) {
            Iterator<Principal> it = principalSet.iterator();
            while (it.hasNext()) {
                if (!it.next().equals(defaultPrincipal)) {
                    return false;
                }
            }
        }
        return rvalue;
    }

    @Override
    protected char[] getPassword(String username) {
        throw new IllegalStateException("Should not reach here");
    }

    @Override
    protected Principal getPrincipal(String username) {
        throw new IllegalStateException("Should not reach here");
    }

    // START OF IASRI 4809144
    /**
     * This method is added to create a Principal based on the username only. Hercules stores the username as part of authentication
     * failover and needs to create a Principal based on username only <sridhar.satuloori@sun.com>
     *
     * @param username
     * @return Principal for the user username HERCULES:add
     */
    public Principal createFailOveredPrincipal(String username) {
        _logger.log(Level.FINEST, "IN createFailOveredPrincipal (" + username + ")");
        // set the appropriate security context
        loginForRunAs(username);
        SecurityContext secCtx = SecurityContext.getCurrent();
        _logger.log(FINE, "Security context is " + secCtx);
        assert (secCtx != null);
        Principal principal = new WebPrincipal(username, (char[]) null, secCtx);
        _logger.log(Level.INFO, "Principal created for FailOvered user " + principal);
        return principal;
    }


    /**
     * Invokes WebSecurityManager to perform access control check. Return <code>true</code> if permission is granted, or
     * <code>false</code> otherwise.
     *
     * @param request Request we are processing
     * @param response Response we are creating
     * @param constraints Security constraint we are enforcing
     *
     * @exception IOException if an input/output error occurs
     */
    private boolean invokeWebSecurityManager(HttpRequest request, HttpResponse response, SecurityConstraint[] constraints)
        throws IOException {

        // allow access to form login related pages and targets
        // and the "j_security_check" action
        boolean evaluated = false;
        try {
            rwLock.readLock().lock();
            evaluated = contextEvaluated;
        } finally {
            rwLock.readLock().unlock();
        }

        if (!evaluated) {
            try {
                rwLock.writeLock().lock();
                if (!contextEvaluated) {
                    // get Context here as preAuthenticateCheck does not have it
                    // and our Container is always a Context
                    Context context = (Context) getContainer();
                    LoginConfig config = context.getLoginConfig();
                    if ((config != null) && (Constants.FORM_METHOD.equals(config.getAuthMethod()))) {
                        loginPage = config.getLoginPage();
                        errorPage = config.getErrorPage();
                    }
                    contextEvaluated = true;
                }
            } finally {
                rwLock.writeLock().unlock();
            }
        }

        if (loginPage != null || errorPage != null) {
            String requestURI = request.getRequestPathMB().toString();
            if (_logger.isLoggable(FINE)) {
                _logger.fine("[Web-Security]  requestURI: " + requestURI + " loginPage: " + loginPage);
            }
            if (loginPage != null && loginPage.equals(requestURI)) {
                if (_logger.isLoggable(FINE)) {
                    _logger.fine(" Allow access to login page " + loginPage);
                }
                return true;
            }
            if (errorPage != null && errorPage.equals(requestURI)) {
                if (_logger.isLoggable(FINE)) {
                    _logger.fine(" Allow access to error page " + errorPage);
                }
                return true;
            } else if (requestURI.endsWith(Constants.FORM_ACTION)) {
                if (_logger.isLoggable(FINE)) {
                    _logger.fine(" Allow access to username/password submission");
                }
                return true;
            }
        }

        HttpServletRequest hrequest = (HttpServletRequest) request;
        if (hrequest.getServletPath() == null) {
            request.setServletPath(getResourceName(hrequest.getRequestURI(), hrequest.getContextPath()));
        }

        if (_logger.isLoggable(FINE)) {
            _logger.fine("[Web-Security] [ hasResourcePermission ] Principal: " + hrequest.getUserPrincipal() + " ContextPath: "
                + hrequest.getContextPath());
        }
        WebSecurityManager secMgr = getWebSecurityManager(true);

        if (secMgr == null) {
            return false;
        }
        return secMgr.hasResourcePermission(hrequest);
    }

    private List<String> getHostAndPort(HttpRequest request) throws IOException {
        boolean isWebServerRequest = false;
        Enumeration headerNames = ((HttpServletRequest) request.getRequest()).getHeaderNames();

        String[] hostPort = null;
        boolean isHeaderPresent = false;
        while (headerNames.hasMoreElements()) {
            String headerName = (String) headerNames.nextElement();
            String hostVal;
            if (headerName.equalsIgnoreCase("Host")) {
                hostVal = ((HttpServletRequest) request.getRequest()).getHeader(headerName);
                isHeaderPresent = true;
                hostPort = hostVal.split(":");
            }
        }
        if (hostPort == null) {
            throw new ProtocolException(rb.getString("missing_http_header.host"));
        }

        // If the port in the Header is empty (it refers to the default port), which is
        // not one of the GlassFish listener ports -> GF is front-ended by a proxy (LB plugin)
        boolean isHostPortNullOrEmpty = ((hostPort.length <= 1) || (hostPort[1] == null || hostPort[1].trim().isEmpty()));
        if (!isHeaderPresent) {
            isWebServerRequest = false;
        } else if (isHostPortNullOrEmpty) {
            isWebServerRequest = true;
        } else {
            boolean breakFromLoop = false;

            for (NetworkListener nwListener : nwListeners.getNetworkListener()) {
                // Loop through the network listeners
                String nwAddress = nwListener.getAddress();
                InetAddress[] localHostAdresses;
                if (nwAddress == null || nwAddress.equals("0.0.0.0")) {
                    nwAddress = NetUtils.getCanonicalHostName();
                    if (!nwAddress.equals(hostPort[0])) {
                        // compare the InetAddress objects
                        // only if the hostname in the header
                        // does not match with the hostname in the
                        // listener-To avoid performance overhead
                        localHostAdresses = NetUtils.getHostAddresses();

                        InetAddress hostAddress = InetAddress.getByName(hostPort[0]);
                        for (InetAddress inetAdress : localHostAdresses) {
                            if (inetAdress.equals(hostAddress)) {
                                // Hostname of the request in the listener and the hostname in the Host header match.
                                // Check the port
                                String nwPort = nwListener.getPort();
                                // If the listener port is different from the port
                                // in the Host header, then request is received by WS frontend
                                if (nwPort.equals(hostPort[1])) {
                                    isWebServerRequest = false;
                                    breakFromLoop = true;
                                    break;
                                }
                                isWebServerRequest = true;
                            }
                        }
                    } else {
                        // Host names are the same, compare the ports
                        String nwPort = nwListener.getPort();
                        // If the listener port is different from the port
                        // in the Host header, then request is received by WS frontend
                        if (!nwPort.equals(hostPort[1])) {
                            isWebServerRequest = true;

                        } else {
                            isWebServerRequest = false;
                            breakFromLoop = true;

                        }

                    }
                }
                if (breakFromLoop && !isWebServerRequest) {
                    break;
                }
            }
        }
        String serverHost = request.getRequest().getServerName();
        int redirectPort = request.getConnector().getRedirectPort();

        // If the request is a from a webserver frontend, redirect to the url
        // with the webserver frontend host and port
        if (isWebServerRequest) {
            serverHost = hostPort[0];
            if (isHostPortNullOrEmpty) {
                // Use the default port
                redirectPort = -1;
            } else {
                redirectPort = Integer.parseInt(hostPort[1]);
            }
        }
        List<String> hostAndPort = new ArrayList<>();
        hostAndPort.add(serverHost);
        hostAndPort.add(String.valueOf(redirectPort));
        return hostAndPort;

    }

    private boolean redirect(HttpRequest request, HttpResponse response) throws IOException {
        // Initialize variables we need to determine the appropriate action
        HttpServletRequest hrequest = (HttpServletRequest) request.getRequest();
        HttpServletResponse hresponse = (HttpServletResponse) response.getResponse();

        int redirectPort = request.getConnector().getRedirectPort();

        // Is redirecting disabled?
        if (redirectPort <= 0) {
            if (_logger.isLoggable(Level.INFO)) {
                _logger.fine("[Web-Security]  SSL redirect is disabled");
            }

            hresponse.sendError(SC_FORBIDDEN, URLEncoder.encode(hrequest.getRequestURI(), "UTF-8"));
            return (false);
        }

        String protocol = "https";

        StringBuffer file = new StringBuffer(hrequest.getRequestURI());
        String requestedSessionId = hrequest.getRequestedSessionId();
        if ((requestedSessionId != null) && hrequest.isRequestedSessionIdFromURL()) {
            file.append(";" + Globals.SESSION_PARAMETER_NAME + "=");
            file.append(requestedSessionId);
        }
        String queryString = hrequest.getQueryString();
        if (queryString != null) {
            file.append('?');
            file.append(queryString);
        }
        URL url = null;
        List<String> hostAndPort = getHostAndPort(request);
        String serverHost = hostAndPort.get(0);
        redirectPort = Integer.parseInt((hostAndPort.get(1)));
        try {
            url = new URL(protocol, serverHost, redirectPort, file.toString());
            hresponse.sendRedirect(url.toString());
            return (false);
        } catch (MalformedURLException e) {
            hresponse.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, URLEncoder.encode(hrequest.getRequestURI(), "UTF-8"));
            return (false);
        }
    }

    // START SJSAS 6232464
    // pass in HttpServletResponse instead of saving it as instance variable
    // private String getCanonicalName(){
    private String getCanonicalName(HttpRequest currentRequest) {
        return currentRequest.getWrapper().getServletName();
    }

    private String getResourceName(String uri, String contextPath) {
        if (contextPath.length() < uri.length()) {
            return uri.substring(contextPath.length());
        }
        return "";
    }

    public void setRealmName(String realmName) {
        // do nothing since this is done when initializing the Realm.
    }

    /**
     * This must be invoked after virtualServer is set.
     */
    private HttpServletHelper getConfigHelper(final ServletContext servletContext) {
        Map map = new HashMap();
        map.put(HttpServletConstants.WEB_BUNDLE, webBundleDescriptor);
        return new HttpServletHelper(getAppContextID(servletContext), map, null, // null handler
            _realmName, isSystemApp, defaultSystemProviderID);
    }

    /**
     * This must be invoked after virtualServer is set.
     */
    private String getAppContextID(final ServletContext servletContext) {
        if (!servletContext.getVirtualServerName().equals(this.virtualServer.getName())) {
            _logger.log(WARNING, "Virtual server name from ServletContext: {0} differs from name from virtual.getName(): {1}",
                new Object[] { servletContext.getVirtualServerName(), virtualServer.getName() });
        }
        if (!servletContext.getContextPath().equals(webBundleDescriptor.getContextRoot())) {
            _logger.log(WARNING, "Context path from ServletContext: {0} differs from path from bundle: {1}",
                new Object[] { servletContext.getContextPath(), webBundleDescriptor.getContextRoot() });
        }
        return servletContext.getVirtualServerName() + " " + servletContext.getContextPath();
    }

    private boolean validate(HttpRequest request, HttpResponse response, LoginConfig config, Authenticator authenticator,
        boolean calledFromAuthenticate) throws IOException {

        HttpServletRequest req = (HttpServletRequest) request.getRequest();
        HttpServletResponse res = (HttpServletResponse) response.getResponse();

        Subject subject = new Subject();

        MessageInfo messageInfo = new HttpMessageInfo(req, res);

        boolean rvalue = false;
        boolean isMandatory = true;
        try {
            WebSecurityManager webSecMgr = getWebSecurityManager(true);
            isMandatory = !webSecMgr.permitAll(req);
            // Issue - 9578 - produce user challenge if call originates from HttpRequest.authenticate
            if (isMandatory || calledFromAuthenticate) {
                messageInfo.getMap().put(HttpServletConstants.IS_MANDATORY, Boolean.TRUE.toString());
            }
            ServerAuthContext sAC = helper.getServerAuthContext(messageInfo, null); // null serviceSubject
            if (sAC == null) {
                throw new AuthException("null ServerAuthContext");
            }
            AuthStatus authStatus = sAC.validateRequest(messageInfo, subject, null); // null serviceSubject
            rvalue = AuthStatus.SUCCESS.equals(authStatus);

            if (rvalue) { // cache it only if validateRequest = true
                messageInfo.getMap().put(SERVER_AUTH_CONTEXT, sAC);
                req.setAttribute(MESSAGE_INFO, messageInfo);
            }
        } catch (AuthException ae) {
            if (_logger.isLoggable(FINE)) {
                _logger.log(FINE, "JMAC: http msg authentication fail", ae);
            }
            res.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        } catch (RuntimeException e) {
            if (_logger.isLoggable(FINE)) {
                _logger.log(FINE, "JMAC: Exception during validateRequest", e);
            }
            res.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
        if (rvalue) {
            Set<Principal> principalSet = subject.getPrincipals();
            // must be at least one new principal to establish
            // non-default security context
            if (principalSet != null && !principalSet.isEmpty() && !principalSetContainsOnlyAnonymousPrincipal(principalSet)) {

                SecurityContext ctx = new SecurityContext(subject);
                SecurityContext.setCurrent(ctx);
                // XXX assuming no null principal here
                Principal p = ctx.getCallerPrincipal();
                WebPrincipal wp = new WebPrincipal(p, ctx);
                try {
                    // XXX Keep it for reference
                    /*
                     * if (this.sAC.managesSessions(sharedState)) { // registration (via proxy) does not occur // if context manages
                     * sessions // record authentication information in the request request.setAuthType(PROXY_AUTH_TYPE);
                     * request.setUserPrincipal(wp); } else { AuthenticatorProxy proxy = new AuthenticatorProxy(authenticator,wp);
                     * proxy.authenticate(request,response,config); }
                     */
                    String authType = (String) messageInfo.getMap().get(HttpServletConstants.AUTH_TYPE);

                    if (authType == null && config != null && config.getAuthMethod() != null) {
                        authType = config.getAuthMethod();
                    }

                    if (shouldRegister(messageInfo.getMap())) {
                        AuthenticatorProxy proxy = new AuthenticatorProxy(authenticator, wp, authType);
                        proxy.authenticate(request, response, config);
                    } else {
                        request.setAuthType((authType == null) ? PROXY_AUTH_TYPE : authType);
                        request.setUserPrincipal(wp);
                    }
                } catch (LifecycleException le) {
                    _logger.log(SEVERE, "[Web-Security] unable to register session", le);

                }
            } else {
                // GLASSFISH-20930.Set null for the case when SAM does not
                // indicate that it needs the session
                if (((HttpServletRequest) messageInfo.getRequestMessage()).getUserPrincipal() != null) {
                    request.setUserPrincipal(null);
                    request.setAuthType(null);
                }

                if (isMandatory) {
                    rvalue = false;
                }
            }
            if (rvalue) {
                HttpServletRequest newRequest = (HttpServletRequest) messageInfo.getRequestMessage();
                if (newRequest != req) {
                    request.setNote(WRAPPED_REQUEST, new HttpRequestWrapper(request, newRequest));
                }

                HttpServletResponse newResponse = (HttpServletResponse) messageInfo.getResponseMessage();
                if (newResponse != res) {
                    request.setNote(WRAPPED_RESPONSE, new HttpResponseWrapper(response, newResponse));
                }
            }

        }
        return rvalue;
    }

    private boolean shouldRegister(Map map) {
        /*
         * Detect both the proprietary property and the standard one.
         */
        return map.containsKey(HttpServletConstants.REGISTER_WITH_AUTHENTICATOR)
            || mapEntryToBoolean(HttpServletConstants.REGISTER_SESSION, map);
    }

    private boolean mapEntryToBoolean(final String propName, final Map map) {
        if (map.containsKey(propName)) {
            Object value = map.get(propName);
            if (value != null && value instanceof String) {
                return Boolean.valueOf((String) value);
            }
        }
        return false;
    }

    /**
     * get the default provider id for system apps if one has been established. the default provider for system apps is established
     * by defining a system property.
     *
     * @return the provider id or null.
     */
    private static String getDefaultSystemProviderID() {
        String p = System.getProperty(SYSTEM_HTTPSERVLET_SECURITY_PROVIDER);
        if (p != null) {
            p = p.trim();
            if (p.length() == 0) {
                p = null;
            }
        }
        return p;
    }

    private static String PROXY_AUTH_TYPE = "PLUGGABLE_PROVIDER";

    private void resetPolicyContext() {
        PolicyContextHandlerImpl.getInstance().reset();
        PolicyContext.setContextID(null);
    }

    // inner class extends AuthenticatorBase such that session registration
    // of webtier can be invoked by RealmAdapter after authentication
    // by authentication module.
    static class AuthenticatorProxy extends AuthenticatorBase {

        private AuthenticatorBase authBase;
        private Principal principal;
        private String authType;

        @Override
        public boolean getCache() {
            return authBase.getCache();
        }

        @Override
        public Container getContainer() {
            return authBase.getContainer();
        }

        AuthenticatorProxy(Authenticator authenticator, Principal p, String authType) throws LifecycleException {

            this.authBase = (AuthenticatorBase) authenticator;
            this.principal = p;
            this.authType = authType == null ? RealmAdapter.PROXY_AUTH_TYPE : authType;

            setCache(authBase.getCache());
            setContainer(authBase.getContainer());
            start(); // finds sso valve and sets its value in proxy
        }

        @Override
        public boolean authenticate(HttpRequest request, HttpResponse response, LoginConfig config) throws IOException {
            if (cache) {
                getSession(request, true);
            }

            register(request, response, this.principal, this.authType, this.principal.getName(), null);
            return true;
        }

        @Override
        public String getAuthMethod() {
            return authType;
        }
    }

    private static class HttpMessageInfo implements MessageInfo {

        private Object request = null;
        private Object response = null;
        private Map map = new HashMap();

        HttpMessageInfo() {
        }

        HttpMessageInfo(HttpServletRequest request, HttpServletResponse response) {
            this.request = request;
            this.response = response;
        }

        @Override
        public Object getRequestMessage() {
            return request;
        }

        @Override
        public Object getResponseMessage() {
            return response;
        }

        @Override
        public void setRequestMessage(Object request) {
            this.request = request;
        }

        @Override
        public void setResponseMessage(Object response) {
            this.response = response;
        }

        @Override
        public Map getMap() {
            return map;
        }
    }

    /**
     * Commit the Jakarta Authorization module, bringing the policy into service.
     *
     * Implementation note: If the committed policy doesn't contains all the permissions, the role mapper is probably broken.
     */
    protected void configureSecurity(WebBundleDescriptor webBundleDescriptor, boolean isSystem) {
        try {
            webSecurityManagerFactory.createManager(webBundleDescriptor, true, serverContext).commitPolicy();

            String contextId = getContextID(webBundleDescriptor);
            if (isSystem && contextId.equals("__admingui/__admingui")) {
                websecurityProbeProvider.policyCreationEvent(contextId);
            }
        } catch (Exception ce) {
            _logger.log(SEVERE, "policy.configure", ce);
            throw new RuntimeException(ce);
        }
    }

    // Moved from J2EEInstanceListener.java
    private SecurityContext getSecurityContextForPrincipal(final Principal p) {
        if (p == null) {
            return null;
        }
        if (p instanceof WebPrincipal) {
            return ((WebPrincipal) p).getSecurityContext();
        } else {
            return AccessController.doPrivileged(new PrivilegedAction<SecurityContext>() {

                @Override
                public SecurityContext run() {
                    Subject s = new Subject();
                    s.getPrincipals().add(p);
                    return new SecurityContext(p.getName(), s);
                }
            });
        }
    }

    public void setCurrentSecurityContextWithWebPrincipal(Principal principal) {
        if (principal instanceof WebPrincipal) {
            SecurityContext.setCurrent(getSecurityContextForPrincipal(principal));
        }
    }

    public void setCurrentSecurityContext(Principal principal) {
        SecurityContext.setCurrent(getSecurityContextForPrincipal(principal));
    }

    // TODO: reexamine this after TP2
    public synchronized void initConfigHelper(final ServletContext servletContext) {
        if (this.helper != null) {
            return;
        }
        this.helper = getConfigHelper(servletContext);
    }

    @Override
    public void postConstruct() {
        nwListeners = networkConfig.getNetworkListeners();
    }
}