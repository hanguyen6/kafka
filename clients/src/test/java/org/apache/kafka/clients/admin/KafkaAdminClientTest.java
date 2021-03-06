/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.clients.admin;

import org.apache.kafka.clients.NodeApiVersions;
import org.apache.kafka.clients.admin.DeleteAclsResult.FilterResults;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.acl.AccessControlEntry;
import org.apache.kafka.common.acl.AccessControlEntryFilter;
import org.apache.kafka.common.acl.AclBinding;
import org.apache.kafka.common.acl.AclBindingFilter;
import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.acl.AclPermissionType;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.LeaderNotAvailableException;
import org.apache.kafka.common.errors.NotLeaderForPartitionException;
import org.apache.kafka.common.errors.OffsetOutOfRangeException;
import org.apache.kafka.common.errors.SecurityDisabledException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.requests.ApiError;
import org.apache.kafka.common.requests.CreateAclsResponse;
import org.apache.kafka.common.requests.CreateAclsResponse.AclCreationResponse;
import org.apache.kafka.common.requests.CreatePartitionsResponse;
import org.apache.kafka.common.requests.CreateTopicsResponse;
import org.apache.kafka.common.requests.DeleteAclsResponse;
import org.apache.kafka.common.requests.DeleteAclsResponse.AclDeletionResult;
import org.apache.kafka.common.requests.DeleteAclsResponse.AclFilterResponse;
import org.apache.kafka.common.requests.DeleteRecordsResponse;
import org.apache.kafka.common.requests.DescribeAclsResponse;
import org.apache.kafka.common.requests.DescribeConfigsResponse;
import org.apache.kafka.common.requests.MetadataResponse;
import org.apache.kafka.common.resource.Resource;
import org.apache.kafka.common.resource.ResourceFilter;
import org.apache.kafka.common.resource.ResourceType;
import org.apache.kafka.common.utils.MockTime;
import org.apache.kafka.test.TestCondition;
import org.apache.kafka.test.TestUtils;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static java.util.Arrays.asList;
import static org.apache.kafka.common.requests.ResourceType.BROKER;
import static org.apache.kafka.common.requests.ResourceType.TOPIC;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;


/**
 * A unit test for KafkaAdminClient.
 *
 * See AdminClientIntegrationTest for an integration test.
 */
public class KafkaAdminClientTest {
    private static final Logger log = LoggerFactory.getLogger(KafkaAdminClientTest.class);

    @Rule
    final public Timeout globalTimeout = Timeout.millis(120000);

    @Test
    public void testGetOrCreateListValue() {
        Map<String, List<String>> map = new HashMap<>();
        List<String> fooList = KafkaAdminClient.getOrCreateListValue(map, "foo");
        assertNotNull(fooList);
        fooList.add("a");
        fooList.add("b");
        List<String> fooList2 = KafkaAdminClient.getOrCreateListValue(map, "foo");
        assertEquals(fooList, fooList2);
        assertTrue(fooList2.contains("a"));
        assertTrue(fooList2.contains("b"));
        List<String> barList = KafkaAdminClient.getOrCreateListValue(map, "bar");
        assertNotNull(barList);
        assertTrue(barList.isEmpty());
    }

    @Test
    public void testCalcTimeoutMsRemainingAsInt() {
        assertEquals(0, KafkaAdminClient.calcTimeoutMsRemainingAsInt(1000, 1000));
        assertEquals(100, KafkaAdminClient.calcTimeoutMsRemainingAsInt(1000, 1100));
        assertEquals(Integer.MAX_VALUE, KafkaAdminClient.calcTimeoutMsRemainingAsInt(0, Long.MAX_VALUE));
        assertEquals(Integer.MIN_VALUE, KafkaAdminClient.calcTimeoutMsRemainingAsInt(Long.MAX_VALUE, 0));
    }

    @Test
    public void testPrettyPrintException() {
        assertEquals("Null exception.", KafkaAdminClient.prettyPrintException(null));
        assertEquals("TimeoutException", KafkaAdminClient.prettyPrintException(new TimeoutException()));
        assertEquals("TimeoutException: The foobar timed out.",
                KafkaAdminClient.prettyPrintException(new TimeoutException("The foobar timed out.")));
    }

    private static Map<String, Object> newStrMap(String... vals) {
        Map<String, Object> map = new HashMap<>();
        map.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:8121");
        map.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "1000");
        if (vals.length % 2 != 0) {
            throw new IllegalStateException();
        }
        for (int i = 0; i < vals.length; i += 2) {
            map.put(vals[i], vals[i + 1]);
        }
        return map;
    }

    private static AdminClientConfig newConfMap(String... vals) {
        return new AdminClientConfig(newStrMap(vals));
    }

    @Test
    public void testGenerateClientId() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            String id = KafkaAdminClient.generateClientId(newConfMap(AdminClientConfig.CLIENT_ID_CONFIG, ""));
            assertTrue("Got duplicate id " + id, !ids.contains(id));
            ids.add(id);
        }
        assertEquals("myCustomId",
                KafkaAdminClient.generateClientId(newConfMap(AdminClientConfig.CLIENT_ID_CONFIG, "myCustomId")));
    }

    private static AdminClientUnitTestEnv mockClientEnv(String... configVals) {
        HashMap<Integer, Node> nodes = new HashMap<>();
        nodes.put(0, new Node(0, "localhost", 8121));
        nodes.put(1, new Node(1, "localhost", 8122));
        nodes.put(2, new Node(2, "localhost", 8123));
        Cluster cluster = new Cluster("mockClusterId", nodes.values(),
                Collections.<PartitionInfo>emptySet(), Collections.<String>emptySet(),
                Collections.<String>emptySet(), nodes.get(0));
        return new AdminClientUnitTestEnv(cluster, configVals);
    }

    @Test
    public void testCloseAdminClient() throws Exception {
        try (AdminClientUnitTestEnv env = mockClientEnv()) {
        }
    }

    private static void assertFutureError(Future<?> future, Class<? extends Throwable> exceptionClass)
            throws InterruptedException {
        try {
            future.get();
            fail("Expected a " + exceptionClass.getSimpleName() + " exception, but got success.");
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            assertEquals("Expected a " + exceptionClass.getSimpleName() + " exception, but got " +
                            cause.getClass().getSimpleName(),
                    exceptionClass, cause.getClass());
        }
    }

    /**
     * Test that the client properly times out when we don't receive any metadata.
     */
    @Test
    public void testTimeoutWithoutMetadata() throws Exception {
        try (AdminClientUnitTestEnv env = mockClientEnv(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "10")) {
            env.kafkaClient().setNodeApiVersions(NodeApiVersions.create());
            env.kafkaClient().setNode(new Node(0, "localhost", 8121));
            env.kafkaClient().prepareResponse(new CreateTopicsResponse(Collections.singletonMap("myTopic", new ApiError(Errors.NONE, ""))));
            KafkaFuture<Void> future = env.adminClient().createTopics(
                    Collections.singleton(new NewTopic("myTopic", Collections.singletonMap(Integer.valueOf(0), asList(new Integer[]{0, 1, 2})))),
                    new CreateTopicsOptions().timeoutMs(1000)).all();
            assertFutureError(future, TimeoutException.class);
        }
    }

    @Test
    public void testCreateTopics() throws Exception {
        try (AdminClientUnitTestEnv env = mockClientEnv()) {
            env.kafkaClient().setNodeApiVersions(NodeApiVersions.create());
            env.kafkaClient().prepareMetadataUpdate(env.cluster(), Collections.<String>emptySet());
            env.kafkaClient().setNode(env.cluster().controller());
            env.kafkaClient().prepareResponse(new CreateTopicsResponse(Collections.singletonMap("myTopic", new ApiError(Errors.NONE, ""))));
            KafkaFuture<Void> future = env.adminClient().createTopics(
                    Collections.singleton(new NewTopic("myTopic", Collections.singletonMap(Integer.valueOf(0), asList(new Integer[]{0, 1, 2})))),
                    new CreateTopicsOptions().timeoutMs(10000)).all();
            future.get();
        }
    }

    private static final AclBinding ACL1 = new AclBinding(new Resource(ResourceType.TOPIC, "mytopic3"),
        new AccessControlEntry("User:ANONYMOUS", "*", AclOperation.DESCRIBE, AclPermissionType.ALLOW));
    private static final AclBinding ACL2 = new AclBinding(new Resource(ResourceType.TOPIC, "mytopic4"),
        new AccessControlEntry("User:ANONYMOUS", "*", AclOperation.DESCRIBE, AclPermissionType.DENY));
    private static final AclBindingFilter FILTER1 = new AclBindingFilter(new ResourceFilter(ResourceType.ANY, null),
        new AccessControlEntryFilter("User:ANONYMOUS", null, AclOperation.ANY, AclPermissionType.ANY));
    private static final AclBindingFilter FILTER2 = new AclBindingFilter(new ResourceFilter(ResourceType.ANY, null),
        new AccessControlEntryFilter("User:bob", null, AclOperation.ANY, AclPermissionType.ANY));

    @Test
    public void testDescribeAcls() throws Exception {
        try (AdminClientUnitTestEnv env = mockClientEnv()) {
            env.kafkaClient().setNodeApiVersions(NodeApiVersions.create());
            env.kafkaClient().prepareMetadataUpdate(env.cluster(), Collections.<String>emptySet());
            env.kafkaClient().setNode(env.cluster().controller());

            // Test a call where we get back ACL1 and ACL2.
            env.kafkaClient().prepareResponse(new DescribeAclsResponse(0, ApiError.NONE,
                    asList(ACL1, ACL2)));
            assertCollectionIs(env.adminClient().describeAcls(FILTER1).values().get(), ACL1, ACL2);

            // Test a call where we get back no results.
            env.kafkaClient().prepareResponse(new DescribeAclsResponse(0, ApiError.NONE,
                Collections.<AclBinding>emptySet()));
            assertTrue(env.adminClient().describeAcls(FILTER2).values().get().isEmpty());

            // Test a call where we get back an error.
            env.kafkaClient().prepareResponse(new DescribeAclsResponse(0,
                new ApiError(Errors.SECURITY_DISABLED, "Security is disabled"), Collections.<AclBinding>emptySet()));
            assertFutureError(env.adminClient().describeAcls(FILTER2).values(), SecurityDisabledException.class);
        }
    }

    @Test
    public void testCreateAcls() throws Exception {
        try (AdminClientUnitTestEnv env = mockClientEnv()) {
            env.kafkaClient().setNodeApiVersions(NodeApiVersions.create());
            env.kafkaClient().prepareMetadataUpdate(env.cluster(), Collections.<String>emptySet());
            env.kafkaClient().setNode(env.cluster().controller());

            // Test a call where we successfully create two ACLs.
            env.kafkaClient().prepareResponse(new CreateAclsResponse(0,
                asList(new AclCreationResponse(ApiError.NONE), new AclCreationResponse(ApiError.NONE))));
            CreateAclsResult results = env.adminClient().createAcls(asList(ACL1, ACL2));
            assertCollectionIs(results.values().keySet(), ACL1, ACL2);
            for (KafkaFuture<Void> future : results.values().values())
                future.get();
            results.all().get();

            // Test a call where we fail to create one ACL.
            env.kafkaClient().prepareResponse(new CreateAclsResponse(0, asList(
                new AclCreationResponse(new ApiError(Errors.SECURITY_DISABLED, "Security is disabled")),
                new AclCreationResponse(ApiError.NONE))
            ));
            results = env.adminClient().createAcls(asList(ACL1, ACL2));
            assertCollectionIs(results.values().keySet(), ACL1, ACL2);
            assertFutureError(results.values().get(ACL1), SecurityDisabledException.class);
            results.values().get(ACL2).get();
            assertFutureError(results.all(), SecurityDisabledException.class);
        }
    }

    @Test
    public void testDeleteAcls() throws Exception {
        try (AdminClientUnitTestEnv env = mockClientEnv()) {
            env.kafkaClient().setNodeApiVersions(NodeApiVersions.create());
            env.kafkaClient().prepareMetadataUpdate(env.cluster(), Collections.<String>emptySet());
            env.kafkaClient().setNode(env.cluster().controller());

            // Test a call where one filter has an error.
            env.kafkaClient().prepareResponse(new DeleteAclsResponse(0, asList(
                    new AclFilterResponse(asList(new AclDeletionResult(ACL1), new AclDeletionResult(ACL2))),
                    new AclFilterResponse(new ApiError(Errors.SECURITY_DISABLED, "No security"),
                            Collections.<AclDeletionResult>emptySet()))));
            DeleteAclsResult results = env.adminClient().deleteAcls(asList(FILTER1, FILTER2));
            Map<AclBindingFilter, KafkaFuture<FilterResults>> filterResults = results.values();
            FilterResults filter1Results = filterResults.get(FILTER1).get();
            assertEquals(null, filter1Results.values().get(0).exception());
            assertEquals(ACL1, filter1Results.values().get(0).binding());
            assertEquals(null, filter1Results.values().get(1).exception());
            assertEquals(ACL2, filter1Results.values().get(1).binding());
            assertFutureError(filterResults.get(FILTER2), SecurityDisabledException.class);
            assertFutureError(results.all(), SecurityDisabledException.class);

            // Test a call where one deletion result has an error.
            env.kafkaClient().prepareResponse(new DeleteAclsResponse(0, asList(
                    new AclFilterResponse(asList(new AclDeletionResult(ACL1),
                            new AclDeletionResult(new ApiError(Errors.SECURITY_DISABLED, "No security"), ACL2))),
                    new AclFilterResponse(Collections.<AclDeletionResult>emptySet()))));
            results = env.adminClient().deleteAcls(asList(FILTER1, FILTER2));
            assertTrue(results.values().get(FILTER2).get().values().isEmpty());
            assertFutureError(results.all(), SecurityDisabledException.class);

            // Test a call where there are no errors.
            env.kafkaClient().prepareResponse(new DeleteAclsResponse(0, asList(
                    new AclFilterResponse(asList(new AclDeletionResult(ACL1))),
                    new AclFilterResponse(asList(new AclDeletionResult(ACL2))))));
            results = env.adminClient().deleteAcls(asList(FILTER1, FILTER2));
            Collection<AclBinding> deleted = results.all().get();
            assertCollectionIs(deleted, ACL1, ACL2);
        }
    }

    /**
     * Test handling timeouts.
     */
    @Ignore // The test is flaky. Should be renabled when this JIRA is fixed: https://issues.apache.org/jira/browse/KAFKA-5792
    @Test
    public void testHandleTimeout() throws Exception {
        HashMap<Integer, Node> nodes = new HashMap<>();
        MockTime time = new MockTime();
        nodes.put(0, new Node(0, "localhost", 8121));
        Cluster cluster = new Cluster("mockClusterId", nodes.values(),
            Collections.<PartitionInfo>emptySet(), Collections.<String>emptySet(),
            Collections.<String>emptySet(), nodes.get(0));
        try (AdminClientUnitTestEnv env = new AdminClientUnitTestEnv(time, cluster,
            AdminClientConfig.RECONNECT_BACKOFF_MAX_MS_CONFIG, "1",
                AdminClientConfig.RECONNECT_BACKOFF_MS_CONFIG, "1")) {
            env.kafkaClient().setNodeApiVersions(NodeApiVersions.create());
            env.kafkaClient().prepareMetadataUpdate(env.cluster(), Collections.<String>emptySet());
            env.kafkaClient().setNode(nodes.get(0));
            assertEquals(time, env.time());
            assertEquals(env.time(), ((KafkaAdminClient) env.adminClient()).time());

            // Make a request with an extremely short timeout.
            // Then wait for it to fail by not supplying any response.
            log.info("Starting AdminClient#listTopics...");
            final ListTopicsResult result = env.adminClient().listTopics(new ListTopicsOptions().timeoutMs(1000));
            TestUtils.waitForCondition(new TestCondition() {
                @Override
                public boolean conditionMet() {
                    return env.kafkaClient().hasInFlightRequests();
                }
            }, "Timed out waiting for inFlightRequests");
            time.sleep(5000);
            TestUtils.waitForCondition(new TestCondition() {
                @Override
                public boolean conditionMet() {
                    return result.listings().isDone();
                }
            }, "Timed out waiting for listTopics to complete");
            assertFutureError(result.listings(), TimeoutException.class);
            log.info("Verified the error result of AdminClient#listTopics");

            // The next request should succeed.
            time.sleep(5000);
            env.kafkaClient().prepareResponse(new DescribeConfigsResponse(0,
                Collections.singletonMap(new org.apache.kafka.common.requests.Resource(TOPIC, "foo"),
                    new DescribeConfigsResponse.Config(ApiError.NONE,
                        Collections.<DescribeConfigsResponse.ConfigEntry>emptySet()))));
            DescribeConfigsResult result2 = env.adminClient().describeConfigs(Collections.singleton(
                new ConfigResource(ConfigResource.Type.TOPIC, "foo")));
            time.sleep(5000);
            result2.values().get(new ConfigResource(ConfigResource.Type.TOPIC, "foo")).get();
        }
    }

    @Test
    public void testDescribeConfigs() throws Exception {
        try (AdminClientUnitTestEnv env = mockClientEnv()) {
            env.kafkaClient().setNodeApiVersions(NodeApiVersions.create());
            env.kafkaClient().prepareMetadataUpdate(env.cluster(), Collections.<String>emptySet());
            env.kafkaClient().setNode(env.cluster().controller());
            env.kafkaClient().prepareResponse(new DescribeConfigsResponse(0,
                Collections.singletonMap(new org.apache.kafka.common.requests.Resource(BROKER, "0"),
                    new DescribeConfigsResponse.Config(ApiError.NONE,
                        Collections.<DescribeConfigsResponse.ConfigEntry>emptySet()))));
            DescribeConfigsResult result2 = env.adminClient().describeConfigs(Collections.singleton(
                new ConfigResource(ConfigResource.Type.BROKER, "0")));
            result2.all().get();
        }
    }

    @Test
    public void testCreatePartitions() throws Exception {
        try (AdminClientUnitTestEnv env = mockClientEnv()) {
            env.kafkaClient().setNodeApiVersions(NodeApiVersions.create());
            env.kafkaClient().prepareMetadataUpdate(env.cluster(), Collections.<String>emptySet());
            env.kafkaClient().setNode(env.cluster().controller());

            Map<String, ApiError> m = new HashMap<>();
            m.put("my_topic", ApiError.NONE);
            m.put("other_topic", ApiError.fromThrowable(new InvalidTopicException("some detailed reason")));

            // Test a call where one filter has an error.
            env.kafkaClient().prepareResponse(new CreatePartitionsResponse(0, m));

            Map<String, NewPartitions> counts = new HashMap<>();
            counts.put("my_topic", NewPartitions.increaseTo(3));
            counts.put("other_topic", NewPartitions.increaseTo(3, asList(asList(2), asList(3))));

            CreatePartitionsResult results = env.adminClient().createPartitions(counts);
            Map<String, KafkaFuture<Void>> values = results.values();
            KafkaFuture<Void> myTopicResult = values.get("my_topic");
            myTopicResult.get();
            KafkaFuture<Void> otherTopicResult = values.get("other_topic");
            try {
                otherTopicResult.get();
                fail("get() should throw ExecutionException");
            } catch (ExecutionException e0) {
                assertTrue(e0.getCause() instanceof InvalidTopicException);
                InvalidTopicException e = (InvalidTopicException) e0.getCause();
                assertEquals("some detailed reason", e.getMessage());
            }
        }
    }

    @Test
    public void testDeleteRecords() throws Exception {

        HashMap<Integer, Node> nodes = new HashMap<>();
        nodes.put(0, new Node(0, "localhost", 8121));
        List<PartitionInfo> partitionInfos = new ArrayList<>();
        partitionInfos.add(new PartitionInfo("my_topic", 0, nodes.get(0), new Node[] {nodes.get(0)}, new Node[] {nodes.get(0)}));
        partitionInfos.add(new PartitionInfo("my_topic", 1, nodes.get(0), new Node[] {nodes.get(0)}, new Node[] {nodes.get(0)}));
        partitionInfos.add(new PartitionInfo("my_topic", 2, null, new Node[] {nodes.get(0)}, new Node[] {nodes.get(0)}));
        partitionInfos.add(new PartitionInfo("my_topic", 3, nodes.get(0), new Node[] {nodes.get(0)}, new Node[] {nodes.get(0)}));
        partitionInfos.add(new PartitionInfo("my_topic", 4, nodes.get(0), new Node[] {nodes.get(0)}, new Node[] {nodes.get(0)}));
        Cluster cluster = new Cluster("mockClusterId", nodes.values(),
                partitionInfos, Collections.<String>emptySet(),
                Collections.<String>emptySet(), nodes.get(0));

        TopicPartition myTopicPartition0 = new TopicPartition("my_topic", 0);
        TopicPartition myTopicPartition1 = new TopicPartition("my_topic", 1);
        TopicPartition myTopicPartition2 = new TopicPartition("my_topic", 2);
        TopicPartition myTopicPartition3 = new TopicPartition("my_topic", 3);
        TopicPartition myTopicPartition4 = new TopicPartition("my_topic", 4);

        try (AdminClientUnitTestEnv env = new AdminClientUnitTestEnv(cluster)) {
            env.kafkaClient().setNodeApiVersions(NodeApiVersions.create());
            env.kafkaClient().prepareMetadataUpdate(env.cluster(), Collections.<String>emptySet());
            env.kafkaClient().setNode(env.cluster().nodes().get(0));

            Map<TopicPartition, DeleteRecordsResponse.PartitionResponse> m = new HashMap<>();
            m.put(myTopicPartition0,
                    new DeleteRecordsResponse.PartitionResponse(3, Errors.NONE));
            m.put(myTopicPartition1,
                    new DeleteRecordsResponse.PartitionResponse(DeleteRecordsResponse.INVALID_LOW_WATERMARK, Errors.OFFSET_OUT_OF_RANGE));
            m.put(myTopicPartition3,
                    new DeleteRecordsResponse.PartitionResponse(DeleteRecordsResponse.INVALID_LOW_WATERMARK, Errors.NOT_LEADER_FOR_PARTITION));
            m.put(myTopicPartition4,
                    new DeleteRecordsResponse.PartitionResponse(DeleteRecordsResponse.INVALID_LOW_WATERMARK, Errors.UNKNOWN_TOPIC_OR_PARTITION));

            List<MetadataResponse.TopicMetadata> t = new ArrayList<>();
            List<MetadataResponse.PartitionMetadata> p = new ArrayList<>();
            p.add(new MetadataResponse.PartitionMetadata(Errors.NONE, 0, nodes.get(0),
                    Collections.singletonList(nodes.get(0)), Collections.singletonList(nodes.get(0)), Collections.<Node>emptyList()));
            p.add(new MetadataResponse.PartitionMetadata(Errors.NONE, 1, nodes.get(0),
                    Collections.singletonList(nodes.get(0)), Collections.singletonList(nodes.get(0)), Collections.<Node>emptyList()));
            p.add(new MetadataResponse.PartitionMetadata(Errors.LEADER_NOT_AVAILABLE, 2, null,
                    Collections.singletonList(nodes.get(0)), Collections.singletonList(nodes.get(0)), Collections.<Node>emptyList()));
            p.add(new MetadataResponse.PartitionMetadata(Errors.NONE, 3, nodes.get(0),
                    Collections.singletonList(nodes.get(0)), Collections.singletonList(nodes.get(0)), Collections.<Node>emptyList()));
            p.add(new MetadataResponse.PartitionMetadata(Errors.NONE, 4, nodes.get(0),
                    Collections.singletonList(nodes.get(0)), Collections.singletonList(nodes.get(0)), Collections.<Node>emptyList()));

            t.add(new MetadataResponse.TopicMetadata(Errors.NONE, "my_topic", false, p));

            env.kafkaClient().prepareResponse(new MetadataResponse(cluster.nodes(), cluster.clusterResource().clusterId(), cluster.controller().id(), t));
            env.kafkaClient().prepareResponse(new DeleteRecordsResponse(0, m));

            Map<TopicPartition, RecordsToDelete> recordsToDelete = new HashMap<>();
            recordsToDelete.put(myTopicPartition0, RecordsToDelete.beforeOffset(3L));
            recordsToDelete.put(myTopicPartition1, RecordsToDelete.beforeOffset(10L));
            recordsToDelete.put(myTopicPartition2, RecordsToDelete.beforeOffset(10L));
            recordsToDelete.put(myTopicPartition3, RecordsToDelete.beforeOffset(10L));
            recordsToDelete.put(myTopicPartition4, RecordsToDelete.beforeOffset(10L));

            DeleteRecordsResult results = env.adminClient().deleteRecords(recordsToDelete);

            // success on records deletion for partition 0
            Map<TopicPartition, KafkaFuture<DeletedRecords>> values = results.lowWatermarks();
            KafkaFuture<DeletedRecords> myTopicPartition0Result = values.get(myTopicPartition0);
            long lowWatermark = myTopicPartition0Result.get().lowWatermark();
            assertEquals(lowWatermark, 3);

            // "offset out of range" failure on records deletion for partition 1
            KafkaFuture<DeletedRecords> myTopicPartition1Result = values.get(myTopicPartition1);
            try {
                myTopicPartition1Result.get();
                fail("get() should throw ExecutionException");
            } catch (ExecutionException e0) {
                assertTrue(e0.getCause() instanceof OffsetOutOfRangeException);
            }

            // "leader not available" failure on metadata request for partition 2
            KafkaFuture<DeletedRecords> myTopicPartition2Result = values.get(myTopicPartition2);
            try {
                myTopicPartition2Result.get();
                fail("get() should throw ExecutionException");
            } catch (ExecutionException e1) {
                assertTrue(e1.getCause() instanceof LeaderNotAvailableException);
            }

            // "not leader for partition" failure on records deletion for partition 3
            KafkaFuture<DeletedRecords> myTopicPartition3Result = values.get(myTopicPartition3);
            try {
                myTopicPartition3Result.get();
                fail("get() should throw ExecutionException");
            } catch (ExecutionException e1) {
                assertTrue(e1.getCause() instanceof NotLeaderForPartitionException);
            }

            // "unknown topic or partition" failure on records deletion for partition 4
            KafkaFuture<DeletedRecords> myTopicPartition4Result = values.get(myTopicPartition4);
            try {
                myTopicPartition4Result.get();
                fail("get() should throw ExecutionException");
            } catch (ExecutionException e1) {
                assertTrue(e1.getCause() instanceof UnknownTopicOrPartitionException);
            }
        }
    }

    private static <T> void assertCollectionIs(Collection<T> collection, T... elements) {
        for (T element : elements) {
            assertTrue("Did not find " + element, collection.contains(element));
        }
        assertEquals("There are unexpected extra elements in the collection.",
            elements.length, collection.size());
    }

    public static KafkaAdminClient createInternal(AdminClientConfig config, KafkaAdminClient.TimeoutProcessorFactory timeoutProcessorFactory) {
        return KafkaAdminClient.createInternal(config, timeoutProcessorFactory);
    }

    public static class FailureInjectingTimeoutProcessorFactory extends KafkaAdminClient.TimeoutProcessorFactory {

        private int numTries = 0;

        private int failuresInjected = 0;
        
        @Override
        public KafkaAdminClient.TimeoutProcessor create(long now) {
            return new FailureInjectingTimeoutProcessor(now);
        }

        synchronized boolean shouldInjectFailure() {
            numTries++;
            if (numTries == 1) {
                failuresInjected++;
                return true;
            }
            return false;
        }

        public synchronized int failuresInjected() {
            return failuresInjected;
        }

        public final class FailureInjectingTimeoutProcessor extends KafkaAdminClient.TimeoutProcessor {
            public FailureInjectingTimeoutProcessor(long now) {
                super(now);
            }

            boolean callHasExpired(KafkaAdminClient.Call call) {
                if (shouldInjectFailure()) {
                    log.debug("Injecting timeout for {}.", call);
                    return true;
                } else {
                    boolean ret = super.callHasExpired(call);
                    log.debug("callHasExpired({}) = {}", call, ret);
                    return ret;
                }
            }
        }

    }

}
