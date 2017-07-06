/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.geode.management.internal.cli.commands;

import static org.apache.geode.distributed.ConfigurationProperties.CLUSTER_SSL_CIPHERS;
import static org.apache.geode.distributed.ConfigurationProperties.CLUSTER_SSL_PROTOCOLS;
import static org.apache.geode.distributed.ConfigurationProperties.LOCATORS;
import static org.apache.geode.distributed.ConfigurationProperties.MCAST_PORT;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.KeyStore;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.security.sasl.AuthenticationException;

import org.apache.commons.lang.StringUtils;

import org.apache.geode.distributed.internal.DistributionConfig;
import org.apache.geode.internal.ClassPathLoader;
import org.apache.geode.internal.DSFIDFactory;
import org.apache.geode.internal.lang.Initializer;
import org.apache.geode.internal.util.IOUtils;
import org.apache.geode.management.cli.Result;
import org.apache.geode.management.internal.Credential;
import org.apache.geode.management.internal.JmxManagerLocatorRequest;
import org.apache.geode.management.internal.JmxManagerLocatorResponse;
import org.apache.geode.management.internal.SSLUtil;
import org.apache.geode.management.internal.cli.CliUtil;
import org.apache.geode.management.internal.cli.CommandContext;
import org.apache.geode.management.internal.cli.LogWrapper;
import org.apache.geode.management.internal.cli.domain.ConnectToLocatorResult;
import org.apache.geode.management.internal.cli.i18n.CliStrings;
import org.apache.geode.management.internal.cli.result.InfoResultData;
import org.apache.geode.management.internal.cli.result.ResultBuilder;
import org.apache.geode.management.internal.cli.shell.Gfsh;
import org.apache.geode.management.internal.cli.shell.JmxOperationInvoker;
import org.apache.geode.management.internal.cli.shell.OperationInvoker;
import org.apache.geode.management.internal.cli.util.ConnectionEndpoint;
import org.apache.geode.management.internal.web.domain.LinkIndex;
import org.apache.geode.management.internal.web.http.support.SimpleHttpRequester;
import org.apache.geode.management.internal.web.shell.SimpleHttpOperationInvoker;
import org.apache.geode.security.AuthenticationFailedException;

public class ConnectCommand {

  private ConnectionEndpoint memberRmiHostPort;
  private ConnectionEndpoint locatorTcpHostPort;
  private Credential user;

  private Credential keystore;
  private Credential truststore;
  private String sslCiphers;
  private String sslProtocols;

  private boolean useHttp;
  private boolean useSsl;
  private CommandContext gfsh;
  private String gfSecurityPropertiesPath;

  private String url;;

  private Result result;

  // millis that connect --locator will wait for a response from the locator.
  private final static int CONNECT_LOCATOR_TIMEOUT_MS = 60000; // see bug 45971


  public ConnectCommand(ConnectionEndpoint locatorTcpHostPort, ConnectionEndpoint memberRmiHostPort,
      Credential user, Credential keystore, Credential truststore, String sslCiphers,
      String sslProtocols, boolean useHttp, boolean useSsl, CommandContext gfsh,
      String gfSecurityPropertiesPath, String url) {
    this.locatorTcpHostPort = locatorTcpHostPort;
    this.memberRmiHostPort = memberRmiHostPort;
    this.user = user;
    this.keystore = keystore;
    this.truststore = truststore;
    this.sslCiphers = sslCiphers;
    this.sslProtocols = sslProtocols;
    this.useHttp = useHttp;
    this.useSsl = useSsl;
    this.gfsh = gfsh;
    this.gfSecurityPropertiesPath = gfSecurityPropertiesPath;
    this.url = url;
  }

  public Result run() throws IOException {
    if (gfsh != null && gfsh.isConnectedAndReady()) {
      return ResultBuilder
          .createInfoResult("Already connected to: " + gfsh.getOperationInvoker().toString());
    }

    Map<String, String> sslConfigProps;

    if (user.hasIdentifier() && !user.hasPassword()) {
      String newPassword = gfsh.readPassword(CliStrings.CONNECT__PASSWORD + ": ");
      if (StringUtils.isBlank(newPassword)) {
        throw new AuthenticationException(CliStrings.CONNECT__MSG__JMX_PASSWORD_MUST_BE_SPECIFIED);
      }
      user = new Credential(user.getIdentifier(), newPassword);
    }

    sslConfigProps = readSSLConfiguration();

    if (useHttp) {
      result = httpConnect(sslConfigProps, useSsl, url, user);
    } else {
      result = jmxConnect(sslConfigProps, memberRmiHostPort, locatorTcpHostPort, useSsl,
          user.getIdentifier(), user.getPassword(), gfSecurityPropertiesPath, false);
    }
    return result;
  }

  /**
   * Common code to read SSL information. Used by JMX, Locator & HTTP mode connect
   */
  private Map<String, String> readSSLConfiguration() throws IOException {
    final Map<String, String> sslConfigProps = new LinkedHashMap<String, String>();

    // JMX SSL Config 1:
    // First from gfsecurity properties file if it's specified OR
    // if the default gfsecurity.properties exists useSsl==true
    if (useSsl || gfSecurityPropertiesPath != null) {
      // reference to hold resolved gfSecurityPropertiesPath
      gfSecurityPropertiesPath = CliUtil.resolvePathname(gfSecurityPropertiesPath);
      URL gfSecurityPropertiesUrl = null;

      // Case 1: User has specified gfSecurity properties file
      if (StringUtils.isNotBlank(gfSecurityPropertiesPath)) {
        // User specified gfSecurity properties doesn't exist
        if (!IOUtils.isExistingPathname(gfSecurityPropertiesPath)) {
          gfsh.printAsSevere(CliStrings.format(CliStrings.GEODE_0_PROPERTIES_1_NOT_FOUND_MESSAGE,
              "Security ", gfSecurityPropertiesPath));
        } else {
          gfSecurityPropertiesUrl = new File(gfSecurityPropertiesPath).toURI().toURL();
        }
      } else if (useSsl && gfSecurityPropertiesPath == null) {
        // Case 2: User has specified to useSsl but hasn't specified
        // gfSecurity properties file. Use default "gfsecurity.properties"
        // in current dir, user's home or classpath
        gfSecurityPropertiesUrl = getFileUrl("gfsecurity.properties");
      }
      // if 'gfSecurityPropertiesPath' OR gfsecurity.properties has resolvable path
      if (gfSecurityPropertiesUrl != null) {
        gfsh.logToFile("Using security properties file : "
            + CliUtil.decodeWithDefaultCharSet(gfSecurityPropertiesUrl.getPath()), null);
        Map<String, String> gfsecurityProps = loadPropertiesFromURL(gfSecurityPropertiesUrl);
        // command line options (if any) would override props in gfsecurity.properties
        sslConfigProps.putAll(gfsecurityProps);
      }
    }

    int numTimesPrompted = 0;
    /*
     * Using do-while here for a case when --use-ssl=true is specified but no SSL options were
     * specified & there was no gfsecurity properties specified or readable in default gfsh
     * directory.
     *
     * NOTE: 2nd round of prompting is done only when sslConfigProps map is empty & useSsl is true -
     * so we won't over-write any previous values.
     */
    do {
      // JMX SSL Config 2: Now read the options
      if (numTimesPrompted > 0) {
        Gfsh.println("Please specify these SSL Configuration properties: ");
      }

      if (numTimesPrompted > 0) {
        // NOTE: sslConfigProps map was empty
        keystore = new Credential(gfsh.readText(CliStrings.CONNECT__KEY_STORE + ": "),
            keystore.getPassword());
      }

      if (keystore.hasIdentifier() && !keystore.hasPassword()) {
        keystore = credentialWithRetrievedPassword(keystore.getIdentifier(), sslConfigProps,
            Gfsh.SSL_KEYSTORE_PASSWORD, CliStrings.CONNECT__KEY_STORE_PASSWORD);
      }

      keystore.onValid(() -> {
        sslConfigProps.put(Gfsh.SSL_KEYSTORE, keystore.getIdentifier());
        sslConfigProps.put(Gfsh.SSL_KEYSTORE_PASSWORD, keystore.getPassword());
      });

      if (numTimesPrompted > 0) {
        truststore = new Credential(gfsh.readText(CliStrings.CONNECT__TRUST_STORE + ": "),
            truststore.getPassword());
      }
      if (truststore.hasIdentifier() && !truststore.hasPassword()) {
        truststore = credentialWithRetrievedPassword(truststore.getIdentifier(), sslConfigProps,
            Gfsh.SSL_TRUSTSTORE_PASSWORD, CliStrings.CONNECT__TRUST_STORE_PASSWORD);
      }

      truststore.onValid(() -> {
        sslConfigProps.put(Gfsh.SSL_TRUSTSTORE, truststore.getIdentifier());
        sslConfigProps.put(Gfsh.SSL_TRUSTSTORE_PASSWORD, truststore.getPassword());
      });

      if (numTimesPrompted > 0) {
        sslCiphers = gfsh.readText(CliStrings.CONNECT__SSL_CIPHERS + ": ");
      }
      if (sslCiphers != null && sslCiphers.length() > 0) {
        // sslConfigProps.put(DistributionConfig.CLUSTER_SSL_CIPHERS_NAME, sslCiphers);
        sslConfigProps.put(Gfsh.SSL_ENABLED_CIPHERS, sslCiphers);
      }

      if (numTimesPrompted > 0) {
        sslProtocols = gfsh.readText(CliStrings.CONNECT__SSL_PROTOCOLS + ": ");
      }
      if (sslProtocols != null && sslProtocols.length() > 0) {
        // sslConfigProps.put(DistributionConfig.CLUSTER_SSL_PROTOCOLS_NAME, sslProtocols);
        sslConfigProps.put(Gfsh.SSL_ENABLED_PROTOCOLS, sslProtocols);
      }

      // SSL is required to be used but no SSL config found
    } while (useSsl && sslConfigProps.isEmpty() && (0 == numTimesPrompted++)
        && !gfsh.isQuietMode());
    return sslConfigProps;
  }

  private Credential credentialWithRetrievedPassword(String identifier,
      Map<String, String> sslConfigProps, String propertyKey, String prompt) throws IOException {
    String password = sslConfigProps.get(propertyKey);
    if (password == null) {
      // not even in properties file, prompt user for it
      password = gfsh.readPassword(prompt + ": ");
    }
    return new Credential(identifier, password);
  }

  private Result jmxConnect(Map<String, String> sslConfigProps,
      ConnectionEndpoint memberRmiHostPort, ConnectionEndpoint locatorTcpHostPort, boolean useSsl,
      String userName, String password, String gfSecurityPropertiesPath, boolean retry) {
    ConnectionEndpoint hostPortToConnect = null;

    try {

      // trying to find the hostPortToConnect, if rmi host port exists, use that, otherwise, use
      // locator to find the rmi host port
      if (memberRmiHostPort != null) {
        hostPortToConnect = memberRmiHostPort;
      } else {
        // Props required to configure a SocketCreator with SSL.
        // Used for gfsh->locator connection & not needed for gfsh->manager connection
        if (useSsl || !sslConfigProps.isEmpty()) {
          sslConfigProps.put(MCAST_PORT, String.valueOf(0));
          sslConfigProps.put(LOCATORS, "");

          String sslInfoLogMsg = "Connecting to Locator via SSL.";
          if (useSsl) {
            sslInfoLogMsg = CliStrings.CONNECT__USE_SSL + " is set to true. " + sslInfoLogMsg;
          }
          gfsh.logToFile(sslInfoLogMsg, null);
        }

        Gfsh.println(CliStrings.format(CliStrings.CONNECT__MSG__CONNECTING_TO_LOCATOR_AT_0,
            new Object[] {locatorTcpHostPort.toString(false)}));
        ConnectToLocatorResult connectToLocatorResult =
            connectToLocator(locatorTcpHostPort.getHost(), locatorTcpHostPort.getPort(),
                CONNECT_LOCATOR_TIMEOUT_MS, sslConfigProps);
        hostPortToConnect = connectToLocatorResult.getMemberEndpoint();

        // when locator is configured to use SSL (ssl-enabled=true) but manager is not
        // (jmx-manager-ssl=false)
        if ((useSsl || !sslConfigProps.isEmpty())
            && !connectToLocatorResult.isJmxManagerSslEnabled()) {
          gfsh.logInfo(
              CliStrings.CONNECT__USE_SSL
                  + " is set to true. But JMX Manager doesn't support SSL, connecting without SSL.",
              null);
          sslConfigProps.clear();
        }
      }

      if (!sslConfigProps.isEmpty()) {
        gfsh.logToFile("Connecting to manager via SSL.", null);
      }

      // print out the connecting endpoint
      // Why is retry a guard on a print statement but does nothing else?
      if (!retry) {
        Gfsh.println(CliStrings.format(CliStrings.CONNECT__MSG__CONNECTING_TO_MANAGER_AT_0,
            new Object[] {hostPortToConnect.toString(false)}));
      }

      InfoResultData infoResultData = ResultBuilder.createInfoResultData();


      OperationInvoker invoker =
          new JmxOperationInvoker(hostPortToConnect.getHost(), hostPortToConnect.getPort(),
              userName, password, sslConfigProps, gfSecurityPropertiesPath);


      gfsh.setOperationInvoker(invoker);
      infoResultData.addLine(
          CliStrings.format(CliStrings.CONNECT__MSG__SUCCESS, hostPortToConnect.toString(false)));
      LogWrapper.getInstance().info(
          CliStrings.format(CliStrings.CONNECT__MSG__SUCCESS, hostPortToConnect.toString(false)));
      return ResultBuilder.buildResult(infoResultData);
    } catch (Exception e) {
      // all other exceptions, just logs it and returns a connection error
      if (!(e instanceof SecurityException) && !(e instanceof AuthenticationFailedException)) {
        throw new RuntimeException(e);
        // return handleException(e, hostPortToConnect);
      }

      // if it's security exception, and we already sent in username and password, still returns the
      // connection error
      if (userName != null) {
        throw new RuntimeException(e);
        // return handleException(e, hostPortToConnect);
      }

      // otherwise, prompt for username and password and retry the connection
      try {
        Credential promptedUser = promptForUser();
        if (!promptedUser.isValid()) {
          throw new RuntimeException(e);
        }
        return jmxConnect(sslConfigProps, hostPortToConnect, null, useSsl, userName, password,
            gfSecurityPropertiesPath, true);
      } catch (IOException ioe) {
        throw new RuntimeException(ioe);
        // return handleException(ioe, hostPortToConnect);
      }
    } finally {
      Gfsh.redirectInternalJavaLoggers();
    }
  }

  private Credential promptForUser() throws IOException {
    String userName = gfsh.readText(CliStrings.CONNECT__USERNAME + ": ");
    String password = gfsh.readPassword(CliStrings.CONNECT__PASSWORD + ": ");
    return new Credential(userName, password);
  }

  private Result httpConnect(Map<String, String> sslConfigProps, boolean useSsl, String url,
      final Credential user) {
    try {
      Map<String, String> securityProperties = new HashMap<>();

      // at this point, if userName is not empty, password should not be empty either
      user.onValid(() -> {
        securityProperties.put("security-username", user.getIdentifier());
        securityProperties.put("security-password", user.getPassword());
      });

      if (useSsl) {
        configureHttpsURLConnection(sslConfigProps);
        if (url.startsWith("http:")) {
          url = url.replace("http:", "https:");
        }
      }

      for (String secKey : sslConfigProps.keySet()) {
        securityProperties.put(secKey, sslConfigProps.get(secKey));
      }

      // This is so that SSL termination results in https URLs being returned
      String query = (url.startsWith("https")) ? "?scheme=https" : "";


      verifyAuthenticatedConnection(securityProperties, query);

      OperationInvoker invoker = new SimpleHttpOperationInvoker(gfsh, url, securityProperties);


      Initializer.init(invoker);
      gfsh.setOperationInvoker(invoker);

      LogWrapper.getInstance()
          .info(CliStrings.format(CliStrings.CONNECT__MSG__SUCCESS, invoker.toString()));
      return ResultBuilder.createInfoResult(
          CliStrings.format(CliStrings.CONNECT__MSG__SUCCESS, invoker.toString()));

    } catch (Exception e) {
      // all other exceptions, just logs it and returns a connection error
      if (!(e instanceof SecurityException) && !(e instanceof AuthenticationFailedException)) {
        throw new RuntimeException(e);
        // return handleException(e, null);
      }

      // if it's security exception, and we already sent in username and password, still returns the
      // connection error
      if (user.getIdentifier() != null) {
        throw new RuntimeException(e);
        // return handleException(e, null);
      }

      // otherwise, prompt for username and password and retry the connection
      try {
        Credential promptedUser = promptForUser();
        return httpConnect(sslConfigProps, useSsl, url, promptedUser);
      } catch (IOException ioe) {
        throw new RuntimeException(e);
        // return handleException(ioe, null);
      }
    } finally {
      Gfsh.redirectInternalJavaLoggers();
    }
  }

  void verifyAuthenticatedConnection(Map<String, String> securityProperties, String query) {
    LogWrapper.getInstance().warning(String.format(
        "Sending HTTP request for Link Index at (%1$s)...", url.concat("/index").concat(query)));

    LinkIndex linkIndex =
        new SimpleHttpRequester(gfsh, CONNECT_LOCATOR_TIMEOUT_MS, securityProperties)
            .exchange(url.concat("/index").concat(query), LinkIndex.class);

    LogWrapper.getInstance()
        .warning(String.format("Received Link Index (%1$s)", linkIndex.toString()));
  }

  private Result handleException(Exception e, ConnectionEndpoint hostPortToConnect) {
    throw new RuntimeException(e);
    // String errorMessage = e.getMessage();
    // if (hostPortToConnect != null) {
    // errorMessage = CliStrings.format(CliStrings.CONNECT__MSG__ERROR,
    // hostPortToConnect.toString(false), e.getMessage());
    // }
    // LogWrapper.getInstance().severe(errorMessage, e);
    // return ResultBuilder.createConnectionErrorResult(errorMessage);
  }

  // Copied from DistributedSystem.java
  public static URL getFileUrl(String fileName) {
    File file = new File(fileName);

    if (file.exists()) {
      try {
        return IOUtils.tryGetCanonicalFileElseGetAbsoluteFile(file).toURI().toURL();
      } catch (MalformedURLException ignore) {
      }
    }

    file = new File(System.getProperty("user.home"), fileName);

    if (file.exists()) {
      try {
        return IOUtils.tryGetCanonicalFileElseGetAbsoluteFile(file).toURI().toURL();
      } catch (MalformedURLException ignore) {
      }
    }

    return ClassPathLoader.getLatest().getResource(ShellCommands.class, fileName);
  }

  private void configureHttpsURLConnection(Map<String, String> sslConfigProps) throws Exception {
    String keystore = sslConfigProps.get(Gfsh.SSL_KEYSTORE);
    String keystorePassword = sslConfigProps.get(Gfsh.SSL_KEYSTORE_PASSWORD);
    String truststore = sslConfigProps.get(Gfsh.SSL_TRUSTSTORE);
    String truststorePassword = sslConfigProps.get(Gfsh.SSL_TRUSTSTORE_PASSWORD);
    // Ciphers are not passed to HttpsURLConnection. Could not find a clean way
    // to pass this attribute to socket layer (see #51645)
    String sslCiphers = sslConfigProps.get(CLUSTER_SSL_CIPHERS);
    String sslProtocols = sslConfigProps.get(CLUSTER_SSL_PROTOCOLS);

    // Commenting the code to set cipher suites in GFSH rest connect (see #51645)
    /*
     * if(sslCiphers != null){ System.setProperty("https.cipherSuites", sslCiphers); }
     */
    FileInputStream keyStoreStream = null;
    FileInputStream trustStoreStream = null;
    try {

      KeyManagerFactory keyManagerFactory = null;
      if (StringUtils.isNotBlank(keystore)) {
        KeyStore clientKeys = KeyStore.getInstance("JKS");
        keyStoreStream = new FileInputStream(keystore);
        clientKeys.load(keyStoreStream, keystorePassword.toCharArray());

        keyManagerFactory =
            KeyManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(clientKeys, keystorePassword.toCharArray());
      }

      // load server public key
      TrustManagerFactory trustManagerFactory = null;
      if (StringUtils.isNotBlank(truststore)) {
        KeyStore serverPub = KeyStore.getInstance("JKS");
        trustStoreStream = new FileInputStream(truststore);
        serverPub.load(trustStoreStream, truststorePassword.toCharArray());
        trustManagerFactory =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(serverPub);
      }

      SSLContext ssl = SSLContext.getInstance(SSLUtil.getSSLAlgo(SSLUtil.readArray(sslProtocols)));

      ssl.init(keyManagerFactory != null ? keyManagerFactory.getKeyManagers() : null,
          trustManagerFactory != null ? trustManagerFactory.getTrustManagers() : null,
          new java.security.SecureRandom());

      HttpsURLConnection.setDefaultSSLSocketFactory(ssl.getSocketFactory());
    } finally {
      if (keyStoreStream != null) {
        keyStoreStream.close();
      }
      if (trustStoreStream != null) {
        trustStoreStream.close();
      }

    }


  }

  public static ConnectToLocatorResult connectToLocator(String host, int port, int timeout,
      Map<String, String> props) throws IOException {
    // register DSFID types first; invoked explicitly so that all message type
    // initializations do not happen in first deserialization on a possibly
    // "precious" thread
    DSFIDFactory.registerTypes();

    JmxManagerLocatorResponse locatorResponse =
        JmxManagerLocatorRequest.send(host, port, timeout, props);

    if (StringUtils.isBlank(locatorResponse.getHost()) || locatorResponse.getPort() == 0) {
      Throwable locatorResponseException = locatorResponse.getException();
      String exceptionMessage = CliStrings.CONNECT__MSG__LOCATOR_COULD_NOT_FIND_MANAGER;

      if (locatorResponseException != null) {
        String locatorResponseExceptionMessage = locatorResponseException.getMessage();
        locatorResponseExceptionMessage = (StringUtils.isNotBlank(locatorResponseExceptionMessage)
            ? locatorResponseExceptionMessage : locatorResponseException.toString());
        exceptionMessage = "Exception caused JMX Manager startup to fail because: '"
            .concat(locatorResponseExceptionMessage).concat("'");
      }

      throw new IllegalStateException(exceptionMessage, locatorResponseException);
    }

    ConnectionEndpoint memberEndpoint =
        new ConnectionEndpoint(locatorResponse.getHost(), locatorResponse.getPort());

    String resultMessage = CliStrings.format(CliStrings.CONNECT__MSG__CONNECTING_TO_MANAGER_AT_0,
        memberEndpoint.toString(false));

    return new ConnectToLocatorResult(memberEndpoint, resultMessage,
        locatorResponse.isJmxManagerSslEnabled());
  }

  /* package-private */
  static Map<String, String> loadPropertiesFromURL(URL gfSecurityPropertiesUrl) {
    Map<String, String> propsMap = Collections.emptyMap();

    if (gfSecurityPropertiesUrl != null) {
      InputStream inputStream = null;
      try {
        Properties props = new Properties();
        inputStream = gfSecurityPropertiesUrl.openStream();
        props.load(inputStream);
        if (!props.isEmpty()) {
          Set<String> jmxSpecificProps = new HashSet<String>();
          propsMap = new LinkedHashMap<String, String>();
          Set<Map.Entry<Object, Object>> entrySet = props.entrySet();
          for (Map.Entry<Object, Object> entry : entrySet) {

            String key = (String) entry.getKey();
            if (key.endsWith(DistributionConfig.JMX_SSL_PROPS_SUFFIX)) {
              key =
                  key.substring(0, key.length() - DistributionConfig.JMX_SSL_PROPS_SUFFIX.length());
              jmxSpecificProps.add(key);

              propsMap.put(key, (String) entry.getValue());
            } else if (!jmxSpecificProps.contains(key)) {// Prefer properties ending with "-jmx"
              // over default SSL props.
              propsMap.put(key, (String) entry.getValue());
            }
          }
          props.clear();
          jmxSpecificProps.clear();
        }
      } catch (IOException io) {
        throw new RuntimeException(
            CliStrings.format(CliStrings.CONNECT__MSG__COULD_NOT_READ_CONFIG_FROM_0,
                CliUtil.decodeWithDefaultCharSet(gfSecurityPropertiesUrl.getPath())),
            io);
      } finally {
        IOUtils.close(inputStream);
      }
    }
    return propsMap;
  }

}