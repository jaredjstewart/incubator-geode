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
package org.apache.geode.management.internal.cli.result;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Paths;

import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.apache.geode.management.cli.Result;
import org.apache.geode.management.internal.cli.CommandResponseBuilder;
import org.apache.geode.management.internal.cli.json.GfJsonException;
import org.apache.geode.test.junit.categories.UnitTest;


@Category(UnitTest.class)
public class ResultBuilderTest {

  @Test
  public void resultWithoutFileGetsRehydratedCorrectly() throws GfJsonException {
    CommandResult result = new CommandResult(new InfoResultData("Some message"));

    String resultJson = CommandResponseBuilder.createCommandResponseJson("someMember", result);

    CommandResult rehydratedResult = ResultBuilder.fromJson(resultJson);
    assertThat(rehydratedResult.getContent().toString()).isEqualTo(result.getContent().toString());
    assertThat(rehydratedResult.getFileToDownload()).isEqualTo(null);
    assertThat(rehydratedResult.hasFileToDownload()).isFalse();
  }

  @Test
  public void resultWithFileGetsRehydratedCorrectly() throws GfJsonException {
    CommandResult result = new CommandResult(Paths.get("."));

    String resultJson = CommandResponseBuilder.createCommandResponseJson("someMember", result);

    CommandResult rehydratedResult = ResultBuilder.fromJson(resultJson);
    assertThat(rehydratedResult.getContent().toString()).isEqualTo(result.getContent().toString());
    assertThat(rehydratedResult.hasFileToDownload()).isTrue();
  }

  @Test
  public void errorResultGetsRehydratedWithErrorStatus() throws GfJsonException {
    CommandResult result = new CommandResult(new ErrorResultData("some error message"));

    String resultJson = CommandResponseBuilder.createCommandResponseJson("someMember", result);

    CommandResult rehydratedResult = ResultBuilder.fromJson(resultJson);
    assertThat(rehydratedResult.getStatus()).isEqualTo(Result.Status.ERROR);
  }

}