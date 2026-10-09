/*
 * Copyright (C) 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.pm;

import android.content.pm.SigningDetails;

import com.android.internal.pm.parsing.pkg.ParsedPackage;
import com.android.server.pm.pkg.PackageStateInternal;

/** Signing handoff between the code-free factory placeholder and genuine Android Auto. */
final class AndroidAutoPlaceholder {
    private static final String PACKAGE = "com.google.android.projection.gearhead";
    private static final String DIRECTORY = "/priv-app/AndroidAutoPlaceholder";
    // Google AA certificates (9ca91f9e... and its a60bd681... successor, in SHA-256).
    private static final String GOOGLE_LEGACY =
            "fdb00c43dbde8b51cb312aa81d3b5fa17713adb94b28f598d77f8eb89daceedf";
    private static final String GOOGLE_CURRENT =
            "1ca8dcc0bed3cbd872d2cb791200c0292ca9975768a82d676b8b424fb65b5295";

    private AndroidAutoPlaceholder() {}

    static boolean isSystemPlaceholder(ParsedPackage pkg, boolean systemScan) {
        return systemScan && PACKAGE.equals(pkg.getPackageName())
                && PACKAGE.equals(pkg.getSharedUserId())
                && pkg.getLongVersionCode() == 1 && !pkg.isDeclaredHavingCode()
                && isFactoryPath(pkg.getPath());
    }

    private static boolean isFactoryPath(String path) {
        return ("/product" + DIRECTORY).equals(path)
                || ("/system/product" + DIRECTORY).equals(path);
    }

    private static boolean isFactoryPlaceholder(PackageSetting factory) {
        return factory != null && PACKAGE.equals(factory.getPackageName())
                && factory.isSystem() && factory.isPrivileged()
                && factory.getVersionCode() == 1 && isFactoryPath(factory.getPathString())
                && known(factory.getSigningDetails()) && !isGoogle(factory.getSigningDetails());
    }

    private static boolean known(SigningDetails details) {
        return details != null && details != SigningDetails.UNKNOWN && details.hasSignatures();
    }

    private static boolean same(SigningDetails first, SigningDetails second) {
        return known(first) && known(second) && first.signaturesMatchExactly(second);
    }

    static boolean isGoogle(SigningDetails details) {
        return known(details) && details.getSignatures().length == 1
                && (details.checkCapability(GOOGLE_LEGACY,
                        SigningDetails.CertCapabilities.INSTALLED_DATA)
                || details.checkCapability(GOOGLE_CURRENT,
                        SigningDetails.CertCapabilities.INSTALLED_DATA));
    }

    // Only substitutes the factory-to-update comparison. The installed Google package's
    // signer and shared UID must still pass normal verification on subsequent updates.
    static boolean isTrustedFactoryUpdate(String packageName, PackageSetting factory,
            SigningDetails incoming) {
        return PACKAGE.equals(packageName) && isFactoryPlaceholder(factory) && isGoogle(incoming);
    }

    private static boolean onlyAndroidAuto(SharedUserSetting shared) {
        if (shared == null || !PACKAGE.equals(shared.name)) {
            return false;
        }
        for (PackageStateInternal state : shared.getPackageStates()) {
            if (!PACKAGE.equals(state.getPackageName())) {
                return false;
            }
        }
        for (PackageStateInternal state : shared.getDisabledPackageStates()) {
            if (!PACKAGE.equals(state.getPackageName())) {
                return false;
            }
        }
        return true;
    }

    /**
     * The factory setting comes from Settings, never from the incoming /data APK. A forward
     * handoff needs that trusted anchor. A reverse handoff only accepts a code-free placeholder
     * being scanned from the system image, never an installer-submitted APK. No signatures are
     * forged or merged: reconciliation commits the actual incoming signer to this dedicated UID.
     */
    static boolean canReplace(PackageSetting existing, PackageSetting factory,
            SharedUserSetting shared, ParsedPackage incoming, boolean systemScan) {
        if (existing == null || !PACKAGE.equals(existing.getPackageName())
                || !existing.isSystem() || !existing.isPrivileged()
                || !PACKAGE.equals(incoming.getPackageName())
                || !PACKAGE.equals(incoming.getSharedUserId()) || !onlyAndroidAuto(shared)) {
            return false;
        }
        SigningDetails old = existing.getSigningDetails();
        SigningDetails next = incoming.getSigningDetails();
        SigningDetails uid = shared.getSigningDetails();
        if (isSystemPlaceholder(incoming, systemScan) && known(next)) {
            // Uninstall updates may already have restored the factory PackageSetting, while
            // the dedicated shared UID still holds Google's signer. Handle both states.
            return (isGoogle(old) || same(old, next))
                    && (isGoogle(uid) || same(uid, next))
                    && (isGoogle(old) || isGoogle(uid));
        }
        if (factory == null) {
            factory = existing;
        }
        if (!isTrustedFactoryUpdate(incoming.getPackageName(), factory, next)) {
            return false;
        }
        SigningDetails local = factory.getSigningDetails();
        return (same(old, local) || isGoogle(old)) && (same(uid, local) || isGoogle(uid))
                // Google-to-Google updates must continue to use ordinary lineage checks.
                && (same(old, local) || same(uid, local));
    }
}
