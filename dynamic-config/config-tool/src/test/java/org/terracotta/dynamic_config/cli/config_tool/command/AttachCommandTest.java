/*
 * Copyright (c) 2011-2019 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA, and/or its subsidiaries and/or its affiliates and/or their licensors.
 * Use, reproduction, transfer, publication or disclosure is prohibited except as specifically provided for in your License Agreement with Software AG.
 */
package org.terracotta.dynamic_config.cli.config_tool.command;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.terracotta.common.struct.MemoryUnit;
import org.terracotta.dynamic_config.api.model.Cluster;
import org.terracotta.dynamic_config.api.model.Node;
import org.terracotta.dynamic_config.api.model.NodeContext;
import org.terracotta.dynamic_config.api.model.Stripe;
import org.terracotta.dynamic_config.api.service.DynamicConfigService;
import org.terracotta.json.Json;

import java.nio.file.Paths;
import java.util.List;

import static java.net.InetSocketAddress.createUnresolved;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.terracotta.diagnostic.common.LogicalServerState.STARTING;
import static org.terracotta.dynamic_config.cli.config_tool.converter.OperationType.NODE;
import static org.terracotta.dynamic_config.cli.config_tool.converter.OperationType.STRIPE;
import static org.terracotta.testing.ExceptionMatcher.throwing;

/**
 * @author Mathieu Carbou
 */
public class AttachCommandTest extends TopologyCommandTest<AttachCommand> {

  Node node0 = Node.newDefaultNode("node0", "localhost", 9410)
      .setOffheapResource("foo", 1, MemoryUnit.GB)
      .setDataDir("cache", Paths.get("/data/cache1"));

  Node node1 = Node.newDefaultNode("node1", "localhost", 9411)
      .setOffheapResource("foo", 1, MemoryUnit.GB)
      .setOffheapResource("bar", 1, MemoryUnit.GB)
      .setDataDir("cache", Paths.get("/data/cache2"));

  Node node2 = Node.newDefaultNode("node2", "localhost", 9412)
      .setOffheapResource("foo", 1, MemoryUnit.GB)
      .setOffheapResource("bar", 1, MemoryUnit.GB)
      .setDataDir("cache", Paths.get("/data/cache3"));

  @Captor ArgumentCaptor<Cluster> newCluster;

  @Override
  protected AttachCommand newTopologyCommand() {
    return new AttachCommand();
  }

  @Override
  @Before
  public void setUp() throws Exception {
    super.setUp();
    NodeContext nodeContext0 = new NodeContext(new Cluster(new Stripe(node0)), node0.getNodeAddress());
    NodeContext nodeContext1 = new NodeContext(new Cluster(new Stripe(node1)), node1.getNodeAddress());

    when(topologyServiceMock("localhost", 9410).getUpcomingNodeContext()).thenReturn(nodeContext0);
    when(topologyServiceMock("localhost", 9411).getUpcomingNodeContext()).thenReturn(nodeContext1);

    when(topologyServiceMock("localhost", 9410).getRuntimeNodeContext()).thenReturn(nodeContext0);
    when(topologyServiceMock("localhost", 9411).getRuntimeNodeContext()).thenReturn(nodeContext1);

    when(diagnosticServiceMock("localhost", 9410).getLogicalServerState()).thenReturn(STARTING);
  }

  @Test
  public void test_attach_node_validation_fail_src_activated() {
    when(topologyServiceMock("localhost", 9411).isActivated()).thenReturn(true);

    TopologyCommand command = newCommand()
        .setOperationType(NODE)
        .setDestination("localhost", 9410)
        .setSource(createUnresolved("localhost", 9411));

    assertThat(
        command::validate,
        is(throwing(instanceOf(IllegalArgumentException.class)).andMessage(is(containsString("cannot be attached since it is part of an existing cluster")))));
  }

  @Test
  public void test_attach_node_validation_fail_src_multiNodeStripe() {
    NodeContext nodeContext = new NodeContext(new Cluster(new Stripe(node1, node2)), node1.getNodeAddress());
    when(topologyServiceMock("localhost", 9411).getUpcomingNodeContext()).thenReturn(nodeContext);

    TopologyCommand command = newCommand()
        .setOperationType(NODE)
        .setDestination("localhost", 9410)
        .setSource(createUnresolved("localhost", 9411));

    assertThat(
        command::validate,
        is(throwing(instanceOf(IllegalArgumentException.class)).andMessage(is(containsString("is part of a stripe containing more than 1 nodes")))));
  }

  @Test
  public void test_attach_node_ok() {
    DynamicConfigService mock10 = dynamicConfigServiceMock("localhost", 9410);
    DynamicConfigService mock11 = dynamicConfigServiceMock("localhost", 9411);

    TopologyCommand command = newCommand()
        .setOperationType(NODE)
        .setDestination("localhost", 9410)
        .setSource(createUnresolved("localhost", 9411));
    command.validate();
    command.run();

    // capture the new topology set calls
    verify(mock10).setUpcomingCluster(newCluster.capture());
    verify(mock11).setUpcomingCluster(newCluster.capture());

    List<Cluster> allValues = newCluster.getAllValues();
    assertThat(allValues, hasSize(2));
    assertThat(allValues.get(0), is(equalTo(allValues.get(1))));
    assertThat(allValues.get(0), is(equalTo(Json.parse(getClass().getResource("/cluster1.json"), Cluster.class))));

    Cluster cluster = allValues.get(0);
    assertThat(cluster.getStripes(), hasSize(1));
    assertThat(cluster.getNodeAddresses(), hasSize(2));
  }

  @Test
  public void test_attach_stripe_validation_fail_src_activated() {
    when(topologyServiceMock("localhost", 9411).isActivated()).thenReturn(true);

    TopologyCommand command = newCommand()
        .setOperationType(STRIPE)
        .setDestination("localhost", 9410)
        .setSource(createUnresolved("localhost", 9411));

    assertThat(
        command::validate,
        is(throwing(instanceOf(IllegalArgumentException.class)).andMessage(is(containsString("cannot be attached since it is part of an existing cluster")))));
  }

  @Test
  public void test_attach_stripe_validation_fail_src_multiStripeCluster() {
    NodeContext nodeContext = new NodeContext(new Cluster(new Stripe(node1), new Stripe(node2)), node1.getNodeAddress());
    when(topologyServiceMock("localhost", 9411).getUpcomingNodeContext()).thenReturn(nodeContext);

    TopologyCommand command = newCommand()
        .setOperationType(STRIPE)
        .setDestination("localhost", 9410)
        .setSource(createUnresolved("localhost", 9411));

    assertThat(
        command::validate,
        is(throwing(instanceOf(IllegalArgumentException.class)).andMessage(is(containsString("is part of a cluster containing more than 1 stripes")))));
  }

  @Test
  public void test_attach_stripe_ok() {
    DynamicConfigService mock10 = dynamicConfigServiceMock("localhost", 9410);
    DynamicConfigService mock11 = dynamicConfigServiceMock("localhost", 9411);

    TopologyCommand command = newCommand()
        .setOperationType(STRIPE)
        .setDestination("localhost", 9410)
        .setSource(createUnresolved("localhost", 9411));
    command.validate();
    command.run();

    // capture the new topology set calls
    verify(mock10).setUpcomingCluster(newCluster.capture());
    verify(mock11).setUpcomingCluster(newCluster.capture());

    List<Cluster> allValues = newCluster.getAllValues();
    assertThat(allValues, hasSize(2));
    assertThat(allValues.get(0), is(equalTo(allValues.get(1))));
    assertThat(allValues.get(0), is(equalTo(Json.parse(getClass().getResource("/cluster2.json"), Cluster.class))));

    Cluster cluster = allValues.get(0);
    assertThat(cluster.getStripes(), hasSize(2));
    assertThat(cluster.getStripes().get(0).getNodes(), hasSize(1));
    assertThat(cluster.getStripes().get(1).getNodes(), hasSize(1));
    assertThat(cluster.getNodeAddresses(), hasSize(2));
  }
}