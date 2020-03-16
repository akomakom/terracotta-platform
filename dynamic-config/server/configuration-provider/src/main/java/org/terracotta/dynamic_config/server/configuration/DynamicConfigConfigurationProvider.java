/*
 * Copyright Terracotta, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terracotta.dynamic_config.server.configuration;

import com.tc.exception.TCServerRestartException;
import com.tc.exception.TCShutdownServerException;
import com.tc.exception.ZapDirtyDbServerNodeException;
import com.tc.server.ServiceClassLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.configuration.ConfigurationProvider;
import org.terracotta.diagnostic.server.DefaultDiagnosticServices;
import org.terracotta.diagnostic.server.api.DiagnosticServices;
import org.terracotta.dynamic_config.api.model.NodeContext;
import org.terracotta.dynamic_config.api.service.ClusterFactory;
import org.terracotta.dynamic_config.api.service.DynamicConfigService;
import org.terracotta.dynamic_config.api.service.IParameterSubstitutor;
import org.terracotta.dynamic_config.api.service.TopologyService;
import org.terracotta.dynamic_config.server.api.ConfigChangeHandlerManager;
import org.terracotta.dynamic_config.server.api.DynamicConfigEventService;
import org.terracotta.dynamic_config.server.api.DynamicConfigExtension;
import org.terracotta.dynamic_config.server.api.LicenseParser;
import org.terracotta.dynamic_config.server.api.LicenseParserDiscovery;
import org.terracotta.dynamic_config.server.api.PathResolver;
import org.terracotta.dynamic_config.server.configuration.service.ConfigChangeHandlerManagerImpl;
import org.terracotta.dynamic_config.server.configuration.service.DynamicConfigServiceImpl;
import org.terracotta.dynamic_config.server.configuration.service.NomadServerManager;
import org.terracotta.dynamic_config.server.configuration.service.ParameterSubstitutor;
import org.terracotta.dynamic_config.server.configuration.startup.CommandLineProcessor;
import org.terracotta.dynamic_config.server.configuration.startup.ConfigurationGeneratorVisitor;
import org.terracotta.dynamic_config.server.configuration.startup.CustomJCommander;
import org.terracotta.dynamic_config.server.configuration.startup.DynamicConfigConfiguration;
import org.terracotta.dynamic_config.server.configuration.startup.MainCommandLineProcessor;
import org.terracotta.dynamic_config.server.configuration.startup.Options;
import org.terracotta.dynamic_config.server.configuration.sync.DynamicConfigSyncData;
import org.terracotta.dynamic_config.server.configuration.sync.DynamicConfigurationPassiveSync;
import org.terracotta.dynamic_config.server.configuration.sync.Require;
import org.terracotta.nomad.server.NomadException;
import org.terracotta.nomad.server.NomadServer;
import org.terracotta.nomad.server.UpgradableNomadServer;

import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

import static java.lang.System.lineSeparator;
import static org.terracotta.dynamic_config.server.configuration.sync.Require.RESTART_REQUIRED;
import static org.terracotta.dynamic_config.server.configuration.sync.Require.ZAP_REQUIRED;

public class DynamicConfigConfigurationProvider implements ConfigurationProvider {
  private static final Logger LOGGER = LoggerFactory.getLogger(DynamicConfigConfigurationProvider.class);

  private volatile DynamicConfigurationPassiveSync dynamicConfigurationPassiveSync;
  private volatile DynamicConfigConfiguration configuration;

  @Override
  public void initialize(List<String> args) {
    withMyClassLoader(() -> {
      ClassLoader serviceClassLoader = getServiceClassLoader();

      // initialize the enhanced communication layer on top of diagnostic port
      DefaultDiagnosticServices diagnosticServices = new DefaultDiagnosticServices();
      diagnosticServices.init();

      // substitution service from placeholders
      ParameterSubstitutor parameterSubstitutor = new ParameterSubstitutor();

      // service containing the list of dynamic change handlers per settings
      ConfigChangeHandlerManager configChangeHandlerManager = new ConfigChangeHandlerManagerImpl();

      // service used to create a topology from input CLI or config file
      ClusterFactory clusterFactory = new ClusterFactory();

      // This path resolver is used when converting a model to XML.
      // It makes sure to resolve any relative path to absolute ones based on the working directory.
      // This is necessary because if some relative path ends up in the XML exactly like they are in the model,
      // then platform will rebase these paths relatively to the config XML file which is inside a sub-folder in
      // the config repository: repository/config.
      // So this has the effect of putting all defined directories inside such as repository/config/logs, repository/config/user-data, repository/metadata, etc
      // That is why we need to force the resolving within the XML relatively to the user directory.
      PathResolver userDirResolver = new PathResolver(Paths.get("%(user.dir)"), parameterSubstitutor::substitute);

      // optional service enabling license parsing
      LicenseParser licenseParser = new LicenseParserDiscovery(serviceClassLoader).find().orElseGet(LicenseParser::unsupported);

      // Service used to manage and initialize the Nomad 2PC system
      NomadServerManager nomadServerManager = new NomadServerManager(parameterSubstitutor, configChangeHandlerManager, licenseParser);

      // Configuration generator class
      // Initialized when processing the CLI depending oin the user input, and called to generate a configuration
      ConfigurationGeneratorVisitor configurationGeneratorVisitor = new ConfigurationGeneratorVisitor(parameterSubstitutor, nomadServerManager, serviceClassLoader, userDirResolver);

      // CLI parsing
      Options options = parseCommandLineOrExit(args);

      // processors for the CLI
      CommandLineProcessor commandLineProcessor = new MainCommandLineProcessor(options, clusterFactory, configurationGeneratorVisitor, parameterSubstitutor);

      // process the CLI and initialize the Nomad system and ConfigurationGeneratorVisitor
      commandLineProcessor.process();

      // retrieve initialized services
      UpgradableNomadServer<NodeContext> nomadServer = nomadServerManager.getNomadServer();
      DynamicConfigServiceImpl dynamicConfigService = nomadServerManager.getDynamicConfigService();

      // initialize the passive sync service
      dynamicConfigurationPassiveSync = new DynamicConfigurationPassiveSync(
          nomadServerManager.getConfiguration().orElse(null),
          nomadServer,
          dynamicConfigService,
          () -> dynamicConfigService.getLicenseContent().orElse(null));

      // generate the configuration wrapper
      configuration = configurationGeneratorVisitor.generateConfiguration();

      //  exposes services through org.terracotta.entity.PlatformConfiguration
      configuration.registerExtendedConfiguration(DiagnosticServices.class, diagnosticServices);
      configuration.registerExtendedConfiguration(IParameterSubstitutor.class, parameterSubstitutor);
      configuration.registerExtendedConfiguration(ConfigChangeHandlerManager.class, configChangeHandlerManager);
      configuration.registerExtendedConfiguration(DynamicConfigEventService.class, dynamicConfigService);
      configuration.registerExtendedConfiguration(TopologyService.class, dynamicConfigService);
      configuration.registerExtendedConfiguration(DynamicConfigService.class, dynamicConfigService);
      configuration.registerExtendedConfiguration(NomadServer.class, nomadServer);
      configuration.registerExtendedConfiguration(PathResolver.class, userDirResolver);

      // discover the dynamic config extensions (offheap, dataroot, lease, etc)
      configuration.discoverExtensions();

      // Expose some services through diagnostic port
      diagnosticServices.register(TopologyService.class, dynamicConfigService);
      diagnosticServices.register(DynamicConfigService.class, dynamicConfigService);
      diagnosticServices.register(NomadServer.class, nomadServer);

      LOGGER.info("Startup configuration of the node: {}{}{}", lineSeparator(), lineSeparator(), configuration);
    });
  }

  @Override
  public DynamicConfigConfiguration getConfiguration() {
    return configuration;
  }

  @Override
  public String getConfigurationParamsDescription() {
    throw new UnsupportedOperationException("Please start the node by using the new script start-node.sh or start-node.bat");
  }

  @Override
  public byte[] getSyncData() {
    return dynamicConfigurationPassiveSync != null ? dynamicConfigurationPassiveSync.getSyncData().encode() : new byte[0];
  }

  @Override
  public void sync(byte[] bytes) {
    if (dynamicConfigurationPassiveSync != null) {

      Set<Require> requires;
      try {
        requires = dynamicConfigurationPassiveSync.sync(DynamicConfigSyncData.decode(bytes));
      } catch (NomadException | RuntimeException e) {
        // shutdown the server because of a unrecoverable error
        throw new TCShutdownServerException("Shutdown because of sync failure: " + e.getMessage(), e);
      }

      if (requires.contains(RESTART_REQUIRED)) {
        if (requires.contains(ZAP_REQUIRED)) {
          throw new ZapDirtyDbServerNodeException("Zapping server");
        } else {
          throw new TCServerRestartException("Restarting server");
        }
      }
    }
  }

  @Override
  public void close() {
    // Do nothing
  }

  private void withMyClassLoader(Runnable runnable) {
    ClassLoader classLoader = getClass().getClassLoader();
    ClassLoader oldloader = Thread.currentThread().getContextClassLoader();
    Thread.currentThread().setContextClassLoader(classLoader);
    try {
      runnable.run();
    } finally {
      Thread.currentThread().setContextClassLoader(oldloader);
    }
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private ClassLoader getServiceClassLoader() {
    return new ServiceClassLoader(getClass().getClassLoader(),
        DynamicConfigExtension.class,
        LicenseParser.class);
  }

  private static Options parseCommandLineOrExit(List<String> args) {
    Options options = new Options();
    CustomJCommander jCommander = new CustomJCommander("start-tc-server", options);
    jCommander.parse(args.toArray(new String[0]));
    if (options.isHelp()) {
      jCommander.usage();
      System.exit(0);
    }
    options.process(jCommander);
    return options;
  }

}
