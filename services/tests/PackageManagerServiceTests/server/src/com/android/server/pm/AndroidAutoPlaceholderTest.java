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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.pm.Signature;
import android.content.pm.SigningDetails;

import com.android.internal.pm.parsing.pkg.ParsedPackage;

import org.junit.Before;
import org.junit.Test;

import java.util.Base64;

public class AndroidAutoPlaceholderTest {
    private static final String PACKAGE = "com.google.android.projection.gearhead";
    private static final String FACTORY = "/product/priv-app/AndroidAutoPlaceholder";
    // Public certificates only: Google AA current/legacy and the AOSP development test key.
    private static final String GOOGLE_CURRENT =
            "MIIFyDCCA7CgAwIBAgIUNDwen+3e/M0u5SffDtUFQETva5owDQYJKoZIhvcNAQELBQAwdDELMAkGA1UE"
            + "BhMCVVMxEzARBgNVBAgTCkNhbGlmb3JuaWExFjAUBgNVBAcTDU1vdW50YWluIFZpZXcxFDASBgNVBAoT"
            + "C0dvb2dsZSBJbmMuMRAwDgYDVQQLEwdBbmRyb2lkMRAwDgYDVQQDEwdBbmRyb2lkMCAXDTIxMDIxNjIw"
            + "MDQyMVoYDzIwNTEwMjE2MjAwNDIxWjB0MQswCQYDVQQGEwJVUzETMBEGA1UECBMKQ2FsaWZvcm5pYTEW"
            + "MBQGA1UEBxMNTW91bnRhaW4gVmlldzEUMBIGA1UEChMLR29vZ2xlIEluYy4xEDAOBgNVBAsTB0FuZHJv"
            + "aWQxEDAOBgNVBAMTB0FuZHJvaWQwggIiMA0GCSqGSIb3DQEBAQUAA4ICDwAwggIKAoICAQDJi2J6VKyy"
            + "LD865BRuAI2bof68hlCGAGZH5udk+UCd9DOEm0vEyp8RYy4wFoRLIynLdQK81Esn5MHQO3JM7oYc+C9L"
            + "s1+s8xYkdSpA/8HBCrzGPjBQG0PKE/DtBJvunXZDtbVSMIYbuVsSbBrAq+nNinaFbaT1gaVN3FSvKf4s"
            + "5j+WDU1IzyF+VmlUXtbpxizCK8PtCP2OSUCGYDDnTTY9xaxFbqyS2XADjDXm6Nv/bHuZbvppBcZNwy9+"
            + "tBbQDd9/+lkPvr9mCfI509XQbDX3TPclw68x51BkRKW5jGrSaGoyfboT7bPX5t2xOKAWU6GgOIqheznW"
            + "sui6/IXqbC1C35bqjVzbAQwNgQFe9j3wN5e4nQDLEH1M8SQDTfQDfyOpDcRAhNkKh44ZH43jbqEJEKia"
            + "6TxvWrMu2uCDbudvv69KehG8Jh2QNhx4kpvBDcpFYivUPb/hupUQeJxEXksDvuCftD6jPeb/hQPAubVw"
            + "ttPrDoaSG2JB7bAsCZKMLHcScSAlftmZkCaf6XZrrlOVg62pI1Wzd3XziQEmsXLT9xrSilyZoGW17idP"
            + "5VYi6TOyZXF86zoiouIFfKWaU6EUw5dWpQWPIuSFNOC1ft2S4XVzBJ14BXcpl6B8ECe5pNCHNi20k5i2"
            + "GuXY+sGr/aQQauLioNW/jPutYkJVLjIHfQIDAQABo1AwTjAMBgNVHRMEBTADAQH/MB0GA1UdDgQWBBSb"
            + "h9SdumPCMjN8yHU2Y4PCGGC/UDAfBgNVHSMEGDAWgBSbh9SdumPCMjN8yHU2Y4PCGGC/UDANBgkqhkiG"
            + "9w0BAQsFAAOCAgEArYMNT7JQdhZc/6NZKa9Q59b7AP2bV2lg8ka3hLX43XNL/YqkZ7h1xMG/B1hSxqZQ"
            + "V+NjZJ1JnDsMKDWR+PUW6DXTR9CXxA2fa/SYMNFkoQH3lnqyz0FMg2St3vT1wP8yF03lV4G5bXFF382z"
            + "5ZBSq8pMsPSF66QssHVd1danMZZEAXxvX+WZwUpKg2liJjSc7QRNW8PaX30qu5OlRwYGczcuJZPy/G0i"
            + "Kxb0sHWWK1cHVlJ05z9WCioSUIqkdbleZgxHjs3h84rb8WD9cxP3/hzaBzu//dOIOVuOqZLgICo6vzH+"
            + "P1rtRH4olDAxQAXmneW+Yw5KYHkgRo0RvzA904m9dVclrnOV0LKZ6L/uYoUXgQQqNsz/fsGPz+FCKQOr"
            + "0A3NV/aqRwbcSn1DjiPkmUZcAi5zRw2HJNdaNA1dHUFPzCfe8rAvrYAeopa0DakwxiEZlMf4msqdjytO"
            + "kkbxe2opVFnA/xIRPLFI59ulNKKFgv40BkniAm3tEwyPR6u1McHza2P48t8jEQc2kND7U1xYXkEksc5H"
            + "9K8foln7NnLb5m3arCWkqHFnwvX90W6wH6Y6hIwIhpHEnHYzkyiFlR35B1stwZCSLvuV3FG0Uk9p7RNq"
            + "jyU0rXlybka5QspMaO254TNNG7hmvIyJAT1qjy6MEFpEYeO1l3QKjp0qjuM=";
    private static final String GOOGLE_LEGACY =
            "MIIDvTCCAqWgAwIBAgIJAMePnkuTQTAGMA0GCSqGSIb3DQEBBQUAMHUxCzAJBgNVBAYTAlVTMRMwEQYD"
            + "VQQIDApDYWxpZm9ybmlhMRYwFAYDVQQHDA1Nb3VudGFpbiBWaWV3MRQwEgYDVQQKDAtHb29nbGUgSW5j"
            + "LjEQMA4GA1UECwwHQW5kcm9pZDERMA8GA1UEAwwIZ2VhcmhlYWQwHhcNMTQwNTI3MjMwNTM0WhcNNDEx"
            + "MDEyMjMwNTM0WjB1MQswCQYDVQQGEwJVUzETMBEGA1UECAwKQ2FsaWZvcm5pYTEWMBQGA1UEBwwNTW91"
            + "bnRhaW4gVmlldzEUMBIGA1UECgwLR29vZ2xlIEluYy4xEDAOBgNVBAsMB0FuZHJvaWQxETAPBgNVBAMM"
            + "CGdlYXJoZWFkMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA050XDkNIsVRMX2wTvVplpCu4"
            + "OtnyNK2v5B7PS+DggmH2yuZiwpTurdKDQ9R9UzxH9U4lsC+mIxXkiBYKIWNVgMtiTgxkEy7cgWvdYHgN"
            + "YpFu8IxZKYDyXes+02pfvpu63MIBD/PnvVFipo1oUrbfetj+mroEpjnA71gUS0Ok+H6XWWsmb8xFHQVM"
            + "oZWEIzsUJ2nhm8EcnPkAPfNZAG++XLPROoRQCaswyYsd42JuYAP3CwZuhDcUbMWmk7rBi9BVQ8gmkrbw"
            + "qo94A7qStLUp3NyCmlKSWHaZ05SspEPwsfctka0oXG5bhgT667EMCzQ+YsFN1oJRL7Qq+mMQjFJs3wID"
            + "AQABo1AwTjAdBgNVHQ4EFgQUGvBfYNeu6JSJUnJZCiaBGsnXztswHwYDVR0jBBgwFoAUGvBfYNeu6JSJ"
            + "UnJZCiaBGsnXztswDAYDVR0TBAUwAwEB/zANBgkqhkiG9w0BAQUFAAOCAQEAlGsDY0EPu3NBSH5k6iw/"
            + "wJh9e3xMwS17ErKGlhyWogxJMzLjAN6g0aCPHxB40IQC+8qAl+RL7VQx6oxttf0m31yUGQPcNYbt2CxB"
            + "TCAr885oLK5t2TAi5tQzhd6ZEYihWSUWUd/X8BQRouxboss9QbBA/iIx0OpDaxiAcq7Cb67TheXZDxGu"
            + "Q8fmHYbLx84pEvm3DQOB/LIMkkpQSfEC1f+oP1zB3urPU/dSvED/LCgOdrpxZ5di7SwSyue+Vq/TZQy3"
            + "4tPygEzD2d8hFlh/yfhWkMizOeIXcayVAQdNn5zpBkuay1skGOjQQ5kTbDcDzigO2R2rqn6HCd9l5Z0W"
            + "IQ==";
    private static final String LOCAL =
            "MIIEqDCCA5CgAwIBAgIJAJNurL4H8gHfMA0GCSqGSIb3DQEBBQUAMIGUMQswCQYDVQQGEwJVUzETMBEG"
            + "A1UECBMKQ2FsaWZvcm5pYTEWMBQGA1UEBxMNTW91bnRhaW4gVmlldzEQMA4GA1UEChMHQW5kcm9pZDEQ"
            + "MA4GA1UECxMHQW5kcm9pZDEQMA4GA1UEAxMHQW5kcm9pZDEiMCAGCSqGSIb3DQEJARYTYW5kcm9pZEBh"
            + "bmRyb2lkLmNvbTAeFw0wODAyMjkwMTMzNDZaFw0zNTA3MTcwMTMzNDZaMIGUMQswCQYDVQQGEwJVUzET"
            + "MBEGA1UECBMKQ2FsaWZvcm5pYTEWMBQGA1UEBxMNTW91bnRhaW4gVmlldzEQMA4GA1UEChMHQW5kcm9p"
            + "ZDEQMA4GA1UECxMHQW5kcm9pZDEQMA4GA1UEAxMHQW5kcm9pZDEiMCAGCSqGSIb3DQEJARYTYW5kcm9p"
            + "ZEBhbmRyb2lkLmNvbTCCASAwDQYJKoZIhvcNAQEBBQADggENADCCAQgCggEBANaTGQTexgskse3HYuDZ"
            + "2CU+Ps1s6x3i/waMqOi8qM1r03hupwqnbOYOuw+ZNVn/2T53qUPn6D1LZLjk/qLT5lbx4meoG7+yMLV4"
            + "wgRDvkxyGLhG9SEVhvA4oU6Jwr44f46+z4/Kw9oe4zDJ6pPQp8PcSvNQIg1QCAcy4ICXF+5qBTNZ5qaU"
            + "7Cyz8oSgpGbIepTYOzEJOmc3Li9kEsBubULxWBjf/gOBzAzURNps3cO4JFgZSAGzJWQTT7/emMkod0jb"
            + "9WdqVA2BVMi7yge54kdVMxHEa5r3b97szI5p58ii0I54JiCUP5lyfTwE/nKZHZnfm644oLIXf6MdW2r+"
            + "6R8CAQOjgfwwgfkwHQYDVR0OBBYEFEhZAFY9JyxGrhGGBaR0GawJyowRMIHJBgNVHSMEgcEwgb6AFEhZ"
            + "AFY9JyxGrhGGBaR0GawJyowRoYGapIGXMIGUMQswCQYDVQQGEwJVUzETMBEGA1UECBMKQ2FsaWZvcm5p"
            + "YTEWMBQGA1UEBxMNTW91bnRhaW4gVmlldzEQMA4GA1UEChMHQW5kcm9pZDEQMA4GA1UECxMHQW5kcm9p"
            + "ZDEQMA4GA1UEAxMHQW5kcm9pZDEiMCAGCSqGSIb3DQEJARYTYW5kcm9pZEBhbmRyb2lkLmNvbYIJAJNu"
            + "rL4H8gHfMAwGA1UdEwQFMAMBAf8wDQYJKoZIhvcNAQEFBQADggEBAHqvlozrUMRBBVEY0NqrrwFbinZa"
            + "J6cVosK0TyIUFf/azgMJWr+kLfcHCHJsIGnlw27drgQAvilFLAhLwn62oX6snb4YLCBOsVMR9FXYJLZW"
            + "2+TcIkCRLXWG/oiVHQGo/rWuWkJgU134NDEFJCJGjDbiLCpe+ZTWHdcwauTJ9pUbo8EvHRkU3cYfGmLa"
            + "Lfgn9gP+pWA7LFQNvXwBnDa6sppCccEX31I828XzgXpJ4O+mDL1/dBd+ek8ZPUP0IgdyZm5MTYPhvVqG"
            + "CHzzTy3sIeJFymwrsBbmg2OAUNLEMO6nwmocSdN2ClirfxqCzJOLSDE4QyS9BAH6EhY6UFcOaE0=";

    private SigningDetails mLocal;
    private SigningDetails mGoogle;
    private PackageSetting mFactory;
    private SharedUserSetting mShared;
    private ParsedPackage mIncoming;

    private static SigningDetails signer(String certificate) throws Exception {
        return new SigningDetails(new Signature[] {
                new Signature(Base64.getDecoder().decode(certificate))},
                SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V3);
    }

    private static PackageSetting setting(SigningDetails signer, String path) {
        PackageSetting result = mock(PackageSetting.class);
        when(result.getPackageName()).thenReturn(PACKAGE);
        when(result.getPathString()).thenReturn(path);
        when(result.getVersionCode()).thenReturn(1L);
        when(result.isSystem()).thenReturn(true);
        when(result.isPrivileged()).thenReturn(true);
        when(result.getSigningDetails()).thenReturn(signer);
        return result;
    }

    @Before
    public void setUp() throws Exception {
        mLocal = signer(LOCAL);
        mGoogle = signer(GOOGLE_CURRENT);
        mFactory = setting(mLocal, FACTORY);
        mShared = new SharedUserSetting(PACKAGE, 0, 0);
        mShared.signatures.mSigningDetails = mLocal;
        mShared.addPackage(mFactory);
        mIncoming = mock(ParsedPackage.class);
        when(mIncoming.getPackageName()).thenReturn(PACKAGE);
        when(mIncoming.getSharedUserId()).thenReturn(PACKAGE);
        when(mIncoming.getSigningDetails()).thenReturn(mGoogle);
        when(mIncoming.getPath()).thenReturn("/data/app/random/gearhead");
        when(mIncoming.getLongVersionCode()).thenReturn(177663624L);
        when(mIncoming.isDeclaredHavingCode()).thenReturn(true);
    }

    @Test
    public void acceptsBothGoogleCertificatesButNotOtherSigners() throws Exception {
        assertTrue(AndroidAutoPlaceholder.isGoogle(mGoogle));
        assertTrue(AndroidAutoPlaceholder.isGoogle(signer(GOOGLE_LEGACY)));
        assertFalse(AndroidAutoPlaceholder.isGoogle(mLocal));
        assertFalse(AndroidAutoPlaceholder.isGoogle(SigningDetails.UNKNOWN));
        assertFalse(AndroidAutoPlaceholder.isGoogle(null));
        SigningDetails multi = new SigningDetails(new Signature[] {
                mGoogle.getSignatures()[0], mLocal.getSignatures()[0]},
                SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V3);
        assertFalse(AndroidAutoPlaceholder.isGoogle(multi));
    }

    @Test
    public void rotatedSignerMustGrantInstalledDataCapability() throws Exception {
        // SigningDetails receives a cryptographically verified lineage from the APK parser.
        Signature ancestor = new Signature(mGoogle.getSignatures()[0].toByteArray());
        SigningDetails rotated = new SigningDetails(mLocal.getSignatures(),
                SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V3,
                new Signature[] {ancestor, mLocal.getSignatures()[0]});
        assertFalse(AndroidAutoPlaceholder.isGoogle(rotated));
        ancestor.setFlags(SigningDetails.CertCapabilities.INSTALLED_DATA);
        assertTrue(AndroidAutoPlaceholder.isGoogle(rotated));
    }

    @Test
    public void firstInstallUsesFactoryAnchorAndDoesNotMutateSharedUid() {
        assertTrue(AndroidAutoPlaceholder.canReplace(mFactory, null, mShared, mIncoming, false));
        assertSame(mLocal, mShared.getSigningDetails());
        assertSame(mGoogle, mIncoming.getSigningDetails());
    }

    @Test
    public void reconcileCanUseFactoryAnchorWithScannedDataPath() {
        PackageSetting scanned = setting(mLocal, "/data/app/random/gearhead");
        assertTrue(AndroidAutoPlaceholder.canReplace(scanned, mFactory, mShared, mIncoming, false));
        assertFalse(AndroidAutoPlaceholder.canReplace(scanned, null, mShared, mIncoming, false));
    }

    @Test
    public void rejectsUntrustedIncomingSigner() {
        when(mIncoming.getSigningDetails()).thenReturn(mLocal);
        assertFalse(AndroidAutoPlaceholder.canReplace(mFactory, null, mShared, mIncoming, false));
        when(mIncoming.getSigningDetails()).thenReturn(SigningDetails.UNKNOWN);
        assertFalse(AndroidAutoPlaceholder.canReplace(mFactory, null, mShared, mIncoming, false));
    }

    @Test
    public void rejectsUnknownSharedUidSignerAndNonPlaceholderFactoryVersion() {
        mShared.signatures.mSigningDetails = SigningDetails.UNKNOWN;
        assertFalse(AndroidAutoPlaceholder.canReplace(mFactory, null, mShared, mIncoming, false));
        mShared.signatures.mSigningDetails = mLocal;
        when(mFactory.getVersionCode()).thenReturn(2L);
        assertFalse(AndroidAutoPlaceholder.canReplace(mFactory, null, mShared, mIncoming, false));
    }

    @Test
    public void googleUpdatesAfterHandoffUseNormalVerification() {
        PackageSetting installed = setting(mGoogle, "/data/app/random/gearhead");
        mShared.signatures.mSigningDetails = mGoogle;
        assertFalse(AndroidAutoPlaceholder.canReplace(installed, mFactory, mShared, mIncoming, false));
    }

    @Test
    public void factoryCertificateComparisonAcceptsOnlyAnchoredGoogleUpdate() {
        assertTrue(AndroidAutoPlaceholder.isTrustedFactoryUpdate(PACKAGE, mFactory, mGoogle));
        assertFalse(AndroidAutoPlaceholder.isTrustedFactoryUpdate(PACKAGE, mFactory, mLocal));
        assertFalse(AndroidAutoPlaceholder.isTrustedFactoryUpdate(PACKAGE, null, mGoogle));
        assertFalse(AndroidAutoPlaceholder.isTrustedFactoryUpdate("com.example.other",
                mFactory, mGoogle));
        when(mFactory.getPathString()).thenReturn("/data/app/AndroidAutoPlaceholder");
        assertFalse(AndroidAutoPlaceholder.isTrustedFactoryUpdate(PACKAGE, mFactory, mGoogle));
    }

    @Test
    public void rebootCanRecoverFactorySignerInDedicatedUid() {
        PackageSetting installed = setting(mGoogle, "/data/app/random/gearhead");
        assertTrue(AndroidAutoPlaceholder.canReplace(installed, mFactory, mShared, mIncoming, false));
    }

    private void prepareRestore() {
        when(mIncoming.getSigningDetails()).thenReturn(mLocal);
        when(mIncoming.getPath()).thenReturn(FACTORY);
        when(mIncoming.getLongVersionCode()).thenReturn(1L);
        when(mIncoming.isDeclaredHavingCode()).thenReturn(false);
        mShared.signatures.mSigningDetails = mGoogle;
    }

    @Test
    public void restoresFactoryWithGooglePackageAndUid() {
        prepareRestore();
        PackageSetting installed = setting(mGoogle, "/data/app/random/gearhead");
        assertTrue(AndroidAutoPlaceholder.canReplace(installed, mFactory, mShared, mIncoming, true));
    }

    @Test
    public void restoresUidAfterUninstallAlreadyEnabledFactoryPackage() {
        prepareRestore();
        assertTrue(AndroidAutoPlaceholder.canReplace(mFactory, null, mShared, mIncoming, true));
        assertSame(mGoogle, mShared.getSigningDetails());
    }

    @Test
    public void restoreCannotBeRequestedByInstallerOrFromDataPartition() {
        prepareRestore();
        assertFalse(AndroidAutoPlaceholder.canReplace(mFactory, null, mShared, mIncoming, false));
        when(mIncoming.getPath()).thenReturn("/data/app/AndroidAutoPlaceholder");
        assertFalse(AndroidAutoPlaceholder.canReplace(mFactory, null, mShared, mIncoming, true));
    }

    @Test
    public void restoreMustBeCodeFreeAndVersionOne() {
        prepareRestore();
        when(mIncoming.isDeclaredHavingCode()).thenReturn(true);
        assertFalse(AndroidAutoPlaceholder.canReplace(mFactory, null, mShared, mIncoming, true));
        when(mIncoming.isDeclaredHavingCode()).thenReturn(false);
        when(mIncoming.getLongVersionCode()).thenReturn(2L);
        assertFalse(AndroidAutoPlaceholder.canReplace(mFactory, null, mShared, mIncoming, true));
    }

    @Test
    public void rejectsOtherPackagesAndSharedUids() {
        when(mIncoming.getPackageName()).thenReturn("com.example.other");
        assertFalse(AndroidAutoPlaceholder.canReplace(mFactory, null, mShared, mIncoming, false));
        when(mIncoming.getPackageName()).thenReturn(PACKAGE);
        when(mIncoming.getSharedUserId()).thenReturn("android.uid.system");
        assertFalse(AndroidAutoPlaceholder.canReplace(mFactory, null, mShared, mIncoming, false));
        when(mIncoming.getSharedUserId()).thenReturn(PACKAGE);
        assertFalse(AndroidAutoPlaceholder.canReplace(mFactory, null, null, mIncoming, false));
        assertFalse(AndroidAutoPlaceholder.canReplace(mFactory, null,
                new SharedUserSetting("android.uid.system", 0, 0), mIncoming, false));
    }

    @Test
    public void rejectsSharedUidWithOtherMembersIncludingDisabledPackages() {
        PackageSetting other = setting(mLocal, FACTORY);
        when(other.getPackageName()).thenReturn("com.example.other");
        mShared.getDisabledPackageSettings().add(other);
        assertFalse(AndroidAutoPlaceholder.canReplace(mFactory, null, mShared, mIncoming, false));
        mShared.getDisabledPackageSettings().remove(other);
        mShared.addPackage(other);
        assertFalse(AndroidAutoPlaceholder.canReplace(mFactory, null, mShared, mIncoming, false));
    }

    @Test
    public void rejectsMissingOrUnprivilegedOrRelocatedFactory() {
        assertFalse(AndroidAutoPlaceholder.canReplace(null, null, mShared, mIncoming, false));
        when(mFactory.isPrivileged()).thenReturn(false);
        assertFalse(AndroidAutoPlaceholder.canReplace(mFactory, null, mShared, mIncoming, false));
        when(mFactory.isPrivileged()).thenReturn(true);
        when(mFactory.isSystem()).thenReturn(false);
        assertFalse(AndroidAutoPlaceholder.canReplace(mFactory, null, mShared, mIncoming, false));
        when(mFactory.isSystem()).thenReturn(true);
        when(mFactory.getPathString()).thenReturn("/data/app/AndroidAutoPlaceholder");
        assertFalse(AndroidAutoPlaceholder.canReplace(mFactory, null, mShared, mIncoming, false));
    }

    @Test
    public void supportsSystemProductMountPath() {
        when(mFactory.getPathString()).thenReturn("/system" + FACTORY);
        assertTrue(AndroidAutoPlaceholder.canReplace(mFactory, null, mShared, mIncoming, false));
    }
}
