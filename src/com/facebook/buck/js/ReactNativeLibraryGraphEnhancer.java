/*
 * Copyright 2015-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.js;

import com.facebook.buck.android.Aapt2Compile;
import com.facebook.buck.android.AndroidResource;
import com.facebook.buck.android.AndroidResourceDescription;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.Tool;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;

public class ReactNativeLibraryGraphEnhancer {

  private static final Flavor REACT_NATIVE_BUNDLE_FLAVOR = InternalFlavor.of("bundle");
  private static final Flavor REACT_NATIVE_ANDROID_RES_FLAVOR = InternalFlavor.of("android_res");

  private final ReactNativeBuckConfig buckConfig;

  public ReactNativeLibraryGraphEnhancer(ReactNativeBuckConfig buckConfig) {
    this.buckConfig = buckConfig;
  }

  private ReactNativeBundle createReactNativeBundle(
      ProjectFilesystem projectFilesystem,
      BuildRuleParams baseParams,
      BuildRuleResolver resolver,
      SourcePathRuleFinder ruleFinder,
      BuildTarget target,
      CoreReactNativeLibraryArg args,
      ReactNativePlatform platform) {
    Tool jsPackager = buckConfig.getPackager(resolver);

    ImmutableList<String> packagerFlags;
    if (args.getPackagerFlags().isPresent()) {
      if (args.getPackagerFlags().get().isLeft()) {
        packagerFlags = ImmutableList.copyOf(args.getPackagerFlags().get().getLeft().split("\\s+"));
      } else {
        packagerFlags = args.getPackagerFlags().get().getRight();
      }
    } else {
      packagerFlags = ImmutableList.of();
    }

    return new ReactNativeBundle(
        projectFilesystem,
        baseParams
            .withBuildTarget(target)
            .withDeclaredDeps(
                ImmutableSortedSet.<BuildRule>naturalOrder()
                    .addAll(ruleFinder.filterBuildRuleInputs(args.getEntryPath()))
                    .addAll(ruleFinder.filterBuildRuleInputs(args.getSrcs()))
                    .addAll(jsPackager.getDeps(ruleFinder))
                    .build())
            .withoutExtraDeps(),
        args.getEntryPath(),
        args.getSrcs(),
        ReactNativeFlavors.useUnbundling(baseParams.getBuildTarget()),
        ReactNativeFlavors.useIndexedUnbundling(baseParams.getBuildTarget()),
        ReactNativeFlavors.isDevMode(baseParams.getBuildTarget()),
        ReactNativeFlavors.exposeSourceMap(baseParams.getBuildTarget()),
        args.getBundleName(),
        packagerFlags,
        jsPackager,
        platform);
  }

  public AndroidReactNativeLibrary enhanceForAndroid(
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      AndroidReactNativeLibraryDescriptionArg args) {

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    BuildTarget originalBuildTarget = params.getBuildTarget();
    ReactNativeBundle bundle =
        createReactNativeBundle(
            projectFilesystem,
            params,
            resolver,
            ruleFinder,
            BuildTarget.builder(originalBuildTarget).addFlavors(REACT_NATIVE_BUNDLE_FLAVOR).build(),
            args,
            ReactNativePlatform.ANDROID);
    resolver.addToIndex(bundle);

    ImmutableList.Builder<BuildRule> extraDeps = ImmutableList.builder();
    extraDeps.add(bundle);
    if (args.getPackage().isPresent()) {
      BuildRuleParams paramsForResource =
          params
              .withAppendedFlavor(REACT_NATIVE_ANDROID_RES_FLAVOR)
              .withExtraDeps(ImmutableSortedSet.of(bundle));

      SourcePath resources =
          new ExplicitBuildTargetSourcePath(bundle.getBuildTarget(), bundle.getResources());
      BuildRule resource =
          new AndroidResource(
              projectFilesystem,
              paramsForResource,
              ruleFinder,
              /* deps */ ImmutableSortedSet.of(),
              resources,
              /* resSrcs */ ImmutableSortedMap.of(),
              args.getPackage().get(),
              /* assets */ null,
              /* assetsSrcs */ ImmutableSortedMap.of(),
              /* manifest */ null,
              /* hasWhitelistedStrings */ false);
      resolver.addToIndex(resource);
      extraDeps.add(resource);

      Aapt2Compile aapt2Compile =
          new Aapt2Compile(
              projectFilesystem,
              paramsForResource.withAppendedFlavor(AndroidResourceDescription.AAPT2_COMPILE_FLAVOR),
              resources);
      resolver.addToIndex(aapt2Compile);
    }

    return new AndroidReactNativeLibrary(
        projectFilesystem, params.copyAppendingExtraDeps(extraDeps.build()), bundle);
  }

  public ReactNativeBundle enhanceForIos(
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      ReactNativeLibraryArg args) {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    return createReactNativeBundle(
        projectFilesystem,
        params,
        resolver,
        ruleFinder,
        params.getBuildTarget(),
        args,
        ReactNativePlatform.IOS);
  }
}
