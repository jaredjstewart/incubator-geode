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
 *
 */
package org.apache.geode.management.internal.configuration.functions;

import org.apache.geode.cache.execute.Function;
import org.apache.geode.cache.execute.FunctionContext;
import org.apache.geode.distributed.Locator;
import org.apache.geode.distributed.internal.InternalLocator;
import org.apache.geode.distributed.internal.SharedConfiguration;
import org.apache.geode.internal.InternalEntity;

import java.io.IOException;

public class UploadJarFunction implements Function, InternalEntity {

  private static final long serialVersionUID = 1L;

  @Override
  public void execute(FunctionContext context) {
    InternalLocator locator = (InternalLocator) Locator.getLocator();
    Object[] args = (Object[]) context.getArguments();
    String group = (String) args[0];
    String jarName = (String) args[1];

    if (locator != null && group != null && jarName != null) {
      SharedConfiguration sharedConfig = locator.getSharedConfiguration();
      if (sharedConfig != null) {
        try {
          byte[] jarBytes = sharedConfig.getJarBytesFromThisLocator(group, jarName);
          context.getResultSender().lastResult(jarBytes);

          // TODO: should we just return here if jarbytes was not null?
        } catch (IOException e) {
          context.getResultSender().sendException(e);
        } catch (Exception e) {
          context.getResultSender().sendException(e);
        }
      }
    }

    // TODO: Why does this not throw an IllegalStateException?
    context.getResultSender().lastResult(null);
  }

  @Override
  public String getId() {
    return UploadJarFunction.class.getName();
  }

}
