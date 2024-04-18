/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.utils;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.rmi.AccessException;
import java.rmi.AlreadyBoundException;
import java.rmi.NoSuchObjectException;
import java.rmi.NotBoundException;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.registry.Registry;
import java.rmi.server.RMIClientSocketFactory;
import java.rmi.server.RMIServerSocketFactory;
import java.rmi.server.UnicastRemoteObject;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import javax.management.remote.*;
import javax.management.remote.rmi.RMIConnectorServer;
import javax.management.remote.rmi.RMIJRMPServerImpl;
import javax.rmi.ssl.SslRMIClientSocketFactory;
import javax.rmi.ssl.SslRMIServerSocketFactory;
import javax.security.auth.Subject;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.jmx.remote.security.JMXPluggableAuthenticator;
import org.apache.cassandra.auth.jmx.AuthenticationProxy;

public class JMXServerUtils
{
    private static final Logger logger = LoggerFactory.getLogger(JMXServerUtils.class);

    /**
     * Creates a server programmatically. This allows us to set parameters which normally are
     * inaccessable.
     */
    @SuppressWarnings("resource")
    public static JMXConnectorServer createJMXServer(int port, boolean local)
    throws IOException
    {
        Map<String, Object> env = new HashMap<>();

        InetAddress serverAddress = null;
        if (local)
        {
            serverAddress = InetAddress.getLoopbackAddress();
            System.setProperty("java.rmi.server.hostname", serverAddress.getHostAddress());
        }

        // Configure the RMI client & server socket factories, including SSL config.
        env.putAll(configureJmxSocketFactories(serverAddress, local));

        // configure the RMI registry
        Registry registry = new JmxRegistry(port,
                                            (RMIClientSocketFactory) env.get(RMIConnectorServer.RMI_CLIENT_SOCKET_FACTORY_ATTRIBUTE),
                                            (RMIServerSocketFactory) env.get(RMIConnectorServer.RMI_SERVER_SOCKET_FACTORY_ATTRIBUTE),
                                            "jmxrmi");

        // Configure authn, using a JMXAuthenticator which either wraps a set log LoginModules configured
        // via a JAAS configuration entry, or one which delegates to the standard file based authenticator.
        // Authn is disabled if com.sun.management.jmxremote.authenticate=false
        env.putAll(configureJmxAuthentication());

        // Configure authz - if a custom proxy class is specified an instance will be returned.
        // If not, but a location for the standard access file is set in system properties, the
        // return value is null, and an entry is added to the env map detailing that location
        // If neither method is specified, no access control is applied
        MBeanServerForwarder authzProxy = configureJmxAuthorization(env);

        // Mark the JMX server as a permanently exported object. This allows the JVM to exit with the
        // server running and also exempts it from the distributed GC scheduler which otherwise would
        // potentially attempt a full GC every `sun.rmi.dgc.server.gcInterval` millis (default is 3600000ms)
        // For more background see:
        //   - CASSANDRA-2967
        //   - https://www.jclarity.com/2015/01/27/rmi-system-gc-unplugged/
        //   - https://bugs.openjdk.java.net/browse/JDK-6760712
        env.put("jmx.remote.x.daemon", "true");

        // Set the port used to create subsequent connections to exported objects over RMI. This simplifies
        // configuration in firewalled environments, but it can't be used in conjuction with SSL sockets.
        // See: CASSANDRA-7087
        int rmiPort = Integer.getInteger("com.sun.management.jmxremote.rmi.port", 0);

        // We create the underlying RMIJRMPServerImpl so that we can manually bind it to the registry,
        // rather then specifying a binding address in the JMXServiceURL and letting it be done automatically
        // when the server is started. The reason for this is that if the registry is configured with SSL
        // sockets, the JMXConnectorServer acts as its client during the binding which means it needs to
        // have a truststore configured which contains the registry's certificate. Manually binding removes
        // this problem.
        // See CASSANDRA-12109.
        RMIJRMPServerImpl server = new RMIJRMPServerImpl(rmiPort,
                                                         (RMIClientSocketFactory) env.get(RMIConnectorServer.RMI_CLIENT_SOCKET_FACTORY_ATTRIBUTE),
                                                         (RMIServerSocketFactory) env.get(RMIConnectorServer.RMI_SERVER_SOCKET_FACTORY_ATTRIBUTE),
                                                         env);
        JMXServiceURL serviceURL = new JMXServiceURL("rmi", null, rmiPort);
        RMIConnectorServer jmxServer = new RMIConnectorServer(serviceURL, env, server, ManagementFactory.getPlatformMBeanServer());

        // If a custom authz proxy was created, attach it to the server now.
        if (authzProxy != null)
            jmxServer.setMBeanServerForwarder(authzProxy);
        jmxServer.start();

        ((JmxRegistry)registry).setRemoteServerStub(server.toStub());
        logJmxServiceUrl(serverAddress, port);
        return jmxServer;
    }

    private static Map<String, Object> configureJmxAuthentication()
    {
        Map<String, Object> env = new HashMap<>();
        if (!Boolean.getBoolean("com.sun.management.jmxremote.authenticate"))
            return env;

        // If authentication is enabled, initialize the appropriate JMXAuthenticator
        // and stash it in the environment settings.
        // A JAAS configuration entry takes precedence. If one is supplied, use
        // Cassandra's own custom JMXAuthenticator implementation which delegates
        // auth to the LoginModules specified by the JAAS configuration entry.
        // If no JAAS entry is found, an instance of the JDK's own
        // JMXPluggableAuthenticator is created. In that case, the admin may have
        // set a location for the JMX password file which must be added to env
        // before creating the authenticator. If no password file has been
        // explicitly set, it's read from the default location
        // $JAVA_HOME/lib/management/jmxremote.password
        String configEntry = System.getProperty("cassandra.jmx.remote.login.config");
        if (configEntry != null)
        {
            env.put(JMXConnectorServer.AUTHENTICATOR, new AuthenticationProxy(configEntry));
        }
        else
        {
            String passwordFile = System.getProperty("com.sun.management.jmxremote.password.file");
            if (passwordFile != null)
            {
                // stash the password file location where JMXPluggableAuthenticator expects it
                env.put("jmx.remote.x.password.file", passwordFile);
            }

            env.put(JMXConnectorServer.AUTHENTICATOR, new JMXPluggableAuthenticatorWrapper(env));
        }
        env.put("jmx.remote.rmi.server.credential.types",
            new String[] { String[].class.getName(), String.class.getName() });
        return env;
    }

    private static MBeanServerForwarder configureJmxAuthorization(Map<String, Object> env)
    {
        // If a custom authz proxy is supplied (Cassandra ships with AuthorizationProxy, which
        // delegates to its own role based IAuthorizer), then instantiate and return one which
        // can be set as the JMXConnectorServer's MBeanServerForwarder.
        // If no custom proxy is supplied, check system properties for the location of the
        // standard access file & stash it in env
        String authzProxyClass = System.getProperty("cassandra.jmx.authorizer");
        if (authzProxyClass != null)
        {
            final InvocationHandler handler = FBUtilities.construct(authzProxyClass, "JMX authz proxy");
            final Class[] interfaces = { MBeanServerForwarder.class };

            Object proxy = Proxy.newProxyInstance(MBeanServerForwarder.class.getClassLoader(), interfaces, handler);
            return MBeanServerForwarder.class.cast(proxy);
        }
        else
        {
            String accessFile = System.getProperty("com.sun.management.jmxremote.access.file");
            if (accessFile != null)
            {
                env.put("jmx.remote.x.access.file", accessFile);
            }
            return null;
        }
    }

    private static Map<String, Object> configureJmxSocketFactories(InetAddress serverAddress, boolean localOnly)
    {
        Map<String, Object> env = new HashMap<>();
        if (Boolean.getBoolean("com.sun.management.jmxremote.ssl"))
        {
            boolean requireClientAuth = Boolean.getBoolean("com.sun.management.jmxremote.ssl.need.client.auth");
            String[] protocols = null;
            String protocolList = System.getProperty("com.sun.management.jmxremote.ssl.enabled.protocols");
            if (protocolList != null)
            {
                System.setProperty("javax.rmi.ssl.client.enabledProtocols", protocolList);
                protocols = StringUtils.split(protocolList, ',');
            }

            String[] ciphers = null;
            String cipherList = System.getProperty("com.sun.management.jmxremote.ssl.enabled.cipher.suites");
            if (cipherList != null)
            {
                System.setProperty("javax.rmi.ssl.client.enabledCipherSuites", cipherList);
                ciphers = StringUtils.split(cipherList, ',');
            }

            SslRMIClientSocketFactory clientFactory = new SslRMIClientSocketFactory();
            SslRMIServerSocketFactory serverFactory = new SslRMIServerSocketFactory(ciphers, protocols, requireClientAuth);
            env.put(RMIConnectorServer.RMI_SERVER_SOCKET_FACTORY_ATTRIBUTE, serverFactory);
            env.put(RMIConnectorServer.RMI_CLIENT_SOCKET_FACTORY_ATTRIBUTE, clientFactory);
            env.put("com.sun.jndi.rmi.factory.socket", clientFactory);
            logJmxSslConfig(serverFactory);
        }
        else if (localOnly)
        {
            env.put(RMIConnectorServer.RMI_SERVER_SOCKET_FACTORY_ATTRIBUTE,
                    new RMIServerSocketFactoryImpl(serverAddress));
        }

        return env;
    }

    @VisibleForTesting
    public static void logJmxServiceUrl(InetAddress serverAddress, int port)
    {
        String urlTemplate = "service:jmx:rmi://%1$s/jndi/rmi://%1$s:%2$d/jmxrmi";
        String hostName;
        if (serverAddress == null)
        {
            hostName = FBUtilities.getBroadcastAddress() instanceof Inet6Address ? "[::]" : "0.0.0.0";
        }
        else
        {
            // hostnames based on IPv6 addresses must be wrapped in [ ]
            hostName = serverAddress instanceof Inet6Address
                       ? '[' + serverAddress.getHostAddress() + ']'
                       : serverAddress.getHostAddress();
        }
        String url = String.format(urlTemplate, hostName, port);
        logger.info("Configured JMX server at: {}", url);
    }

    private static void logJmxSslConfig(SslRMIServerSocketFactory serverFactory)
    {
        logger.debug("JMX SSL configuration. { protocols: [{}], cipher_suites: [{}], require_client_auth: {} }",
                     serverFactory.getEnabledProtocols() == null
                     ? "'JVM defaults'"
                     : Arrays.stream(serverFactory.getEnabledProtocols()).collect(Collectors.joining("','", "'", "'")),
                     serverFactory.getEnabledCipherSuites() == null
                     ? "'JVM defaults'"
                     : Arrays.stream(serverFactory.getEnabledCipherSuites()).collect(Collectors.joining("','", "'", "'")),
                     serverFactory.getNeedClientAuth());
    }

    private static class JMXPluggableAuthenticatorWrapper implements JMXAuthenticator
    {
        final Map<?, ?> env;
        private JMXPluggableAuthenticatorWrapper(Map<?, ?> env)
        {
            this.env = ImmutableMap.copyOf(env);
        }

        public Subject authenticate(Object credentials)
        {
            JMXPluggableAuthenticator authenticator = new JMXPluggableAuthenticator(env);
            return authenticator.authenticate(credentials);
        }
    }

    /*
     * Better to use the internal API than re-invent the wheel.
     */
    @SuppressWarnings("restriction")
    public static class JmxRegistry extends sun.rmi.registry.RegistryImpl
    {
        private final String lookupName;
        private Remote remoteServerStub;

        public JmxRegistry(final int port,
                    final RMIClientSocketFactory csf,
                    RMIServerSocketFactory ssf,
                    final String lookupName) throws RemoteException {
            super(port, csf, ssf);
            this.lookupName = lookupName;
        }

        @Override
        public Remote lookup(String s) throws RemoteException, NotBoundException {
            return lookupName.equals(s) ? remoteServerStub : null;
        }

        @Override
        public void bind(String s, Remote remote) throws RemoteException, AlreadyBoundException, AccessException {
        }

        @Override
        public void unbind(String s) throws RemoteException, NotBoundException, AccessException {
        }

        @Override
        public void rebind(String s, Remote remote) throws RemoteException, AccessException {
        }

        @Override
        public String[] list() throws RemoteException {
            return new String[] {lookupName};
        }

        public void setRemoteServerStub(Remote remoteServerStub) {
            this.remoteServerStub = remoteServerStub;
        }

        /**
         * Closes the underlying JMX registry by unexporting this instance.
         * There is no reason to do this except for in-jvm dtests where we need
         * to stop the registry, so we can start with a clean slate for future cluster
         * builds, and the superclass never expects to be shut down and therefore doesn't
         * handle this edge case at all.
         */
        @VisibleForTesting
        public void close() {
            try
            {
                UnicastRemoteObject.unexportObject(this, true);
            }
            catch (NoSuchObjectException ignored)
            {
                // Ignore if it's already unexported
            }
        }
    }
}