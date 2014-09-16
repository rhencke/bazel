// Copyright 2014 Google Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.rules.objc;

import static com.google.devtools.build.lib.rules.objc.ArtifactListAttribute.NON_ARC_SRCS;
import static com.google.devtools.build.lib.rules.objc.ArtifactListAttribute.SRCS;
import static com.google.devtools.build.lib.rules.objc.IosSdkCommands.BIN_DIR;
import static com.google.devtools.build.lib.rules.objc.IosSdkCommands.MINIMUM_OS_VERSION;
import static com.google.devtools.build.lib.rules.objc.IosSdkCommands.TARGET_DEVICE_FAMILIES;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.ASSET_CATALOG;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.HEADER;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.INCLUDE;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.XCASSETS_DIR;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.view.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.view.RuleContext;
import com.google.devtools.build.lib.view.actions.SpawnAction;
import com.google.devtools.build.xcode.common.TargetDeviceFamily;
import com.google.devtools.build.xcode.util.Interspersing;
import com.google.devtools.build.xcode.xcodegen.proto.XcodeGenProtos;
import com.google.devtools.build.xcode.xcodegen.proto.XcodeGenProtos.TargetControl;

import java.util.Locale;

/**
 * Utility code for creating actions used by Objective-C rules.
 */
public class ObjcActionsBuilder {
  private ObjcActionsBuilder() {
    throw new UnsupportedOperationException("static-only");
  }

  /**
   * Creates a {@code SpawnAction.Builder} that does not get registered automatically when created.
   * This is to make the {@code SpawnAction}s generated by this class consistent with actions of
   * other types.
   */
  private static SpawnAction.Builder spawnActionBuilder(RuleContext ruleContext) {
    return new SpawnAction.Builder(ruleContext).setRegisterSpawnAction(false);
  }

  private static SpawnAction.Builder spawnOnDarwinActionBuilder(RuleContext ruleContext) {
    return spawnActionBuilder(ruleContext)
        .setExecutionInfo(ImmutableMap.of(ExecutionRequirements.REQUIRES_DARWIN, ""));
  }

  static final PathFragment CLANG = new PathFragment(BIN_DIR + "/clang");
  static final PathFragment CLANG_PLUSPLUS = new PathFragment(BIN_DIR + "/clang++");

  // TODO(bazel-team): Reference a rule target rather than a jar file when Darwin runfiles work
  // better.
  private static SpawnAction.Builder spawnJavaOnDarwinActionBuilder(
      RuleContext ruleContext, String deployJarAttribute) {
    Artifact deployJarArtifact = ruleContext.getPrerequisiteArtifact(deployJarAttribute, Mode.HOST);
    return spawnOnDarwinActionBuilder(ruleContext)
        .setExecutable(new PathFragment("/usr/bin/java"))
        .addArgument("-jar")
        .addInputArgument(deployJarArtifact);
  }

  private static Action compileAction(RuleContext ruleContext, Artifact sourceFile,
      ObjcProvider provider, String... otherFlags) {
    return spawnOnDarwinActionBuilder(ruleContext)
        .setMnemonic("Compile")
        .setExecutable(CLANG)
        .addArguments(IosSdkCommands.compileArgsForClang(objcConfiguration(ruleContext)))
        .addArguments(
            IosSdkCommands.commonLinkAndCompileArgsForClang(objcConfiguration(ruleContext)))
        .addArguments(Interspersing.beforeEach(
            "-iquote",
            PathFragment.safePathStrings(
                ObjcCommon.userHeaderSearchPaths(ruleContext.getConfiguration()))))
        .addArguments(Interspersing.beforeEach(
            "-include", Artifact.asExecPaths(ObjcRuleClasses.pchFile(ruleContext).asSet())))
        .addArguments(Interspersing.beforeEach(
            "-I", PathFragment.safePathStrings(provider.get(INCLUDE))))
        .addArguments(otherFlags)
        .addArguments(ObjcCommon.combinedOptions(ruleContext).getCopts())
        .addArgument("-c").addInputArgument(sourceFile)
        .addArgument("-o").addOutputArgument(ObjcRuleClasses.objFile(ruleContext, sourceFile))
        .addInputs(provider.get(HEADER))
        .addInputs(ObjcRuleClasses.pchFile(ruleContext).asSet())
        .build();
  }

  /**
   * Creates actions to compile each source file individually, and link all the compiled object
   * files into a single archive library.
   * @return the {@code Action}s that were created
   */
  static Iterable<Action> compileAndLinkActions(RuleContext ruleContext, ObjcProvider provider) {
    ObjcConfiguration configuration = objcConfiguration(ruleContext);

    ImmutableList.Builder<Action> result = new ImmutableList.Builder<>();
    for (Artifact sourceFile : SRCS.get(ruleContext)) {
      result.add(compileAction(ruleContext, sourceFile, provider, "-fobjc-arc"));
    }
    for (Artifact nonArcSourceFile : NON_ARC_SRCS.get(ruleContext)) {
      result.add(compileAction(ruleContext, nonArcSourceFile, provider, "-fno-objc-arc"));
    }

    for (Artifact outputAFile : ObjcRuleClasses.outputAFile(ruleContext).asSet()) {
      Iterable<Artifact> objFiles = ObjcRuleClasses.objFiles(ruleContext,
          Iterables.concat(SRCS.get(ruleContext), NON_ARC_SRCS.get(ruleContext)));

      result.add(spawnOnDarwinActionBuilder(ruleContext)
          .setMnemonic("Link")
          .setExecutable(new PathFragment(BIN_DIR + "/libtool"))
          .addArgument("-static")
          .addInputArguments(objFiles)
          .addArguments("-arch_only", configuration.getIosCpu())
          .addArguments("-syslibroot", IosSdkCommands.sdkDir(configuration))
          .addArgument("-o").addOutputArgument(outputAFile)
          .build());
    }

    return result.build();
  }

  /**
   * Registers all actions in a collection.
   */
  static void registerAll(RuleContext ruleContext, Iterable<? extends Action> actions) {
    for (Action action : actions) {
      ruleContext.registerAction(action);
    }
  }

  /**
   * Generates actions needed to create an Xcode project file.
   */
  static Iterable<Action> xcodegenActions(RuleContext ruleContext,
      Iterable<TargetControl> targets) {
    Artifact pbxproj = ObjcRuleClasses.pbxprojArtifact(ruleContext);
    XcodeGenProtos.Control xcodegenControl = XcodeGenProtos.Control.newBuilder()
        .setPbxproj(pbxproj.getExecPathString())
        .addAllTarget(targets)
        .build();
    Artifact controlFile = ObjcRuleClasses.pbxprojControlArtifact(ruleContext);
    return ImmutableList.<Action>of(
        new WriteXcodeGenControlFileAction(
            ruleContext.getActionOwner(), controlFile, xcodegenControl),
        spawnActionBuilder(ruleContext)
            .setMnemonic("Generate project")
            .setExecutable(ruleContext.getExecutablePrerequisite("$xcodegen", Mode.HOST))
            .addArgument("--control")
            .addInputArgument(controlFile)
            .addOutput(pbxproj)
            .build());
  }

  /**
   * Creates actions to convert all files specified by the strings attribute into binary format.
   */
  static Iterable<Action> convertStringsActions(RuleContext ruleContext) {
    ImmutableList.Builder<Action> result = new ImmutableList.Builder<>();
    for (BundleableFile stringsFile : BundleableFile.stringsFilesFromRule(ruleContext)) {
      result.add(spawnActionBuilder(ruleContext)
          .setMnemonic("Convert plist to binary")
          .setExecutable(ruleContext.getExecutablePrerequisite("$plmerge", Mode.HOST))
          .addArgument("--source_file").addInputArgument(stringsFile.getOriginal())
          .addArgument("--out_file").addOutputArgument(stringsFile.getBundled())
          .build());
    }
    return result.build();
  }

  /**
   * Creates actions to convert all files specified by the xibs attribute into nib format.
   */
  static Iterable<Action> convertXibsActions(RuleContext ruleContext) {
    ImmutableList.Builder<Action> result = new ImmutableList.Builder<>();
    for (BundleableFile xibFile : BundleableFile.xibFilesFromRule(ruleContext)) {
      result.add(spawnOnDarwinActionBuilder(ruleContext)
          .setMnemonic("Compile xib")
          .setExecutable(new PathFragment("/usr/bin/ibtool"))
          .addArguments("--minimum-deployment-target", MINIMUM_OS_VERSION)
          .addArgument("--compile").addOutputArgument(xibFile.getBundled())
          .addInputArgument(xibFile.getOriginal())
          .build());
    }
    return result.build();
  }

  static Action actoolzipAction(RuleContext ruleContext, ObjcProvider provider) {
    Artifact actoolOutputZip = ObjcRuleClasses.actoolOutputZip(ruleContext);
    // TODO(bazel-team): Do not use the deploy jar explicitly here. There is currently a bug where
    // we cannot .setExecutable({java_binary target}) and set REQUIRES_DARWIN in the execution info.
    // Note that below we set the archive root to the empty string. This means that the generated
    // zip file will be rooted at the bundle root, and we have to prepend the bundle root to each
    // entry when merging it with the final .ipa file.
    SpawnAction.Builder actionBuilder =
        spawnJavaOnDarwinActionBuilder(ruleContext, "$actoolzip_deploy")
            .setMnemonic("Compile asset catalogs")
            .addInputs(provider.get(ASSET_CATALOG))
            // The next three arguments are positional, i.e. they don't have flags before them.
            .addOutputArgument(actoolOutputZip)
            .addArgument("") // archive root
            .addArgument(IosSdkCommands.ACTOOL_PATH)
            .addArguments("--platform",
                objcConfiguration(ruleContext).getPlatform().getLowerCaseNameInPlist())
            .addArguments("--minimum-deployment-target", MINIMUM_OS_VERSION);
    for (TargetDeviceFamily targetDeviceFamily : TARGET_DEVICE_FAMILIES) {
      actionBuilder.addArguments(
          "--target-device", targetDeviceFamily.name().toLowerCase(Locale.US));
    }
    return actionBuilder
        .addArguments(PathFragment.safePathStrings(provider.get(XCASSETS_DIR)))
        .addArguments(Interspersing.beforeEach(
            "--app-icon", ObjcBinaryRule.appIcon(ruleContext).asSet()))
        .addArguments(Interspersing.beforeEach(
            "--launch-image", ObjcBinaryRule.launchImage(ruleContext).asSet()))
        .build();
  }

  @VisibleForTesting
  static Iterable<String> commonMomczipArguments(ObjcConfiguration configuration) {
    return ImmutableList.of(
        "-XD_MOMC_SDKROOT=" + IosSdkCommands.sdkDir(configuration),
        "-XD_MOMC_IOS_TARGET_VERSION=" + IosSdkCommands.MINIMUM_OS_VERSION,
        "-MOMC_PLATFORMS", configuration.getPlatform().getLowerCaseNameInPlist(),
        "-XD_MOMC_TARGET_VERSION=10.6");
  }

  static Iterable<Action> momczipActions(RuleContext ruleContext) {
    ObjcConfiguration configuration = objcConfiguration(ruleContext);
    ImmutableList.Builder<Action> result = new ImmutableList.Builder<>();
    for (Xcdatamodel datamodel : XcdatamodelsInfo.fromRule(ruleContext).getXcdatamodels()) {
      result.add(spawnJavaOnDarwinActionBuilder(ruleContext, "$momczip_deploy")
          .addOutputArgument(datamodel.getOutputZip())
          .addArgument(datamodel.archiveRootForMomczip())
          .addArgument(IosSdkCommands.momcPath(configuration))
          .addArguments(commonMomczipArguments(configuration))
          .addArgument(datamodel.getContainer().getPathString())
          .addInputs(datamodel.getInputs())
          .build());
    }
    return result.build();
  }

  /**
   * Creates actions that are common to objc_binary and objc_library rules.
   */
  static Iterable<Action> baseActions(RuleContext ruleContext, ObjcProvider provider,
      XcodeProvider xcodeProvider) {
    // Don't use Iterables.concat(Iterable...) because it introduces an unchecked warning.
    return Iterables.concat(
        Iterables.concat(
          compileAndLinkActions(ruleContext, provider),
          xcodegenActions(ruleContext, xcodeProvider.getTargets()),
          convertStringsActions(ruleContext),
          convertXibsActions(ruleContext)),
        momczipActions(ruleContext)
    );
  }

  static ObjcConfiguration objcConfiguration(RuleContext ruleContext) {
    return ruleContext.getConfiguration().getFragment(ObjcConfiguration.class);
  }
}
