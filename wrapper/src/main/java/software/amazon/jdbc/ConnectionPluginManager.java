/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package software.amazon.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import software.amazon.jdbc.cleanup.CanReleaseResources;
import software.amazon.jdbc.plugin.AuroraConnectionTrackerPluginFactory;
import software.amazon.jdbc.plugin.AuroraHostListConnectionPluginFactory;
import software.amazon.jdbc.plugin.AwsSecretsManagerConnectionPluginFactory;
import software.amazon.jdbc.plugin.ConnectTimeConnectionPluginFactory;
import software.amazon.jdbc.plugin.DataCacheConnectionPluginFactory;
import software.amazon.jdbc.plugin.DefaultConnectionPlugin;
import software.amazon.jdbc.plugin.DriverMetaDataConnectionPluginFactory;
import software.amazon.jdbc.plugin.ExecutionTimeConnectionPluginFactory;
import software.amazon.jdbc.plugin.IamAuthConnectionPluginFactory;
import software.amazon.jdbc.plugin.LogQueryConnectionPluginFactory;
import software.amazon.jdbc.plugin.efm.HostMonitoringConnectionPluginFactory;
import software.amazon.jdbc.plugin.failover.FailoverConnectionPluginFactory;
import software.amazon.jdbc.plugin.readwritesplitting.ReadWriteSplittingPluginFactory;
import software.amazon.jdbc.plugin.staledns.AuroraStaleDnsPluginFactory;
import software.amazon.jdbc.profile.DriverConfigurationProfiles;
import software.amazon.jdbc.util.Messages;
import software.amazon.jdbc.util.SqlMethodAnalyzer;
import software.amazon.jdbc.util.SqlState;
import software.amazon.jdbc.util.StringUtils;
import software.amazon.jdbc.util.WrapperUtils;
import software.amazon.jdbc.wrapper.ConnectionWrapper;

/**
 * This class creates and handles a chain of {@link ConnectionPlugin} for each connection.
 *
 * <p>THIS CLASS IS NOT MULTI-THREADING SAFE IT'S EXPECTED TO HAVE ONE INSTANCE OF THIS MANAGER PER
 * JDBC CONNECTION
 */
public class ConnectionPluginManager implements CanReleaseResources {

  protected static final Map<String, Class<? extends ConnectionPluginFactory>> pluginFactoriesByCode =
      new HashMap<String, Class<? extends ConnectionPluginFactory>>() {
        {
          put("executionTime", ExecutionTimeConnectionPluginFactory.class);
          put("auroraHostList", AuroraHostListConnectionPluginFactory.class);
          put("logQuery", LogQueryConnectionPluginFactory.class);
          put("dataCache", DataCacheConnectionPluginFactory.class);
          put("efm", HostMonitoringConnectionPluginFactory.class);
          put("failover", FailoverConnectionPluginFactory.class);
          put("iam", IamAuthConnectionPluginFactory.class);
          put("awsSecretsManager", AwsSecretsManagerConnectionPluginFactory.class);
          put("auroraStaleDns", AuroraStaleDnsPluginFactory.class);
          put("readWriteSplitting", ReadWriteSplittingPluginFactory.class);
          put("auroraConnectionTracker", AuroraConnectionTrackerPluginFactory.class);
          put("driverMetaData", DriverMetaDataConnectionPluginFactory.class);
          put("connectTime", ConnectTimeConnectionPluginFactory.class);
        }
      };

  protected static final String DEFAULT_PLUGINS = "auroraConnectionTracker,failover,efm";

  private static final Logger LOGGER = Logger.getLogger(ConnectionPluginManager.class.getName());
  private static final String ALL_METHODS = "*";
  private static final String CONNECT_METHOD = "connect";
  private static final String FORCE_CONNECT_METHOD = "forceConnect";
  private static final String ACCEPTS_STRATEGY_METHOD = "acceptsStrategy";
  private static final String GET_HOST_SPEC_BY_STRATEGY_METHOD = "getHostSpecByStrategy";
  private static final String INIT_HOST_PROVIDER_METHOD = "initHostProvider";
  private static final String NOTIFY_CONNECTION_CHANGED_METHOD = "notifyConnectionChanged";
  private static final String NOTIFY_NODE_LIST_CHANGED_METHOD = "notifyNodeListChanged";
  private static final SqlMethodAnalyzer sqlMethodAnalyzer = new SqlMethodAnalyzer();
  private final ReentrantLock lock = new ReentrantLock();

  protected Properties props = new Properties();
  protected List<ConnectionPlugin> plugins;
  protected final ConnectionProvider defaultConnProvider;
  protected final ConnectionWrapper connectionWrapper;
  protected PluginService pluginService;

  @SuppressWarnings("rawtypes")
  protected final Map<String, PluginChainJdbcCallable> pluginChainFuncMap = new HashMap<>();

  public ConnectionPluginManager(
      final ConnectionProvider defaultConnProvider, final ConnectionWrapper connectionWrapper) {
    this.defaultConnProvider = defaultConnProvider;
    this.connectionWrapper = connectionWrapper;
  }

  /**
   * This constructor is for testing purposes only.
   */
  ConnectionPluginManager(
      final ConnectionProvider defaultConnProvider,
      final Properties props,
      final ArrayList<ConnectionPlugin> plugins,
      final ConnectionWrapper connectionWrapper,
      final PluginService pluginService) {
    this(defaultConnProvider, props, plugins, connectionWrapper);
    this.pluginService = pluginService;
  }

  /**
   * This constructor is for testing purposes only.
   */
  ConnectionPluginManager(
      final ConnectionProvider defaultConnProvider,
      final Properties props,
      final ArrayList<ConnectionPlugin> plugins,
      final ConnectionWrapper connectionWrapper) {
    this.defaultConnProvider = defaultConnProvider;
    this.props = props;
    this.plugins = plugins;
    this.connectionWrapper = connectionWrapper;
  }

  public void lock() {
    lock.lock();
  }

  public void unlock() {
    lock.unlock();
  }

  /**
   * Initialize a chain of {@link ConnectionPlugin} using their corresponding {@link
   * ConnectionPluginFactory}. If {@code PropertyDefinition.PLUGINS} is provided by the user,
   * initialize the chain with the given connection plugins in the order they are specified.
   *
   * <p>The {@link DefaultConnectionPlugin} will always be initialized and attached as the last
   * connection plugin in the chain.
   *
   * @param pluginService        a reference to a plugin service that plugin can use
   * @param props                the configuration of the connection
   * @param pluginManagerService a reference to a plugin manager service
   * @throws SQLException if errors occurred during the execution
   */
  public void init(
      final PluginService pluginService, final Properties props, final PluginManagerService pluginManagerService)
      throws SQLException {

    this.props = props;
    this.pluginService = pluginService;

    final String profileName = PropertyDefinition.PROFILE_NAME.getString(props);

    final List<Class<? extends ConnectionPluginFactory>> pluginFactories;

    if (profileName != null) {

      if (!DriverConfigurationProfiles.contains(profileName)) {
        throw new SQLException(
            Messages.get(
                "ConnectionPluginManager.configurationProfileNotFound",
                new Object[] {profileName}));
      }
      pluginFactories = DriverConfigurationProfiles.getPluginFactories(profileName);

    } else {

      String pluginCodes = PropertyDefinition.PLUGINS.getString(props);

      if (pluginCodes == null) {
        pluginCodes = DEFAULT_PLUGINS;
      }

      final List<String> pluginCodeList = StringUtils.split(pluginCodes, ",", true);
      pluginFactories = new ArrayList<>(pluginCodeList.size());

      for (final String pluginCode : pluginCodeList) {
        if (!pluginFactoriesByCode.containsKey(pluginCode)) {
          throw new SQLException(
              Messages.get(
                  "ConnectionPluginManager.unknownPluginCode",
                  new Object[] {pluginCode}));
        }
        pluginFactories.add(pluginFactoriesByCode.get(pluginCode));
      }
    }

    if (!pluginFactories.isEmpty()) {
      try {
        final ConnectionPluginFactory[] factories =
            WrapperUtils.loadClasses(
                    pluginFactories,
                    ConnectionPluginFactory.class,
                    "ConnectionPluginManager.unableToLoadPlugin")
                .toArray(new ConnectionPluginFactory[0]);

        // make a chain of connection plugins

        this.plugins = new ArrayList<>(factories.length + 1);

        for (final ConnectionPluginFactory factory : factories) {
          this.plugins.add(factory.getInstance(this.pluginService, this.props));
        }

      } catch (final InstantiationException instEx) {
        throw new SQLException(instEx.getMessage(), SqlState.UNKNOWN_STATE.getState(), instEx);
      }
    } else {
      this.plugins = new ArrayList<>(1); // one spot for default connection plugin
    }

    // add default connection plugin to the tail
    final ConnectionPlugin defaultPlugin =
        new DefaultConnectionPlugin(this.pluginService, this.defaultConnProvider, pluginManagerService);
    this.plugins.add(defaultPlugin);
  }

  protected <T, E extends Exception> T executeWithSubscribedPlugins(
      final String methodName,
      final PluginPipeline<T, E> pluginPipeline,
      final JdbcCallable<T, E> jdbcMethodFunc)
      throws E {

    if (pluginPipeline == null) {
      throw new IllegalArgumentException("pluginPipeline");
    }

    if (jdbcMethodFunc == null) {
      throw new IllegalArgumentException("jdbcMethodFunc");
    }

    // noinspection unchecked
    PluginChainJdbcCallable<T, E> pluginChainFunc = this.pluginChainFuncMap.get(methodName);

    if (pluginChainFunc == null) {
      pluginChainFunc = this.makePluginChainFunc(methodName);
      this.pluginChainFuncMap.put(methodName, pluginChainFunc);
    }

    if (pluginChainFunc == null) {
      throw new RuntimeException("Error processing this JDBC call.");
    }

    return pluginChainFunc.call(pluginPipeline, jdbcMethodFunc);
  }

  @Nullable
  protected <T, E extends Exception> PluginChainJdbcCallable<T, E> makePluginChainFunc(
      final @NonNull String methodName) {

    PluginChainJdbcCallable<T, E> pluginChainFunc = null;

    for (int i = this.plugins.size() - 1; i >= 0; i--) {
      final ConnectionPlugin plugin = this.plugins.get(i);
      final Set<String> pluginSubscribedMethods = plugin.getSubscribedMethods();
      final boolean isSubscribed =
          pluginSubscribedMethods.contains(ALL_METHODS)
              || pluginSubscribedMethods.contains(methodName);

      if (isSubscribed) {
        if (pluginChainFunc == null) {
          pluginChainFunc = (pipelineFunc, jdbcFunc) -> pipelineFunc.call(plugin, jdbcFunc);
        } else {
          final PluginChainJdbcCallable<T, E> finalPluginChainFunc = pluginChainFunc;
          pluginChainFunc = (pipelineFunc, jdbcFunc) ->
              pipelineFunc.call(plugin, () -> finalPluginChainFunc.call(pipelineFunc, jdbcFunc));
        }
      }
    }
    return pluginChainFunc;
  }

  protected <E extends Exception> void notifySubscribedPlugins(
      final String methodName,
      final PluginPipeline<Void, E> pluginPipeline,
      final ConnectionPlugin skipNotificationForThisPlugin)
      throws E {

    if (pluginPipeline == null) {
      throw new IllegalArgumentException("pluginPipeline");
    }

    for (final ConnectionPlugin plugin : this.plugins) {
      if (plugin == skipNotificationForThisPlugin) {
        continue;
      }
      final Set<String> pluginSubscribedMethods = plugin.getSubscribedMethods();
      final boolean isSubscribed =
          pluginSubscribedMethods.contains(ALL_METHODS)
              || pluginSubscribedMethods.contains(methodName);

      if (isSubscribed) {
        pluginPipeline.call(plugin, null);
      }
    }
  }

  public ConnectionWrapper getConnectionWrapper() {
    return this.connectionWrapper;
  }

  public <T, E extends Exception> T execute(
      final Class<T> resultType,
      final Class<E> exceptionClass,
      final Object methodInvokeOn,
      final String methodName,
      final JdbcCallable<T, E> jdbcMethodFunc,
      final Object[] jdbcMethodArgs)
      throws E {

    final Connection conn = WrapperUtils.getConnectionFromSqlObject(methodInvokeOn);
    if (conn != null && conn != this.pluginService.getCurrentConnection()
        && !sqlMethodAnalyzer.isMethodClosingSqlObject(methodName)) {
      final SQLException e =
          new SQLException(Messages.get("ConnectionPluginManager.methodInvokedAgainstOldConnection",
              new Object[] {methodInvokeOn}));
      throw WrapperUtils.wrapExceptionIfNeeded(exceptionClass, e);
    }

    return executeWithSubscribedPlugins(
        methodName,
        (plugin, func) ->
            plugin.execute(
                resultType, exceptionClass, methodInvokeOn, methodName, func, jdbcMethodArgs),
        jdbcMethodFunc);
  }

  /**
   * Establishes a connection to the given host using the given driver protocol and properties. If a
   * non-default {@link ConnectionProvider} has been set with
   * {@link ConnectionProviderManager#setConnectionProvider} and
   * {@link ConnectionProvider#acceptsUrl(String, HostSpec, Properties)} returns true for the given
   * protocol, host, and properties, the connection will be created by the non-default
   * ConnectionProvider. Otherwise, the connection will be created by the default
   * ConnectionProvider. The default ConnectionProvider will be {@link DriverConnectionProvider} for
   * connections requested via the {@link java.sql.DriverManager} and
   * {@link DataSourceConnectionProvider} for connections requested via an
   * {@link software.amazon.jdbc.ds.AwsWrapperDataSource}.
   *
   * @param driverProtocol      the driver protocol that should be used to establish the connection
   * @param hostSpec            the host details for the desired connection
   * @param props               the connection properties
   * @param isInitialConnection a boolean indicating whether the current {@link Connection} is
   *                            establishing an initial physical connection to the database or has
   *                            already established a physical connection in the past
   * @return a {@link Connection} to the requested host
   * @throws SQLException if there was an error establishing a {@link Connection} to the requested
   *                      host
   */
  public Connection connect(
      final String driverProtocol,
      final HostSpec hostSpec,
      final Properties props,
      final boolean isInitialConnection)
      throws SQLException {

    try {
      return executeWithSubscribedPlugins(
          CONNECT_METHOD,
          (plugin, func) ->
              plugin.connect(driverProtocol, hostSpec, props, isInitialConnection, func),
          () -> {
            throw new SQLException("Shouldn't be called.");
          });
    } catch (final SQLException | RuntimeException e) {
      throw e;
    } catch (final Exception e) {
      throw new SQLException(e);
    }
  }

  /**
   * Establishes a connection to the given host using the given driver protocol and properties. This
   * call differs from {@link ConnectionPlugin#connect} in that the default
   * {@link ConnectionProvider} will be used to establish the connection even if a non-default
   * ConnectionProvider has been set via {@link ConnectionProviderManager#setConnectionProvider}.
   * The default ConnectionProvider will be {@link DriverConnectionProvider} for connections
   * requested via the {@link java.sql.DriverManager} and {@link DataSourceConnectionProvider} for
   * connections requested via an {@link software.amazon.jdbc.ds.AwsWrapperDataSource}.
   *
   * @param driverProtocol      the driver protocol that should be used to establish the connection
   * @param hostSpec            the host details for the desired connection
   * @param props               the connection properties
   * @param isInitialConnection a boolean indicating whether the current {@link Connection} is
   *                            establishing an initial physical connection to the database or has
   *                            already established a physical connection in the past
   * @return a {@link Connection} to the requested host
   * @throws SQLException if there was an error establishing a {@link Connection} to the requested
   *                      host
   */
  public Connection forceConnect(
      final String driverProtocol,
      final HostSpec hostSpec,
      final Properties props,
      final boolean isInitialConnection)
      throws SQLException {

    try {
      return executeWithSubscribedPlugins(
          FORCE_CONNECT_METHOD,
          (plugin, func) ->
              plugin.forceConnect(driverProtocol, hostSpec, props, isInitialConnection, func),
          () -> {
            throw new SQLException("Shouldn't be called.");
          });
    } catch (SQLException | RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new SQLException(e);
    }
  }

  /**
   * Returns a boolean indicating if the available {@link ConnectionProvider} or
   * {@link ConnectionPlugin} instances implement the selection of a host with the requested role
   * and strategy via {@link #getHostSpecByStrategy}.
   *
   * @param role     the desired host role
   * @param strategy the strategy that should be used to pick a host (eg "random")
   * @return true if the available {@link ConnectionProvider} or {@link ConnectionPlugin} instances
   *     support the selection of a host with the requested role and strategy via
   *     {@link #getHostSpecByStrategy}. Otherwise, return false.
   */
  public boolean acceptsStrategy(HostRole role, String strategy) throws SQLException {
    try {
      for (ConnectionPlugin plugin : this.plugins) {
        Set<String> pluginSubscribedMethods = plugin.getSubscribedMethods();
        boolean isSubscribed =
            pluginSubscribedMethods.contains(ALL_METHODS)
                || pluginSubscribedMethods.contains(ACCEPTS_STRATEGY_METHOD);

        if (isSubscribed) {
          if (plugin.acceptsStrategy(role, strategy)) {
            return true;
          }
        }
      }

      return false;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new SQLException(e);
    }
  }

  /**
   * Selects a {@link HostSpec} with the requested role from available hosts using the requested
   * strategy. {@link #acceptsStrategy} should be called first to evaluate if the available
   * {@link ConnectionProvider} or {@link ConnectionPlugin} instances support the selection of a
   * host with the requested role and strategy.
   *
   * @param role     the desired role of the host - either a writer or a reader
   * @param strategy the strategy that should be used to select a {@link HostSpec} from the
   *                 available hosts (eg "random")
   * @return a {@link HostSpec} with the requested role
   * @throws SQLException                  if the available hosts do not contain any hosts matching
   *                                       the requested role or an error occurs while selecting a
   *                                       host
   * @throws UnsupportedOperationException if the available {@link ConnectionProvider} or
   *                                       {@link ConnectionPlugin} instances do not support the
   *                                       requested strategy
   */
  public HostSpec getHostSpecByStrategy(HostRole role, String strategy)
      throws SQLException, UnsupportedOperationException {
    try {
      for (ConnectionPlugin plugin : this.plugins) {
        Set<String> pluginSubscribedMethods = plugin.getSubscribedMethods();
        boolean isSubscribed =
            pluginSubscribedMethods.contains(ALL_METHODS)
                || pluginSubscribedMethods.contains(GET_HOST_SPEC_BY_STRATEGY_METHOD);

        if (isSubscribed) {
          try {
            final HostSpec host = plugin.getHostSpecByStrategy(role, strategy);
            if (host != null) {
              return host;
            }
          } catch (UnsupportedOperationException e) {
            // This plugin does not support the provided strategy, ignore the exception and move on
          }
        }
      }

      throw new UnsupportedOperationException(
          "The driver does not support the requested host selection strategy: " + strategy);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new SQLException(e);
    }
  }

  public void initHostProvider(
      final String driverProtocol,
      final String initialUrl,
      final Properties props,
      final HostListProviderService hostListProviderService)
      throws SQLException {

    executeWithSubscribedPlugins(
        INIT_HOST_PROVIDER_METHOD,
        (PluginPipeline<Void, SQLException>)
            (plugin, func) -> {
              plugin.initHostProvider(
                  driverProtocol, initialUrl, props, hostListProviderService, func);
              return null;
            },
        () -> {
          throw new SQLException("Shouldn't be called.");
        });
  }

  public EnumSet<OldConnectionSuggestedAction> notifyConnectionChanged(
      @NonNull final EnumSet<NodeChangeOptions> changes,
      @Nullable final ConnectionPlugin skipNotificationForThisPlugin) {

    final EnumSet<OldConnectionSuggestedAction> result =
        EnumSet.noneOf(OldConnectionSuggestedAction.class);

    notifySubscribedPlugins(
        NOTIFY_CONNECTION_CHANGED_METHOD,
        (plugin, func) -> {
          final OldConnectionSuggestedAction pluginOpinion = plugin.notifyConnectionChanged(changes);
          result.add(pluginOpinion);
          return null;
        },
        skipNotificationForThisPlugin);

    return result;
  }

  public void notifyNodeListChanged(@NonNull final Map<String, EnumSet<NodeChangeOptions>> changes) {

    notifySubscribedPlugins(
        NOTIFY_NODE_LIST_CHANGED_METHOD,
        (plugin, func) -> {
          plugin.notifyNodeListChanged(changes);
          return null;
        },
        null);
  }

  /**
   * Release all dangling resources held by the connection plugins associated with a single
   * connection.
   */
  public void releaseResources() {
    LOGGER.fine(() -> Messages.get("ConnectionPluginManager.releaseResources"));

    // This step allows all connection plugins a chance to clean up any dangling resources or
    // perform any
    // last tasks before shutting down.

    this.plugins.forEach(
        (plugin) -> {
          if (plugin instanceof CanReleaseResources) {
            ((CanReleaseResources) plugin).releaseResources();
          }
        });
  }

  private interface PluginPipeline<T, E extends Exception> {

    T call(final @NonNull ConnectionPlugin plugin, final @Nullable JdbcCallable<T, E> jdbcMethodFunc) throws E;
  }

  private interface PluginChainJdbcCallable<T, E extends Exception> {

    T call(final @NonNull PluginPipeline<T, E> pipelineFunc, final @NonNull JdbcCallable<T, E> jdbcMethodFunc) throws E;
  }
}
