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

import static org.apache.geode.distributed.ConfigurationProperties.ENABLE_NETWORK_PARTITION_DETECTION;
import static org.apache.geode.distributed.ConfigurationProperties.ENABLE_TIME_STATISTICS;
import static org.apache.geode.distributed.ConfigurationProperties.GROUPS;
import static org.apache.geode.distributed.ConfigurationProperties.LOCATORS;
import static org.apache.geode.distributed.ConfigurationProperties.LOG_LEVEL;
import static org.apache.geode.distributed.ConfigurationProperties.MCAST_PORT;
import static org.apache.geode.distributed.ConfigurationProperties.NAME;
import static org.apache.geode.distributed.ConfigurationProperties.STATISTIC_SAMPLING_ENABLED;
import static org.apache.geode.test.dunit.Assert.assertEquals;
import static org.apache.geode.test.dunit.LogWriterUtils.getLogWriter;
import static org.apache.geode.test.dunit.NetworkUtils.getServerHostName;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.junit.ClassRule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.ProvideSystemProperty;
import org.junit.experimental.categories.Category;

import org.apache.geode.cache.Cache;
import org.apache.geode.cache.CacheFactory;
import org.apache.geode.cache.EvictionAction;
import org.apache.geode.cache.EvictionAttributes;
import org.apache.geode.cache.FixedPartitionAttributes;
import org.apache.geode.cache.PartitionAttributes;
import org.apache.geode.cache.PartitionAttributesFactory;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.RegionFactory;
import org.apache.geode.cache.RegionShortcut;
import org.apache.geode.distributed.DistributedMember;
import org.apache.geode.distributed.Locator;
import org.apache.geode.internal.AvailablePortHelper;
import org.apache.geode.internal.cache.GemFireCacheImpl;
import org.apache.geode.management.cli.Result;
import org.apache.geode.management.cli.Result.Status;
import org.apache.geode.management.internal.cli.i18n.CliStrings;
import org.apache.geode.management.internal.cli.remote.OnlineCommandProcessor;
import org.apache.geode.management.internal.cli.util.CommandStringBuilder;
import org.apache.geode.test.dunit.Host;
import org.apache.geode.test.dunit.SerializableRunnable;
import org.apache.geode.test.dunit.VM;
import org.apache.geode.test.dunit.cache.internal.JUnit4CacheTestCase;
import org.apache.geode.test.junit.categories.DistributedTest;

@Category(DistributedTest.class)
public class MemberCommandsDUnitTest extends JUnit4CacheTestCase {

  private static final long serialVersionUID = 1L;

  private static final Map<String, String> EMPTY_ENV = Collections.emptyMap();
  private static final String REGION1 = "region1";
  private static final String REGION2 = "region2";
  private static final String REGION3 = "region3";
  private static final String SUBREGION1A = "subregion1A";
  private static final String SUBREGION1B = "subregion1B";
  private static final String SUBREGION1C = "subregion1C";
  private static final String PR1 = "PartitionedRegion1";
  private static final String PR2 = "ParitionedRegion2";

  @ClassRule
  public static ProvideSystemProperty provideSystemProperty =
      new ProvideSystemProperty(CliCommandTestBase.USE_HTTP_SYSTEM_PROPERTY, "true");

  @Override
  public final void postTearDownCacheTestCase() throws Exception {
    disconnectFromDS();
  }

  private Properties createProperties(String name, String groups) {
    Properties props = new Properties();
    props.setProperty(MCAST_PORT, "0");
    props.setProperty(LOG_LEVEL, "info");
    props.setProperty(STATISTIC_SAMPLING_ENABLED, "true");
    props.setProperty(ENABLE_TIME_STATISTICS, "true");
    props.setProperty(NAME, name);
    props.setProperty(GROUPS, groups);
    return props;
  }

  private void createRegionsWithSubRegions() {
    final Cache cache = getCache();

    RegionFactory<String, Integer> dataRegionFactory =
        cache.createRegionFactory(RegionShortcut.REPLICATE);
    dataRegionFactory.setConcurrencyLevel(3);
    Region<String, Integer> region1 = dataRegionFactory.create(REGION1);
    region1.createSubregion(SUBREGION1C, region1.getAttributes());
    Region<String, Integer> subregion2 =
        region1.createSubregion(SUBREGION1A, region1.getAttributes());

    subregion2.createSubregion(SUBREGION1B, subregion2.getAttributes());
    dataRegionFactory.create(REGION2);
    dataRegionFactory.create(REGION3);
  }

  private void createPartitionedRegion1() {
    final Cache cache = getCache();
    // Create the data region
    RegionFactory<String, Integer> dataRegionFactory =
        cache.createRegionFactory(RegionShortcut.PARTITION);
    dataRegionFactory.create(PR1);
  }

  private void createPartitionedRegion(String regionName) {
    final Cache cache = getCache();
    // Create the data region
    RegionFactory<String, Integer> dataRegionFactory =
        cache.createRegionFactory(RegionShortcut.PARTITION);
    dataRegionFactory.setConcurrencyLevel(4);
    EvictionAttributes ea =
        EvictionAttributes.createLIFOEntryAttributes(100, EvictionAction.LOCAL_DESTROY);
    dataRegionFactory.setEvictionAttributes(ea);
    dataRegionFactory.setEnableAsyncConflation(true);

    FixedPartitionAttributes fpa = FixedPartitionAttributes.createFixedPartition("Par1", true);
    PartitionAttributes pa =
        new PartitionAttributesFactory().setLocalMaxMemory(100).setRecoveryDelay(2)
            .setTotalMaxMemory(200).setRedundantCopies(1).addFixedPartitionAttributes(fpa).create();
    dataRegionFactory.setPartitionAttributes(pa);

    dataRegionFactory.create(regionName);
  }


  private void createLocalRegion() {
    final Cache cache = getCache();
    // Create the data region
    RegionFactory<String, Integer> dataRegionFactory =
        cache.createRegionFactory(RegionShortcut.LOCAL);
    dataRegionFactory.create("LocalRegion");
  }

  private void setupSystem() throws IOException {
    disconnectAllFromDS();
    final Host host = Host.getHost(0);
    final VM[] servers = {host.getVM(0), host.getVM(1)};

    final Properties propsMe = createProperties("me", "G1");
    final Properties propsServer1 = createProperties("Server1", "G1");
    final Properties propsServer2 = createProperties("Server2", "G2");


    getSystem(propsMe);
    final Cache cache = getCache();
    RegionFactory<String, Integer> dataRegionFactory =
        cache.createRegionFactory(RegionShortcut.REPLICATE_PROXY);
    dataRegionFactory.setConcurrencyLevel(5);
    Region<String, Integer> region1 = dataRegionFactory.create(REGION1);


    servers[1].invoke(new SerializableRunnable("Create cache for server1") {
      public void run() {
        getSystem(propsServer2);
        createRegionsWithSubRegions();
        createLocalRegion();
        createPartitionedRegion("ParReg1");
      }
    });
    servers[0].invoke(new SerializableRunnable("Create cache for server0") {
      public void run() {
        getSystem(propsServer1);
        createRegionsWithSubRegions();
        createLocalRegion();
      }
    });
  }

  private Properties createProperties(Host host, int locatorPort) {
    Properties props = new Properties();

    props.setProperty(MCAST_PORT, "0");
    props.setProperty(LOCATORS, getServerHostName(host) + "[" + locatorPort + "]");
    props.setProperty(LOG_LEVEL, "info");
    props.setProperty(STATISTIC_SAMPLING_ENABLED, "true");
    props.setProperty(ENABLE_TIME_STATISTICS, "true");
    props.put(ENABLE_NETWORK_PARTITION_DETECTION, "true");

    return props;
  }

  /**
   * Creates the cache.
   */
  private void createCache(Properties props) {
    getSystem(props);
    final Cache cache = getCache();
  }

  /**
   * Tests the execution of "list member" command which should list out all the members in the DS
   *
   * @throws IOException
   * @throws ClassNotFoundException
   */
  @Test
  public void testListMemberAll() throws IOException, ClassNotFoundException {
    setupSystem();
    OnlineCommandProcessor onlineCommandProcessor = new OnlineCommandProcessor();
    Result result =
        onlineCommandProcessor.createCommandStatement(CliStrings.LIST_MEMBER, EMPTY_ENV).process();
    String resultOutput = getResultAsString(result);
    getLogWriter().info(resultOutput);
    assertEquals(true, result.getStatus().equals(Status.OK));
    assertTrue(resultOutput.contains("me:"));
    assertTrue(resultOutput.contains("Server1:"));
    assertTrue(resultOutput.contains("Server2:"));
  }

  /**
   * Tests the execution of "list member" command, when no cache is created
   *
   * @throws IOException
   * @throws ClassNotFoundException
   */
  @Test
  public void testListMemberWithNoCache() throws IOException, ClassNotFoundException {
    final Host host = Host.getHost(0);
    final VM[] servers = {host.getVM(0), host.getVM(1)};
    final int openPorts[] = AvailablePortHelper.getRandomAvailableTCPPorts(1);
    final File logFile = new File(getUniqueName() + "-locator" + openPorts[0] + ".log");

    Locator locator = Locator.startLocator(openPorts[0], logFile);
    try {

      final Properties props = createProperties(host, openPorts[0]);
      OnlineCommandProcessor onlineCommandProcessor = new OnlineCommandProcessor();
      Result result = onlineCommandProcessor
          .createCommandStatement(CliStrings.LIST_MEMBER, EMPTY_ENV).process();

      getLogWriter().info("#SB" + getResultAsString(result));
      assertEquals(true, result.getStatus().equals(Status.ERROR));
    } finally {
      locator.stop(); // fix for bug 46562
    }
  }

  /**
   * Tests list member --group=G1
   *
   * @throws IOException
   * @throws ClassNotFoundException
   */
  @Test
  public void testListMemberWithGroups() throws IOException, ClassNotFoundException {
    setupSystem();
    OnlineCommandProcessor onlineCommandProcessor = new OnlineCommandProcessor();
    CommandStringBuilder csb = new CommandStringBuilder(CliStrings.LIST_MEMBER);
    csb.addOption(CliStrings.GROUP, "G1");
    Result result =
        onlineCommandProcessor.createCommandStatement(csb.toString(), EMPTY_ENV).process();
    getLogWriter().info("#SB" + getResultAsString(result));
    assertEquals(true, result.getStatus().equals(Status.OK));
  }

  /**
   * Tests the "describe member" command for all the members in the DS
   *
   * @throws IOException
   * @throws ClassNotFoundException
   */
  @Test
  public void testDescribeMember() throws IOException, ClassNotFoundException {
    setupSystem();
    OnlineCommandProcessor onlineCommandProcessor = new OnlineCommandProcessor();
    GemFireCacheImpl cache = (GemFireCacheImpl) CacheFactory.getAnyInstance();
    Set<DistributedMember> members = cache.getDistributedSystem().getAllOtherMembers();

    Iterator<DistributedMember> iters = members.iterator();

    while (iters.hasNext()) {
      DistributedMember member = iters.next();
      Result result = onlineCommandProcessor
          .createCommandStatement("describe member --name=" + member.getId(), EMPTY_ENV).process();
      assertEquals(true, result.getStatus().equals(Status.OK));
      getLogWriter().info("#SB" + getResultAsString(result));
      // assertIndexDetailsEquals(true, result.getStatus().equals(Status.OK));
    }
  }

  private String getResultAsString(Result result) {
    StringBuilder sb = new StringBuilder();
    while (result.hasNextLine()) {
      sb.append(result.nextLine());
    }
    return sb.toString();
  }
}
