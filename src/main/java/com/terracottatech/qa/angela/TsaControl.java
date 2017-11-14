package com.terracottatech.qa.angela;

import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.Ignition;
import org.apache.ignite.cluster.ClusterGroup;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.lang.IgniteCallable;
import org.apache.ignite.lang.IgniteRunnable;
import org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.TcpDiscoveryVmIpFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.terracottatech.qa.angela.kit.KitManager;
import com.terracottatech.qa.angela.kit.TerracottaInstall;
import com.terracottatech.qa.angela.kit.TerracottaServerInstance;
import com.terracottatech.qa.angela.kit.distribution.DistributionController;
import com.terracottatech.qa.angela.tcconfig.ClusterToolConfig;
import com.terracottatech.qa.angela.tcconfig.TcConfig;
import com.terracottatech.qa.angela.tcconfig.TerracottaServer;
import com.terracottatech.qa.angela.topology.Topology;

import java.io.File;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static com.terracottatech.qa.angela.kit.TerracottaServerInstance.TerracottaServerState.STARTED_AS_ACTIVE;
import static com.terracottatech.qa.angela.kit.TerracottaServerInstance.TerracottaServerState.STARTED_AS_PASSIVE;

/**
 * @author Aurelien Broszniowski
 */

public class TsaControl {

  private final static Logger logger = LoggerFactory.getLogger(TsaControl.class);

  private Topology topology;
  private Map<String, TerracottaServerInstance> terracottaServerInstances;
  private KitManager kitManager;
  private DistributionController distributionController;

  private ClusterToolConfig clusterToolConfig;
  Map<String, TerracottaServerInstance.TerracottaServerState> states = new HashMap<>();

  public static final long TIMEOUT = 30000;

  private volatile Ignite ignite;

  public void init() {
    if (topology == null) {
      throw new IllegalArgumentException("You need to pass a topology");
    }

    if (ignite != null) {
      throw new IllegalStateException("You can not init TsaControl twice");
    }

    IgniteConfiguration cfg = new IgniteConfiguration();

    TcpDiscoverySpi spi = new TcpDiscoverySpi();
    TcpDiscoveryVmIpFinder ipFinder = new TcpDiscoveryVmIpFinder();
    ipFinder.setAddresses(topology.getServersHostnames());
    spi.setIpFinder(ipFinder);

    cfg.setDiscoverySpi(spi);
    cfg.setClientMode(true);
    cfg.setPeerClassLoadingEnabled(true);
    cfg.setIgniteInstanceName(UUID.randomUUID().toString());
    cfg.setPeerClassLoadingEnabled(true);

    ignite = Ignition.start(cfg);

    TcConfig[] tcConfigs = topology.getTcConfigs();
    for (int tcConfigIndex = 0; tcConfigIndex < tcConfigs.length; tcConfigIndex++) {
      final TcConfig tcConfig = tcConfigs[tcConfigIndex];
      for (String serverSymbolicName : tcConfig.getServers().keySet()) {
        TerracottaServer terracottaServer = topology.getServers().get(serverSymbolicName);

        final int finalTcConfigIndex = tcConfigIndex;
        executeRemotely(terracottaServer.getHostname(), (IgniteRunnable)() -> {

          IgniteCache<String, TerracottaInstall> kitsInstalls = ignite.getOrCreateCache("installs");
          if (kitsInstalls.containsKey(topology.getId())) {
            System.out.println("Already exists");
          } else {
            boolean offline = Boolean.parseBoolean(System.getProperty("offline", "false"));  //TODO :get offline flag
            logger.info("Installing the kit");
            File kitDir = kitManager.installKit(clusterToolConfig.getLicenseConfig(), offline);

            logger.info("Installing the tc-configs");
            tcConfig.updateLogsLocation(kitDir, finalTcConfigIndex);
            tcConfig.writeTcConfigFile(kitDir);

            kitsInstalls.put(topology.getId(), new TerracottaInstall(kitDir, topology));

            System.out.println("kitDir = " + kitDir.getAbsolutePath());
//        new TerracottaInstall(kitDir, clusterConfig, managementConfig, clusterToolConfig, clusterConfig.getVersion(), agent
//            .getNetworkController())
          }
        });
      }
    }
  }

  private void executeRemotely(final String hostname, final IgniteRunnable runnable) {
    logger.info("Executing command on {}", hostname);
    ClusterGroup location = ignite.cluster().forAttribute("nodename", hostname);
    ignite.compute(location).broadcast(runnable);
  }

  private <R> R executeRemotely(final String hostname, final IgniteCallable<R> callable) {
    logger.info("Executing command on {}", hostname);
    ClusterGroup location = ignite.cluster().forAttribute("nodename", hostname);
    Collection<R> results = ignite.compute(location).broadcast(callable);
    if (results.size() != 1) {
      throw new IllegalStateException("Multiple response for the Execution on " + hostname);
    }
    return results.iterator().next();
  }

  public void close() {
    IgniteCache<String, String> kitsInstalls = ignite.getOrCreateCache("installs");
    kitsInstalls.remove(topology.getId());

    ignite.close();
  }


  public TsaControl withTopology(Topology topology) {
    this.topology = topology;
    this.distributionController = topology.createDistributionController();
    this.kitManager = topology.createKitManager();
    this.terracottaServerInstances = topology.createTerracottaServerInstances();
    return this;
  }

  public TsaControl withClusterToolConfig(final ClusterToolConfig clusterToolConfig) {
    this.clusterToolConfig = clusterToolConfig;
    return this;
  }

  public void start(final TerracottaServer terracottaServer) {
    startServer(terracottaServer);
  }

  public void startAll() {
    startAll(TIMEOUT);
  }

  public void startAll(long timeout) {
    TcConfig[] tcConfigs = topology.getTcConfigs();
    for (int tcConfigIndex = 0; tcConfigIndex < tcConfigs.length; tcConfigIndex++) {
      final TcConfig tcConfig = tcConfigs[tcConfigIndex];
      for (String serverSymbolicName : tcConfig.getServers().keySet()) {
        TerracottaServer terracottaServer = topology.getServers().get(serverSymbolicName);
        start(terracottaServer);
      }
    }
  }

  private void startServer(final TerracottaServer terracottaServer) {
    TerracottaServerInstance instance = terracottaServerInstances.get(terracottaServer.getServerSymbolicName());
    if (instance == null) {
      throw new IllegalStateException("TsaControl missing calls to withTopology() and init().");
    }

    TerracottaServerInstance.TerracottaServerState currentState = instance.getState();
    if (currentState == STARTED_AS_ACTIVE || currentState == STARTED_AS_PASSIVE) {
      return;
    }

    IgniteCache<String, TerracottaInstall> kitsInstalls = ignite.getOrCreateCache("installs");
    TerracottaInstall terracottaInstall = kitsInstalls.get(topology.getId());

    TerracottaServerInstance.TerracottaServerState state = executeRemotely(terracottaServer.getHostname(),
        (IgniteCallable<TerracottaServerInstance.TerracottaServerState>)() ->
            distributionController.start(terracottaServer, topology, terracottaInstall.getLocation()));

    instance.setState(state);

    System.out.println("++++++++++++++++++++++++++++++ state " +state);

  }

}
