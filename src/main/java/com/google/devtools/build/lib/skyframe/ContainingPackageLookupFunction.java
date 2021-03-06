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
package com.google.devtools.build.lib.skyframe;

import com.google.devtools.build.lib.packages.PackageIdentifier;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;

import javax.annotation.Nullable;

/**
 * SkyFunction for {@link ContainingPackageLookupValue}s.
 */
public class ContainingPackageLookupFunction implements SkyFunction {

  @Override
  public SkyValue compute(SkyKey skyKey, Environment env) {
    PackageIdentifier dir = (PackageIdentifier) skyKey.argument();
    PackageLookupValue pkgLookupValue =
        (PackageLookupValue) env.getValue(PackageLookupValue.key(dir));
    if (pkgLookupValue == null) {
      return null;
    }

    if (pkgLookupValue.packageExists()) {
      return ContainingPackageLookupValue.withContainingPackage(dir, pkgLookupValue.getRoot());
    }

    PathFragment parentDir = dir.getPackageFragment().getParentDirectory();
    if (parentDir == null) {
      return ContainingPackageLookupValue.noContainingPackage();
    }
    PackageIdentifier parentId = new PackageIdentifier(dir.getRepository(), parentDir);
    return env.getValue(ContainingPackageLookupValue.key(parentId));
  }

  @Nullable
  @Override
  public String extractTag(SkyKey skyKey) {
    return null;
  }
}
