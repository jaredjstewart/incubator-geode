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

import org.apache.geode.cache.execute.ResultCollector;
import org.apache.geode.distributed.DistributedMember;
import org.apache.geode.distributed.internal.membership.InternalDistributedMember;
import org.apache.geode.internal.cache.GemFireCacheImpl;
import org.apache.geode.internal.lang.StringUtils;
import org.apache.geode.management.cli.CliMetaData;
import org.apache.geode.management.cli.Result;
import org.apache.geode.management.internal.cli.AbstractCliAroundInterceptor;
import org.apache.geode.management.internal.cli.CliUtil;
import org.apache.geode.management.internal.cli.GfshParseResult;
import org.apache.geode.management.internal.cli.functions.CliFunctionResult;
import org.apache.geode.management.internal.cli.functions.ExportSharedConfigurationFunction;
import org.apache.geode.management.internal.cli.functions.ImportSharedConfigurationArtifactsFunction;
import org.apache.geode.management.internal.cli.functions.LoadSharedConfigurationFunction;
import org.apache.geode.management.internal.cli.i18n.CliStrings;
import org.apache.geode.management.internal.cli.remote.CommandExecutionContext;
import org.apache.geode.management.internal.cli.result.ErrorResultData;
import org.apache.geode.management.internal.cli.result.FileResult;
import org.apache.geode.management.internal.cli.result.InfoResultData;
import org.apache.geode.management.internal.cli.result.ResultBuilder;
import org.apache.geode.management.internal.cli.result.TabularResultData;
import org.apache.geode.management.internal.security.ResourceOperation;
import org.apache.geode.security.ResourcePermission.Operation;
import org.apache.geode.security.ResourcePermission.Resource;
import org.springframework.shell.core.annotation.CliAvailabilityIndicator;
import org.springframework.shell.core.annotation.CliCommand;
import org.springframework.shell.core.annotation.CliOption;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/****
 * Commands for the shared configuration
 *
 */
@SuppressWarnings("unused")
public class ExportImportSharedConfigurationCommands extends AbstractCommandsSupport {

  private final ExportSharedConfigurationFunction exportSharedConfigurationFunction =
      new ExportSharedConfigurationFunction();
  private final ImportSharedConfigurationArtifactsFunction importSharedConfigurationFunction =
      new ImportSharedConfigurationArtifactsFunction();
  private final LoadSharedConfigurationFunction loadSharedConfiguration =
      new LoadSharedConfigurationFunction();

  @CliCommand(value = {CliStrings.EXPORT_SHARED_CONFIG},
      help = CliStrings.EXPORT_SHARED_CONFIG__HELP)
  @CliMetaData(
      interceptor = "org.apache.geode.management.internal.cli.commands.ExportImportSharedConfigurationCommands$ExportInterceptor",
      readsSharedConfiguration = true, relatedTopic = {CliStrings.TOPIC_GEODE_CONFIG})
  @ResourceOperation(resource = Resource.CLUSTER, operation = Operation.READ)
  public Result exportSharedConfig(@CliOption(key = {CliStrings.EXPORT_SHARED_CONFIG__FILE},
      mandatory = true, help = CliStrings.EXPORT_SHARED_CONFIG__FILE__HELP) String zipFileName,

      @CliOption(key = {CliStrings.EXPORT_SHARED_CONFIG__DIR},
          help = CliStrings.EXPORT_SHARED_CONFIG__DIR__HELP) String dir) {

    GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
    Set<? extends DistributedMember> locators =
        cache.getDistributionManager().getAllHostedLocatorsWithSharedConfiguration().keySet();

    Optional<CliFunctionResult> functionResult = locators.stream()
        .map((DistributedMember locator) -> exportSharedConfigurationFromLocator(locator, null))
        .filter(CliFunctionResult::isSuccessful).findFirst();

    Result result;
    if (functionResult.isPresent()) {
      InfoResultData infoData = ResultBuilder.createInfoResultData();
      byte[] byteData = functionResult.get().getByteData();
      infoData.addAsFile(zipFileName, byteData, InfoResultData.FILE_TYPE_BINARY,
          CliStrings.EXPORT_SHARED_CONFIG__DOWNLOAD__MSG, false);
      result = ResultBuilder.buildResult(infoData);
    } else {
      ErrorResultData errorData = ResultBuilder.createErrorResultData();
      errorData.addLine("Export failed");
      result = ResultBuilder.buildResult(errorData);
    }

    return result;
  }

  private CliFunctionResult exportSharedConfigurationFromLocator(DistributedMember locator,
      Object[] args) {
    ResultCollector rc = CliUtil.executeFunction(exportSharedConfigurationFunction, args, locator);
    List<CliFunctionResult> results = (List<CliFunctionResult>) rc.getResult();

    return results.get(0);
  }


  @CliCommand(value = {CliStrings.IMPORT_SHARED_CONFIG},
      help = CliStrings.IMPORT_SHARED_CONFIG__HELP)
  @CliMetaData(
      interceptor = "org.apache.geode.management.internal.cli.commands.ExportImportSharedConfigurationCommands$ImportInterceptor",
      writesToSharedConfiguration = true, relatedTopic = {CliStrings.TOPIC_GEODE_CONFIG})
  @ResourceOperation(resource = Resource.CLUSTER, operation = Operation.MANAGE)
  @SuppressWarnings("unchecked")
  public Result importSharedConfig(@CliOption(key = {CliStrings.IMPORT_SHARED_CONFIG__ZIP},
      mandatory = true, help = CliStrings.IMPORT_SHARED_CONFIG__ZIP__HELP) String zip) {

    GemFireCacheImpl cache = GemFireCacheImpl.getInstance();

    if (!CliUtil.getAllNormalMembers(cache).isEmpty()) {
      return ResultBuilder
          .createGemFireErrorResult(CliStrings.IMPORT_SHARED_CONFIG__CANNOT__IMPORT__MSG);
    }

    Set<? extends DistributedMember> locators =
        cache.getDistributionManager().getAllHostedLocatorsWithSharedConfiguration().keySet();

    if (locators.isEmpty()) {
      return ResultBuilder.createGemFireErrorResult(CliStrings.NO_LOCATORS_WITH_SHARED_CONFIG);
    }

    Result result;
    byte[][] shellBytesData = CommandExecutionContext.getBytesFromShell();
    String[] names = CliUtil.bytesToNames(shellBytesData);
    byte[][] bytes = CliUtil.bytesToData(shellBytesData);

    String zipFileName = names[0];
    byte[] zipBytes = bytes[0];

    Object[] args = new Object[] {zipFileName, zipBytes};


    Optional<CliFunctionResult> functionResult = locators.stream()
        .map((DistributedMember locator) -> importSharedConfigurationFromLocator(locator, args))
        .filter(CliFunctionResult::isSuccessful).findFirst();

    if (functionResult.isPresent()) {
      InfoResultData infoData = ResultBuilder.createInfoResultData();
      infoData.addLine(functionResult.get().getMessage());
      result = ResultBuilder.buildResult(infoData);
    } else {
      ErrorResultData errorData = ResultBuilder.createErrorResultData();
      errorData.addLine("Import failed");
      result = ResultBuilder.buildResult(errorData);
    }

    return result;
  }

  private CliFunctionResult importSharedConfigurationFromLocator(DistributedMember locator,
      Object[] args) {
    ResultCollector rc = CliUtil.executeFunction(importSharedConfigurationFunction, args, locator);
    List<CliFunctionResult> results = (List<CliFunctionResult>) rc.getResult();

    return results.get(0);
  }


  @CliAvailabilityIndicator({CliStrings.EXPORT_SHARED_CONFIG, CliStrings.IMPORT_SHARED_CONFIG})
  public boolean sharedConfigCommandsAvailable() {
    boolean isAvailable = true; // always available on server
    if (CliUtil.isGfshVM()) { // in gfsh check if connected
      isAvailable = getGfsh() != null && getGfsh().isConnectedAndReady();
    }
    return isAvailable;
  }

  /**
   * Interceptor used by gfsh to intercept execution of export shared config command at "shell".
   */
  public static class ExportInterceptor extends AbstractCliAroundInterceptor {
    private String saveDirString;

    @Override
    public Result preExecution(GfshParseResult parseResult) {
      Map<String, String> paramValueMap = parseResult.getParamValueStrings();
      String zip = paramValueMap.get(CliStrings.EXPORT_SHARED_CONFIG__FILE);

      if (!zip.endsWith(".zip")) {
        return ResultBuilder
            .createUserErrorResult(CliStrings.format(CliStrings.INVALID_FILE_EXTENTION, ".zip"));
      }
      return ResultBuilder.createInfoResult("OK");
    }

    @Override
    public Result postExecution(GfshParseResult parseResult, Result commandResult) {
      if (commandResult.hasIncomingFiles()) {
        try {
          Map<String, String> paramValueMap = parseResult.getParamValueStrings();
          String dir = paramValueMap.get(CliStrings.EXPORT_SHARED_CONFIG__DIR);
          dir = (dir == null) ? null : dir.trim();

          File saveDirFile = new File(".");

          if (dir != null && !dir.isEmpty()) {
            saveDirFile = new File(dir);
            if (saveDirFile.exists()) {
              if (!saveDirFile.isDirectory())
                return ResultBuilder.createGemFireErrorResult(
                    CliStrings.format(CliStrings.EXPORT_SHARED_CONFIG__MSG__NOT_A_DIRECTORY, dir));
            } else if (!saveDirFile.mkdirs()) {
              return ResultBuilder.createGemFireErrorResult(
                  CliStrings.format(CliStrings.EXPORT_SHARED_CONFIG__MSG__CANNOT_CREATE_DIR, dir));
            }
          }
          try {
            if (!saveDirFile.canWrite()) {
              return ResultBuilder.createGemFireErrorResult(
                  CliStrings.format(CliStrings.EXPORT_SHARED_CONFIG__MSG__NOT_WRITEABLE,
                      saveDirFile.getCanonicalPath()));
            }
          } catch (IOException ioex) {
          }
          saveDirString = saveDirFile.getAbsolutePath();
          commandResult.saveIncomingFiles(saveDirString);
          return commandResult;
        } catch (IOException ioex) {
          return ResultBuilder.createShellClientErrorResult(
              CliStrings.EXPORT_SHARED_CONFIG__UNABLE__TO__EXPORT__CONFIG);
        }
      }
      return null;
    }
  }


  public static class ImportInterceptor extends AbstractCliAroundInterceptor {

    public Result preExecution(GfshParseResult parseResult) {
      Map<String, String> paramValueMap = parseResult.getParamValueStrings();

      String zip = paramValueMap.get(CliStrings.IMPORT_SHARED_CONFIG__ZIP);

      zip = StringUtils.trim(zip);

      if (zip == null) {
        return ResultBuilder.createUserErrorResult(CliStrings.format(
            CliStrings.IMPORT_SHARED_CONFIG__PROVIDE__ZIP, CliStrings.IMPORT_SHARED_CONFIG__ZIP));
      }
      if (!zip.endsWith(CliStrings.ZIP_FILE_EXTENSION)) {
        return ResultBuilder.createUserErrorResult(
            CliStrings.format(CliStrings.INVALID_FILE_EXTENTION, CliStrings.ZIP_FILE_EXTENSION));
      }

      FileResult fileResult;

      try {
        fileResult = new FileResult(new String[] {zip});
      } catch (FileNotFoundException fnfex) {
        return ResultBuilder.createUserErrorResult("'" + zip + "' not found.");
      } catch (IOException ioex) {
        return ResultBuilder
            .createGemFireErrorResult(ioex.getClass().getName() + ": " + ioex.getMessage());
      }

      return fileResult;
    }

    @Override
    public Result postExecution(GfshParseResult parseResult, Result commandResult) {
      return null;
    }
  }

}
