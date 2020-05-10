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
package org.terracotta.dynamic_config.entity.topology.common;

import org.junit.Test;
import org.terracotta.dynamic_config.api.model.Cluster;
import org.terracotta.dynamic_config.api.model.Configuration;
import org.terracotta.dynamic_config.api.model.License;
import org.terracotta.dynamic_config.api.model.Node;
import org.terracotta.dynamic_config.api.model.Stripe;
import org.terracotta.entity.MessageCodecException;

import java.time.LocalDate;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.terracotta.dynamic_config.entity.topology.common.DynamicTopologyEntityMessage.Type.EVENT_NODE_ADDITION;
import static org.terracotta.dynamic_config.entity.topology.common.DynamicTopologyEntityMessage.Type.EVENT_NODE_REMOVAL;
import static org.terracotta.dynamic_config.entity.topology.common.DynamicTopologyEntityMessage.Type.EVENT_SETTING_CHANGED;
import static org.terracotta.dynamic_config.entity.topology.common.DynamicTopologyEntityMessage.Type.REQ_HAS_INCOMPLETE_CHANGE;
import static org.terracotta.dynamic_config.entity.topology.common.DynamicTopologyEntityMessage.Type.REQ_LICENSE;
import static org.terracotta.dynamic_config.entity.topology.common.DynamicTopologyEntityMessage.Type.REQ_MUST_BE_RESTARTED;
import static org.terracotta.dynamic_config.entity.topology.common.DynamicTopologyEntityMessage.Type.REQ_RUNTIME_CLUSTER;
import static org.terracotta.dynamic_config.entity.topology.common.DynamicTopologyEntityMessage.Type.REQ_UPCOMING_CLUSTER;

/**
 * @author Mathieu Carbou
 */
public class DynamicTopologyMessageCodecTest {
  @Test
  public void test_encode_decode() throws MessageCodecException {
    Node node = Node.newDefaultNode("foo", "localhost", 9410);
    Cluster cluster = Cluster.newDefaultCluster("bar", new Stripe(node));

    test(new DynamicTopologyEntityMessage(REQ_LICENSE, null));
    test(new DynamicTopologyEntityMessage(REQ_LICENSE, new License(emptyMap(), LocalDate.of(2020, 1, 1))));
    test(new DynamicTopologyEntityMessage(REQ_LICENSE, new License(singletonMap("offheap", 1024L), LocalDate.of(2020, 1, 1))));

    test(new DynamicTopologyEntityMessage(REQ_MUST_BE_RESTARTED, true));
    test(new DynamicTopologyEntityMessage(REQ_HAS_INCOMPLETE_CHANGE, true));

    test(new DynamicTopologyEntityMessage(REQ_RUNTIME_CLUSTER, cluster));
    test(new DynamicTopologyEntityMessage(REQ_UPCOMING_CLUSTER, cluster));

    test(new DynamicTopologyEntityMessage(EVENT_NODE_ADDITION, asList(1, node)));
    test(new DynamicTopologyEntityMessage(EVENT_NODE_REMOVAL, asList(1, node)));

    test(new DynamicTopologyEntityMessage(EVENT_SETTING_CHANGED, asList(Configuration.valueOf("cluster-name=foo"), cluster)));
  }

  private static void test(DynamicTopologyEntityMessage message) throws MessageCodecException {
    DynamicTopologyMessageCodec codec = new DynamicTopologyMessageCodec();
    byte[] bytes = codec.encodeMessage(message);
    DynamicTopologyEntityMessage decoded = codec.decodeMessage(bytes);
    assertThat(message, is(equalTo(decoded)));
  }
}