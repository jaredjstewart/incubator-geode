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
package org.apache.geode.test.dunit.rules;

import static org.junit.Assert.assertNotNull;

import org.junit.rules.ExternalResource;

/**
 * This {@code Rule} is used to indicate tests that require the GEODE_HOME environment varible to be
 * set. (For example, any test that relies on the assembled Pulse WAR or GFSH binary.)
 */
public class RequiresGeodeHome extends ExternalResource {
  private static final String GEODE_HOME_NOT_SET_MESSAGE =
      "This test requires a GEODE_HOME environment variable that points to the location "
          + "of geode-assembly/build/install/apache-geode.\n"
          + "For instructions on how to set this variable if running tests through IntelliJ, see \n"
          + "https://stackoverflow.com/a/32761503/3988499 \n";

  @Override
  protected void before() {
    assertNotNull(GEODE_HOME_NOT_SET_MESSAGE, System.getenv("GEODE_HOME"));
  }
}
