package android.security.keystore2;

import android.security.keystore.KeyProperties;
import android.content.pm.IPackageManager;
import android.os.ServiceManager;
import android.os.SystemProperties;

import android.security.keystore2.shaded.org.bouncycastle.asn1.ASN1Boolean;
import android.security.keystore2.shaded.org.bouncycastle.asn1.ASN1Encodable;
import android.security.keystore2.shaded.org.bouncycastle.asn1.ASN1EncodableVector;
import android.security.keystore2.shaded.org.bouncycastle.asn1.ASN1Enumerated;
import android.security.keystore2.shaded.org.bouncycastle.asn1.ASN1ObjectIdentifier;
import android.security.keystore2.shaded.org.bouncycastle.asn1.ASN1OctetString;
import android.security.keystore2.shaded.org.bouncycastle.asn1.ASN1Sequence;
import android.security.keystore2.shaded.org.bouncycastle.asn1.ASN1TaggedObject;
import android.security.keystore2.shaded.org.bouncycastle.asn1.DEROctetString;
import android.security.keystore2.shaded.org.bouncycastle.asn1.DERSequence;
import android.security.keystore2.shaded.org.bouncycastle.asn1.DERTaggedObject;
import android.security.keystore2.shaded.org.bouncycastle.asn1.x509.Extension;
import android.security.keystore2.shaded.org.bouncycastle.cert.X509CertificateHolder;
import android.security.keystore2.shaded.org.bouncycastle.cert.X509v3CertificateBuilder;
import android.security.keystore2.shaded.org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import android.security.keystore2.shaded.org.bouncycastle.openssl.PEMKeyPair;
import android.security.keystore2.shaded.org.bouncycastle.openssl.PEMParser;
import android.security.keystore2.shaded.org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import android.security.keystore2.shaded.org.bouncycastle.operator.ContentSigner;
import android.security.keystore2.shaded.org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import android.security.keystore2.shaded.org.bouncycastle.util.io.pem.PemReader;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.security.KeyPair;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@SuppressWarnings("MissingNullability")
/**
 * @hide
 */
public final class CertHack {
    private CertHack() {}
    private static final ASN1ObjectIdentifier OID = new ASN1ObjectIdentifier("1.3.6.1.4.1.11129.2.1.17");
    private static final String DISABLE_PROPERTY = "persist.sys.dumbdroid.disable_certhack";
    private static final int SECURITY_LEVEL_TRUSTED_ENVIRONMENT = 1;

    private static final CertificateFactory certificateFactory;

    static {
        try {
            certificateFactory = CertificateFactory.getInstance("X.509");
        } catch (Throwable t) {
            Logger.e("", t);
            throw new RuntimeException(t);
        }
    }

    static boolean canHack() {
        return !isHackGloballyDisabled() && !keyboxes.isEmpty();
    }

    public static boolean canHackForUid(int uid) {
        return canHack() && !isHackDisabledForUid(uid);
    }

    public static boolean isHackGloballyDisabled() {
        return SystemProperties.getBoolean(DISABLE_PROPERTY, false);
    }

    public static boolean isHackDisabledForUid(int uid) {
        try {
            if (isHackGloballyDisabled()) {
                Logger.i("disabling CertHack globally using property " + DISABLE_PROPERTY);
                return true;
            }
        } catch (Throwable t) {
            Logger.e("isHackDisabledForUid failed", t);
        }
        return false;
    }

    private static PEMKeyPair parseKeyPair(String key) throws Throwable {
        try (PEMParser parser = new PEMParser(new StringReader(UtilKt.trimLine(key)))) {
            return (PEMKeyPair) parser.readObject();
        }
    }

    private static Certificate parseCert(String cert) throws Throwable {
        try (PemReader reader = new PemReader(new StringReader(UtilKt.trimLine(cert)))) {
            return certificateFactory.generateCertificate(new ByteArrayInputStream(reader.readPemObject().getContent()));
        }
    }

    private static byte[] getByteArrayFromAsn1(ASN1Encodable asn1Encodable) throws CertificateParsingException {
        if (!(asn1Encodable instanceof DEROctetString derOctectString)) {
            throw new CertificateParsingException("Expected DEROctetString");
        }
        return derOctectString.getOctets();
    }

    private static byte[] getRootOfTrustByteArray(ASN1Sequence rootOfTrust, int index, String label)
            throws CertificateParsingException {
        if (rootOfTrust.size() <= index) {
            throw new CertificateParsingException("Missing " + label + " in root of trust");
        }
        return getByteArrayFromAsn1(rootOfTrust.getObjectAt(index));
    }

    record KeyBox(PEMKeyPair pemKeyPair, KeyPair keyPair, List<Certificate> certificates) {
    }

    static Map<String, KeyBox> readFromXml(String data) {
        Map<String, KeyBox> keyboxes = new HashMap<>();
        if (data == null) {
            Logger.i("clear all keyboxes");
            return null;
        }
        XMLParser xmlParser = new XMLParser(data);

        try {
            int numberOfKeyboxes = Integer.parseInt(Objects.requireNonNull(xmlParser.obtainPath(
                    "AndroidAttestation.NumberOfKeyboxes").get("text")));
            Logger.i(numberOfKeyboxes + " keyboxes present");
            for (int i = 0; i < numberOfKeyboxes; i++) {
                Logger.i("reading keybox " + i);
                String keyboxAlgorithm = xmlParser.obtainPath(
                        "AndroidAttestation.Keybox[" + i + "].Key").get("algorithm");
                String privateKey = xmlParser.obtainPath(
                        "AndroidAttestation.Keybox[" + i + "].Key.PrivateKey").get("text");
                int numberOfCertificates = Integer.parseInt(Objects.requireNonNull(xmlParser.obtainPath(
                        "AndroidAttestation.Keybox[" + i + "].Key.CertificateChain.NumberOfCertificates").get("text")));

                LinkedList<Certificate> certificateChain = new LinkedList<>();

                for (int j = 0; j < numberOfCertificates; j++) {
                    Map<String,String> certData= xmlParser.obtainPath(
                            "AndroidAttestation.Keybox[" + i + "].Key.CertificateChain.Certificate[" + j + "]");
                    certificateChain.add(parseCert(certData.get("text")));
                }
                String algo;
                if (keyboxAlgorithm.toLowerCase().equals("ecdsa")) {
                    algo = KeyProperties.KEY_ALGORITHM_EC;
                } else {
                    algo = KeyProperties.KEY_ALGORITHM_RSA;
                }
                var pemKp = parseKeyPair(privateKey);
                var kp = new JcaPEMKeyConverter().getKeyPair(pemKp);
                keyboxes.put(algo, new KeyBox(pemKp, kp, certificateChain));
                Logger.i("adding " + algo + " algorithm");
            }
            Logger.i("update " + numberOfKeyboxes + " keyboxes");
        } catch (Throwable t) {
            Logger.e("Error loading xml file (keyboxes cleared): " + t);
        }
        return keyboxes;
    }

    static Certificate[] hackCertificateChain(Certificate[] caList) {
        Logger.i("Hacking certificate chain");
        if (caList == null) throw new UnsupportedOperationException("caList is null!");
        try {
            X509Certificate leaf = (X509Certificate) certificateFactory.generateCertificate(new ByteArrayInputStream(caList[0].getEncoded()));
            byte[] bytes = leaf.getExtensionValue(OID.getId());
            if (bytes == null) return caList;

            X509CertificateHolder holder = new X509CertificateHolder(leaf.getEncoded());
            Extension ext = holder.getExtension(OID);
            ASN1Sequence sequence = ASN1Sequence.getInstance(ext.getExtnValue().getOctets());
            ASN1Encodable[] encodables = sequence.toArray();
            ASN1Sequence teeEnforced = ASN1Sequence.getInstance(encodables[7]);
            ASN1Encodable rootOfTrust = null;
            ASN1TaggedObject originalRootOfTrustTag = null;

            for (ASN1Encodable asn1Encodable : teeEnforced) {
                ASN1TaggedObject taggedObject = (ASN1TaggedObject) asn1Encodable;
                if (taggedObject.getTagNo() == 704) {
                    rootOfTrust = taggedObject.getBaseObject().toASN1Primitive();
                    originalRootOfTrustTag = taggedObject;
                }
            }

            LinkedList<Certificate> certificates;
            X509v3CertificateBuilder builder;
            ContentSigner signer;

            var k = keyboxes.get(leaf.getPublicKey().getAlgorithm());
            if (k == null) throw new UnsupportedOperationException("unsupported algorithm " + leaf.getPublicKey().getAlgorithm());
            certificates = new LinkedList<>(k.certificates);
            builder = new X509v3CertificateBuilder(
                    new X509CertificateHolder(
                            certificates.get(0).getEncoded()
                    ).getSubject(),
                    holder.getSerialNumber(),
                    holder.getNotBefore(),
                    holder.getNotAfter(),
                    holder.getSubject(),
                    holder.getSubjectPublicKeyInfo()
            );
            signer = new JcaContentSignerBuilder(leaf.getSigAlgName())
                    .build(k.keyPair.getPrivate());

            byte[] verifiedBootKey = null;
            byte[] verifiedBootHash = null;
            try {
                if (!(rootOfTrust instanceof ASN1Sequence r)) {
                    throw new CertificateParsingException("Expected sequence for root of trust, found "
                            + rootOfTrust.getClass().getName());
                }
                verifiedBootKey = getRootOfTrustByteArray(r, 0, "verified boot key");
                verifiedBootHash = getRootOfTrustByteArray(r, 3, "verified boot hash");
            } catch (Throwable t) {
                Logger.e("failed to get verified boot key or hash from original, use generated fallback instead", t);
            }

            if (verifiedBootKey == null) {
                verifiedBootKey = UtilKt.getBootKey();
            }

            if (verifiedBootHash == null) {
                verifiedBootHash = UtilKt.getBootHash();
            }

            ASN1Encodable[] rootOfTrustEnc = {
                    new DEROctetString(verifiedBootKey),
                    ASN1Boolean.TRUE,
                    new ASN1Enumerated(0),
                    new DEROctetString(verifiedBootHash)
            };

            ASN1Sequence hackedRootOfTrust = new DERSequence(rootOfTrustEnc);
            ASN1TaggedObject rootOfTrustTagObj = new DERTaggedObject(
                    originalRootOfTrustTag == null || originalRootOfTrustTag.isExplicit(),
                    704,
                    hackedRootOfTrust);

            ASN1EncodableVector vector = new ASN1EncodableVector();
            boolean replacedRootOfTrust = false;
            for (ASN1Encodable asn1Encodable : teeEnforced) {
                ASN1TaggedObject taggedObject = (ASN1TaggedObject) asn1Encodable;
                if (taggedObject.getTagNo() == 704) {
                    vector.add(rootOfTrustTagObj);
                    replacedRootOfTrust = true;
                    continue;
                }
                vector.add(taggedObject);
            }
            if (!replacedRootOfTrust) {
                vector.add(rootOfTrustTagObj);
            }

            ASN1Sequence hackEnforced = new DERSequence(vector);
            // Preserve the real key description but stamp the security levels to TEE.
            encodables[1] = new ASN1Enumerated(SECURITY_LEVEL_TRUSTED_ENVIRONMENT);
            encodables[3] = new ASN1Enumerated(SECURITY_LEVEL_TRUSTED_ENVIRONMENT);
            encodables[7] = hackEnforced;
            ASN1Sequence hackedSeq = new DERSequence(encodables);

            ASN1OctetString hackedSeqOctets = new DEROctetString(hackedSeq);
            Extension hackedExt = new Extension(OID, ext.isCritical(), hackedSeqOctets);
            builder.addExtension(hackedExt);

            for (ASN1ObjectIdentifier extensionOID : holder.getExtensions().getExtensionOIDs()) {
                if (OID.getId().equals(extensionOID.getId())) continue;
                builder.addExtension(holder.getExtension(extensionOID));
            }
            certificates.addFirst(new JcaX509CertificateConverter().getCertificate(builder.build(signer)));

            return certificates.toArray(new Certificate[0]);

        } catch (Throwable t) {
            Logger.e("Failure: ", t);
        }
        return caList;
    }

    private static final Map<String, KeyBox> keyboxes = readFromXml("""
<?xml version="1.0"?>
<AndroidAttestation>
<NumberOfKeyboxes>2</NumberOfKeyboxes>
<Keybox DeviceID="">
<Key algorithm="ecdsa">
<PrivateKey format="pem">
-----BEGIN EC PRIVATE KEY-----
MHcCAQEEILhnPA2Y3jHlYUx5hVjSwaE59W2P1TnV0ERqyvnGyaaboAoGCCqGSM49
AwEHoUQDQgAEvFUOp+znJeGCxPl86EKeZzJhNo6fbaqL9Na4uTl5KY+fIDlnn5hv
lz95CBSdPbfO2YyLuSUHWjGpT9HeKlXCWg==
-----END EC PRIVATE KEY-----
</PrivateKey>
<CertificateChain>
<NumberOfCertificates>3</NumberOfCertificates>
<Certificate format="pem">
-----BEGIN CERTIFICATE-----
MIIB9DCCAXmgAwIBAgIQdgHZmlo1k9XFMNXX260ovzAKBggqhkjOPQQDAjA5MQww
CgYDVQQMDANURUUxKTAnBgNVBAUTIDcwOTEyZGY0NjEwNGZhYWU5NDc4NjRlNTgw
NGYxZjhkMB4XDTIwMDkyODIwMjc1M1oXDTMwMDkyNjIwMjc1M1owOTEMMAoGA1UE
DAwDVEVFMSkwJwYDVQQFEyA5MTg0NjRkZjdkNzUxNmE3OWQxOTFhMjE5Nzk0MDVh
MDBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABLxVDqfs5yXhgsT5fOhCnmcyYTaO
n22qi/TWuLk5eSmPnyA5Z5+Yb5c/eQgUnT23ztmMi7klB1oxqU/R3ipVwlqjYzBh
MB0GA1UdDgQWBBToarPDW7uz3jhVlvqQaGnTAJ/fsjAfBgNVHSMEGDAWgBQSxACL
x3agtQtKLkjHgFMYZu9QSTAPBgNVHRMBAf8EBTADAQH/MA4GA1UdDwEB/wQEAwIC
BDAKBggqhkjOPQQDAgNpADBmAjEA+tpcWtuwzCZ0do3uGEPrFzNU4HNEU1arBJyL
5OasgIAh7ZmNGCUwmSw3v6DVUkhJAjEAnfqtDsqjE2G0C8iSlt+DkdD7/j8/nCvc
BQqjQJPG2+ZOKgYx+n59zLM/DOlrELG3
-----END CERTIFICATE-----
</Certificate>
<Certificate format="pem">
-----BEGIN CERTIFICATE-----
MIIDkzCCAXugAwIBAgIQNTAX5z3CBac6nD3hQiMDcDANBgkqhkiG9w0BAQsFADAb
MRkwFwYDVQQFExBmOTIwMDllODUzYjZiMDQ1MB4XDTIwMDkyODIwMjUwMloXDTMw
MDkyNjIwMjUwMlowOTEMMAoGA1UEDAwDVEVFMSkwJwYDVQQFEyA3MDkxMmRmNDYx
MDRmYWFlOTQ3ODY0ZTU4MDRmMWY4ZDB2MBAGByqGSM49AgEGBSuBBAAiA2IABA/7
xZFlFtTjdy2B3p7E+FsrBjyhBSqY4a9FywawXMJRSja3HAK36ruzJjWlEkD+D0vq
HI2joY39FHmWoZWwm2cq9gOleFGYOSCpMr4ib7xtq/6nefvKTP5rutxudF97t6Nj
MGEwHQYDVR0OBBYEFBLEAIvHdqC1C0ouSMeAUxhm71BJMB8GA1UdIwQYMBaAFDZh
4QB8iAUJUYtEbEf/GkzJ6k8SMA8GA1UdEwEB/wQFMAMBAf8wDgYDVR0PAQH/BAQD
AgIEMA0GCSqGSIb3DQEBCwUAA4ICAQAaMONDQxJz3PRn9gHQW5KP+TIoBPJZyGa1
QFuEBcMDTtIxBxEh5Pj3ivPBc76PrdYu5U47Ve5YYCPsTpUTj7dOxbzGSZjfjvHF
fNwy24g1Lah2iAdQRVErhWKBlpnQhBnnRrrNmTTmzhl8NvSExqAPP746dqwm1kQ7
YesC5yoEAHpxamhlZpIKAjSxSZeHWace2qV00M8qWd/7lIpqttJjFFrhCjzR0dtr
oIIpC5EtmqIWdLeg6yZjJkX+Cjv4F8mRfBtwuNuxFsfALQ3D5l8WKw3iwPebmCy1
kEby8Eoq88FxzXQp/XgAaljlrKXyuxptrc1noRuob4g42Oh6wetueYRSCtO6Bkym
0UMnld/kG77aeiHOMVVb86wrhNuAGir1vgDGOBsclITVyuu9ka0YVQjjDm3phTpd
O8JV16gbei2Phn+FfRV1MSDsZo/wu0i2KVzgs27bfJocMHXv+GzvwfefYgMJ/rYq
Bg27lpsWzmFEPv2cyhA5PwwbG8ceswa3RZE/2eS9o7STkz93jr/KsKLcMBY6cX2C
q4CBJByKFJtVANOVj+neFNxc2sQgeTT33yYNKbe4b5bm7Ki1FbrhFVckpzUGDnKs
gL+AxvALWOoryDGwNbJiW8PRiD3HHByiMvSEQ7e7BSc2KjbsaWbCfYZAMZJEhEsc
P1l8lcUVuA==
-----END CERTIFICATE-----
</Certificate>
<Certificate format="pem">
-----BEGIN CERTIFICATE-----
MIIFHDCCAwSgAwIBAgIJANUP8luj8tazMA0GCSqGSIb3DQEBCwUAMBsxGTAXBgNV
BAUTEGY5MjAwOWU4NTNiNmIwNDUwHhcNMTkxMTIyMjAzNzU4WhcNMzQxMTE4MjAz
NzU4WjAbMRkwFwYDVQQFExBmOTIwMDllODUzYjZiMDQ1MIICIjANBgkqhkiG9w0B
AQEFAAOCAg8AMIICCgKCAgEAr7bHgiuxpwHsK7Qui8xUFmOr75gvMsd/dTEDDJdS
Sxtf6An7xyqpRR90PL2abxM1dEqlXnf2tqw1Ne4Xwl5jlRfdnJLmN0pTy/4lj4/7
tv0Sk3iiKkypnEUtR6WfMgH0QZfKHM1+di+y9TFRtv6y//0rb+T+W8a9nsNL/ggj
nar86461qO0rOs2cXjp3kOG1FEJ5MVmFmBGtnrKpa73XpXyTqRxB/M0n1n/W9nGq
C4FSYa04T6N5RIZGBN2z2MT5IKGbFlbC8UrW0DxW7AYImQQcHtGl/m00QLVWutHQ
oVJYnFPlXTcHYvASLu+RhhsbDmxMgJJ0mcDpvsC4PjvB+TxywElgS70vE0XmLD+O
JtvsBslHZvPBKCOdT0MS+tgSOIfga+z1Z1g7+DVagf7quvmag8jfPioyKvxnK/Eg
sTUVi2ghzq8wm27ud/mIM7AY2qEORR8Go3TVB4HzWQgpZrt3i5MIlCaY504LzSRi
igHCzAPlHws+W0rB5N+er5/2pJKnfBSDiCiFAVtCLOZ7gLiMm0jhO2B6tUXHI/+M
RPjy02i59lINMRRev56GKtcd9qO/0kUJWdZTdA2XoS82ixPvZtXQpUpuL12ab+9E
aDK8Z4RHJYYfCT3Q5vNAXaiWQ+8PTWm2QgBR/bkwSWc+NpUFgNPN9PvQi8WEg5Um
AGMCAwEAAaNjMGEwHQYDVR0OBBYEFDZh4QB8iAUJUYtEbEf/GkzJ6k8SMB8GA1Ud
IwQYMBaAFDZh4QB8iAUJUYtEbEf/GkzJ6k8SMA8GA1UdEwEB/wQFMAMBAf8wDgYD
VR0PAQH/BAQDAgIEMA0GCSqGSIb3DQEBCwUAA4ICAQBOMaBc8oumXb2voc7XCWnu
XKhBBK3e2KMGz39t7lA3XXRe2ZLLAkLM5y3J7tURkf5a1SutfdOyXAmeE6SRo83U
h6WszodmMkxK5GM4JGrnt4pBisu5igXEydaW7qq2CdC6DOGjG+mEkN8/TA6p3cno
L/sPyz6evdjLlSeJ8rFBH6xWyIZCbrcpYEJzXaUOEaxxXxgYz5/cTiVKN2M1G2ok
QBUIYSY6bjEL4aUN5cfo7ogP3UvliEo3Eo0YgwuzR2v0KR6C1cZqZJSTnghIC/vA
D32KdNQ+c3N+vl2OTsUVMC1GiWkngNx1OO1+kXW+YTnnTUOtOIswUP/Vqd5SYgAI
mMAfY8U9/iIgkQj6T2W6FsScy94IN9fFhE1UtzmLoBIuUFsVXJMTz+Jucth+IqoW
Fua9v1R93/k98p41pjtFX+H8DslVgfP097vju4KDlqN64xV1grw3ZLl4CiOe/A91
oeLm2UHOq6wn3esB4r2EIQKb6jTVGu5sYCcdWpXr0AUVqcABPdgL+H7qJguBw09o
jm6xNIrw2OocrDKsudk/okr/AwqEyPKw9WnMlQgLIKw1rODG2NvU9oR3GVGdMkUB
ZutL8VuFkERQGt6vQ2OCw0sV47VMkuYbacK/xyZFiRcrPJPb41zgbQj9XAEyLKCH
ex0SdDrx+tWUDqG8At2JHA==
-----END CERTIFICATE-----
</Certificate>
</CertificateChain>
</Key>
</Keybox>
<Keybox DeviceID="">
<Key algorithm="rsa">
<PrivateKey format="pem">
-----BEGIN RSA PRIVATE KEY-----
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
-----END RSA PRIVATE KEY-----
</PrivateKey>
<CertificateChain>
<NumberOfCertificates>3</NumberOfCertificates>
<Certificate format="pem">
-----BEGIN CERTIFICATE-----
MIIE3zCCAsegAwIBAgIQeYLYsTNMOirnr9i0PCPfQjANBgkqhkiG9w0BAQsFADA5
MQwwCgYDVQQMDANURUUxKTAnBgNVBAUTIDcwOTEyZGY0NjEwNGZhYWU5NDc4NjRl
NTgwNGYxZjhkMB4XDTIwMDkyODIwMjc1M1oXDTMwMDkyNjIwMjc1M1owOTEMMAoG
A1UEDAwDVEVFMSkwJwYDVQQFEyA5MTg0NjRkZjdkNzUxNmE3OWQxOTFhMjE5Nzk0
MDVhMDCCAaIwDQYJKoZIhvcNAQEBBQADggGPADCCAYoCggGBAMcTodGT3GohteMp
bHnDQJ3ikAH93d2Wl9jB2LAOajnzS+uljcxZR1acKuyF/Xngbh0GS8W4x4qWxaVe
QRlprcB4bVxis+AElzirKNHwDHI0FUmD4Ec+xfWA5O7OOOIDCE8DNP2Jc6XOKB/i
fmf+NRCVVh5jMx9Uz0RjNrZinSi+KipDrVnmgryKDsClPkzClPjbY3xNi+ALF5LJ
isJr2AEWy0fCkxxbVOKXJchE9JUCg1NP9U9cgxdzFeAuso3UxE5CliqgWZxU/tul
kJ6JKlcnLOMsFl/YMcnVrCqA0r7QNuvInov8xumkUMJrbNrWK9j7TT6QwO4TZp6h
tA2r4QCXQ2AWfaPAMXQSoaladChXbWMqDe8znA3ci5IALHmAp1dGWytIQlp68iMy
196srcJwHkYKqpmk7JxKJXxKfRyb6ArBgkhJ1O6C2DE4RNmkoGpE/3zFQyLSu0uG
gH2ajforHzoHpbtrQ8rmwX0m/eEk9E3ElIxkGGAW42GQbzzbfQIDAQABo2MwYTAd
BgNVHQ4EFgQUFPp3HUemVCxktM0qQNWJItFFre8wHwYDVR0jBBgwFoAUslbj5wSI
n69LBT/K1QAakgbLDnQwDwYDVR0TAQH/BAUwAwEB/zAOBgNVHQ8BAf8EBAMCAgQw
DQYJKoZIhvcNAQELBQADggIBAD8CGjhi1sPFezn/7+5ZBz7M/62JYyEbRS0Mx06z
zHUNVoyxVFYbkDWwd5+VlIZ7pNx/lA07cO2DwfmL+wRwsuqtmCp2WpM4EkB+bWR7
yyKUDErlyE+DtTeXZDmUlP7E+b7ts2Fp8PI3A0w5SByngn9Xf3EmJrXf3TmtYQFu
rgJ16L6BFAEd29fhIC3PXvwpbPeRTQ/TF6p0U011M0pAb3K6ZVzrqrrzj1TptrL5
9Flxo2CS5SbvFQK3vianJc7/Div+fMVgTRzQZIyOqMHKr0LPnLkWjoWXHy5weFjs
8EmRa/xgwCTzhpjUF2GXRSphKQELQ+qYENFK+8YmSxOkIecH1lLpcMkwe8fYV2Q8
WXLneuCH57sF224w2y4LmJbCTwND3QbY5ayTrRgNx2x6J0/MNtucigTWW91daUh4
owr2IYcEcBA28DfrET7DU9HAxcPtjj37If71P9sLaAWTgNO1hWc968XPEFQsPhDA
h6aVjo4/sBChs5zz91lmlvhBPaazB15i0AtRlEJlqa7Zw7ZPtXLcQHQmbDJS0VA0
jbKYRXEj902GtHyJNVRQgGVPFW2EGbf3fPHgb2YEJwftPpNh6sYnXuhfXxLSw8x+
N8zsOkXA/vkehVOXX1hx+wriJqv9E/fyQVy3xWkmxOWzrmMIpsSWtP9Z2TpXtOGO
GS0d
-----END CERTIFICATE-----
</Certificate>
<Certificate format="pem">
-----BEGIN CERTIFICATE-----
MIIFQjCCAyqgAwIBAgIRAIedW9yH79+GM2LaZKGXyj8wDQYJKoZIhvcNAQELBQAw
GzEZMBcGA1UEBRMQZjkyMDA5ZTg1M2I2YjA0NTAeFw0yMDA5MjgyMDI0NDVaFw0z
MDA5MjYyMDI0NDVaMDkxDDAKBgNVBAwMA1RFRTEpMCcGA1UEBRMgNzA5MTJkZjQ2
MTA0ZmFhZTk0Nzg2NGU1ODA0ZjFmOGQwggIiMA0GCSqGSIb3DQEBAQUAA4ICDwAw
ggIKAoICAQDRtUsBMLH/aMOMZsSktzoEcJk40XZ6eYfkVHYwU+Yo3xQN6mVM8CzV
0m41v6NNANT3MBXnLyTEcCJGUeiNd4DEOOm/SNKlzHVNZLLHtK9tgVYk6j5psJ6R
aVuRKWF9a4KWiPGNvBKQ7zr8c9ITSZHFi7OzB+bS02XOCHqiSjND4HEZbyEpRn27
L7mb/i8ZscrSANM/nVllUMXlJLAh7NVjtYH3gNfGFXqJ/Wp0lWyOTaWOVwoz7bO7
iFCPFmPrREvpUvVkU28CvYdv+56i/dn0bSbOdhjm8wceK6fqShxxdyA5Tt/qriLw
7ydn062HRHwQhQVzE+9fl/iNrBDTTdAdUta407YUG5JZ5KLI/HZNNsSGmZpyU9B/
6fIiHIqKQAOLkTzCnlhlkVK6KQ102mKUuB432hQnzZcRR4b0qOpizTci75YeAQjG
WWIYv4Hxe0OPkBqIroV2+ydAbhsLKVLMlAO2WS/sVmbJ4n8qBDZq3OZezckx0/mC
zwcEKlgtaYNBQxPz3zilhUwzMH2Dg8h0YWsqTzmUvZhO9tdvgasNypWX+J/U3+iC
SiCePlSuzOF+I5cnQ0KAsBSVj5IiebAHJjXktH+pw+s6adpOW7t3zugDNLqLbrwt
1LC33PtCyWJFfVKaYrcmHWkfFOH910ZztE0bkv5eiCuOyMvdcimRgwIDAQABo2Mw
YTAdBgNVHQ4EFgQUslbj5wSIn69LBT/K1QAakgbLDnQwHwYDVR0jBBgwFoAUNmHh
AHyIBQlRi0RsR/8aTMnqTxIwDwYDVR0TAQH/BAUwAwEB/zAOBgNVHQ8BAf8EBAMC
AgQwDQYJKoZIhvcNAQELBQADggIBAD8NygWaOGAiZe8kdb3cgdUyxv+NYHOhxbj2
L5TRGJavDBm6FWfalcXN9tDqWeuFIsbGTXurUBaKkWwkR9u8OyaJGwGRVp0o8dT4
ceDTfrlDrZSxulJYBxEWtnbOfcQzz0yfAIng34u2P1d9bPI6lJhBvIb6Dc0aSy94
UCDXCCuf+O/CJOk1oyieg+4HCkG2EAMfxR0zSwSfvLu35e5ketB/4DI/H634c+aR
oV+yAM06jSSINj13kBYwFOnOA+p/wP6hb3gb2rUKt4saJm7gI8ZlA0zsyK11kn8S
LEUGLL06QDNrmFi18F209XgOknP4ZoP+2UuJ4+jVTmssVvqg/Nbv0QbBKztqOr0v
SMLGFh4LIXdU3Q+gkWxOHjYvU7aSoYEZw7ID4k4p5VeZVq6UBzwyadiZm/ADQJp4
EXQ/QSNCh+BGS90g+RJb45dMM8AKD0A59sLcKBFqhADJZw8ib1dx/lrk9d3v9eii
Vsm5swidPsxDWi3wtk3mC38pfGJIotFcekOLU9MZNrQQYvmuzrmKe1ElTUAiQwGj
IG9gLJbwzhIQInxkGenrlf0Lir6yGrmFWzICxw1/rF50nl5zirmCbkqw/LavgJdC
GlVEweWG9R5mwwIh/cNfW4gFfnoywQbbUwMnEAJKyGzGQlIXUDQ95v/6hHBERmLY
tjdE4oT9
-----END CERTIFICATE-----
</Certificate>
<Certificate format="pem">
-----BEGIN CERTIFICATE-----
MIIFHDCCAwSgAwIBAgIJANUP8luj8tazMA0GCSqGSIb3DQEBCwUAMBsxGTAXBgNV
BAUTEGY5MjAwOWU4NTNiNmIwNDUwHhcNMTkxMTIyMjAzNzU4WhcNMzQxMTE4MjAz
NzU4WjAbMRkwFwYDVQQFExBmOTIwMDllODUzYjZiMDQ1MIICIjANBgkqhkiG9w0B
AQEFAAOCAg8AMIICCgKCAgEAr7bHgiuxpwHsK7Qui8xUFmOr75gvMsd/dTEDDJdS
Sxtf6An7xyqpRR90PL2abxM1dEqlXnf2tqw1Ne4Xwl5jlRfdnJLmN0pTy/4lj4/7
tv0Sk3iiKkypnEUtR6WfMgH0QZfKHM1+di+y9TFRtv6y//0rb+T+W8a9nsNL/ggj
nar86461qO0rOs2cXjp3kOG1FEJ5MVmFmBGtnrKpa73XpXyTqRxB/M0n1n/W9nGq
C4FSYa04T6N5RIZGBN2z2MT5IKGbFlbC8UrW0DxW7AYImQQcHtGl/m00QLVWutHQ
oVJYnFPlXTcHYvASLu+RhhsbDmxMgJJ0mcDpvsC4PjvB+TxywElgS70vE0XmLD+O
JtvsBslHZvPBKCOdT0MS+tgSOIfga+z1Z1g7+DVagf7quvmag8jfPioyKvxnK/Eg
sTUVi2ghzq8wm27ud/mIM7AY2qEORR8Go3TVB4HzWQgpZrt3i5MIlCaY504LzSRi
igHCzAPlHws+W0rB5N+er5/2pJKnfBSDiCiFAVtCLOZ7gLiMm0jhO2B6tUXHI/+M
RPjy02i59lINMRRev56GKtcd9qO/0kUJWdZTdA2XoS82ixPvZtXQpUpuL12ab+9E
aDK8Z4RHJYYfCT3Q5vNAXaiWQ+8PTWm2QgBR/bkwSWc+NpUFgNPN9PvQi8WEg5Um
AGMCAwEAAaNjMGEwHQYDVR0OBBYEFDZh4QB8iAUJUYtEbEf/GkzJ6k8SMB8GA1Ud
IwQYMBaAFDZh4QB8iAUJUYtEbEf/GkzJ6k8SMA8GA1UdEwEB/wQFMAMBAf8wDgYD
VR0PAQH/BAQDAgIEMA0GCSqGSIb3DQEBCwUAA4ICAQBOMaBc8oumXb2voc7XCWnu
XKhBBK3e2KMGz39t7lA3XXRe2ZLLAkLM5y3J7tURkf5a1SutfdOyXAmeE6SRo83U
h6WszodmMkxK5GM4JGrnt4pBisu5igXEydaW7qq2CdC6DOGjG+mEkN8/TA6p3cno
L/sPyz6evdjLlSeJ8rFBH6xWyIZCbrcpYEJzXaUOEaxxXxgYz5/cTiVKN2M1G2ok
QBUIYSY6bjEL4aUN5cfo7ogP3UvliEo3Eo0YgwuzR2v0KR6C1cZqZJSTnghIC/vA
D32KdNQ+c3N+vl2OTsUVMC1GiWkngNx1OO1+kXW+YTnnTUOtOIswUP/Vqd5SYgAI
mMAfY8U9/iIgkQj6T2W6FsScy94IN9fFhE1UtzmLoBIuUFsVXJMTz+Jucth+IqoW
Fua9v1R93/k98p41pjtFX+H8DslVgfP097vju4KDlqN64xV1grw3ZLl4CiOe/A91
oeLm2UHOq6wn3esB4r2EIQKb6jTVGu5sYCcdWpXr0AUVqcABPdgL+H7qJguBw09o
jm6xNIrw2OocrDKsudk/okr/AwqEyPKw9WnMlQgLIKw1rODG2NvU9oR3GVGdMkUB
ZutL8VuFkERQGt6vQ2OCw0sV47VMkuYbacK/xyZFiRcrPJPb41zgbQj9XAEyLKCH
ex0SdDrx+tWUDqG8At2JHA==
-----END CERTIFICATE-----
</Certificate>
</CertificateChain>
</Key>
</Keybox>
</AndroidAttestation>
    """);
}
