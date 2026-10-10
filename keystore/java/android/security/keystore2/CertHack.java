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

/**
 * @hide
 */
@SuppressWarnings("MissingNullability")
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
            // Remove the original software RootOfTrust when supplying the rewritten hardware one.
            ASN1Sequence softwareEnforced = ASN1Sequence.getInstance(encodables[6]);
            ASN1EncodableVector softwareAuthorizations = new ASN1EncodableVector();
            for (ASN1Encodable authorization : softwareEnforced) {
                ASN1TaggedObject taggedObject = (ASN1TaggedObject) authorization;
                if (taggedObject.getTagNo() != 704) {
                    softwareAuthorizations.add(taggedObject);
                }
            }

            // Preserve the real key description but stamp the security levels to TEE.
            encodables[1] = new ASN1Enumerated(SECURITY_LEVEL_TRUSTED_ENVIRONMENT);
            encodables[3] = new ASN1Enumerated(SECURITY_LEVEL_TRUSTED_ENVIRONMENT);
            encodables[6] = new DERSequence(softwareAuthorizations);
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

    // Local software attestation identity; embedded keys provide no hardware security.
    private static final Map<String, KeyBox> keyboxes = readFromXml("""
<?xml version="1.0"?>
<AndroidAttestation>
<NumberOfKeyboxes>2</NumberOfKeyboxes>
<Keybox DeviceID="">
<Key algorithm="ecdsa">
<PrivateKey format="pem">
-----BEGIN EC PRIVATE KEY-----
MHcCAQEEIECeq3az/+4OUKQ3OkVbJ8OnnGSh81PJnMhqOX+GpfvCoAoGCCqGSM49
AwEHoUQDQgAEBvmeSrIIRgk4W+xUYxp6VHgaZwZKDBwv3uRl7NXZYltWMVV/HH2l
Sk1CDF33YONSdQvopYqFGdtSySpl1Lp4Xw==
-----END EC PRIVATE KEY-----
</PrivateKey>
<CertificateChain>
<NumberOfCertificates>1</NumberOfCertificates>
<Certificate format="pem">
-----BEGIN CERTIFICATE-----
MIIB7TCCAZSgAwIBAgIUbZvHp4n0pHnX5MLXW+E/nTS/kw8wCgYIKoZIzj0EAwIw
QzERMA8GA1UECgwIRHVtYmVyT1MxLjAsBgNVBAMMJUR1bWJlck9TIFNvZnR3YXJl
IEF0dGVzdGF0aW9uIEVDIFJvb3QwHhcNMjYwOTMwMTA0MDUxWhcNMzYwOTI3MTA0
MDUxWjBDMREwDwYDVQQKDAhEdW1iZXJPUzEuMCwGA1UEAwwlRHVtYmVyT1MgU29m
dHdhcmUgQXR0ZXN0YXRpb24gRUMgUm9vdDBZMBMGByqGSM49AgEGCCqGSM49AwEH
A0IABAb5nkqyCEYJOFvsVGMaelR4GmcGSgwcL97kZezV2WJbVjFVfxx9pUpNQgxd
92DjUnUL6KWKhRnbUskqZdS6eF+jZjBkMB8GA1UdIwQYMBaAFIdekeWrsKEsrXMC
TBVvcQn/djyfMBIGA1UdEwEB/wQIMAYBAf8CAQAwDgYDVR0PAQH/BAQDAgEGMB0G
A1UdDgQWBBSHXpHlq7ChLK1zAkwVb3EJ/3Y8nzAKBggqhkjOPQQDAgNHADBEAiBK
Ug0lnfw0CAEz6+GSRaWXqRzC3T23tlvLMeRtHM1HWAIgLm+BWMcG5rEAjVK8QQ61
7PYGApRNFkjNThNBJ2r49A8=
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
<NumberOfCertificates>1</NumberOfCertificates>
<Certificate format="pem">
-----BEGIN CERTIFICATE-----
MIIEfDCCAuSgAwIBAgIUZG9/nuPs6xvD9FsiXOQtvK+sIrAwDQYJKoZIhvcNAQEL
BQAwRDERMA8GA1UECgwIRHVtYmVyT1MxLzAtBgNVBAMMJkR1bWJlck9TIFNvZnR3
YXJlIEF0dGVzdGF0aW9uIFJTQSBSb290MB4XDTI2MDkzMDEwNDA1MVoXDTM2MDky
NzEwNDA1MVowRDERMA8GA1UECgwIRHVtYmVyT1MxLzAtBgNVBAMMJkR1bWJlck9T
IFNvZnR3YXJlIEF0dGVzdGF0aW9uIFJTQSBSb290MIIBojANBgkqhkiG9w0BAQEF
AAOCAY8AMIIBigKCAYEApBFbYEGvg4LccVlCiDQPart9BkTWGbtX7wZd20fPCjxS
ryMRgijlJ6mwT1NaSRE+HNDw3zhuICzE5OXFnYa8601eqaMBIqObgq+PEVW+6Sjz
pdzdfycaMiBFoW5aDlbA40QWFScTZPb5cS63bRwwQQWgNko1z89Pz/2oVL7qaGnz
JkgW7qZTMtwUEqUqVkGpxfteJvhUWU8PGy1FnPU4a8WiZOftY1SHwLUvtVou02fF
8IYfkfQSTpe3UcO+SLqRzQDmDZTVc8S5CgTjmGeBZaXxIhtoGVaAcF6M4UkVQcC/
p35D//Yf2lyBdG0NQXENgzhKscUzZzVcvZHc9pRLg4fo+4kYLcSkTJuqbhwmvJtU
HpIw2iHqrxHYlLo1XtGBvcQkoiXMn17aG/x0zzIx4LzYQJr+AxcogSyLQTZLmp5a
KhOyVen9b6CieHdLgpeKAyLcUu0lxwLWjcMAXPGRJzPkoJnYlJAAgiD49SUNrK9f
KI+mKdTED6urQu/9XHkjAgMBAAGjZjBkMB8GA1UdIwQYMBaAFEEOGHj5Liy34LOB
3zqMeHD8itxWMBIGA1UdEwEB/wQIMAYBAf8CAQAwDgYDVR0PAQH/BAQDAgEGMB0G
A1UdDgQWBBRBDhh4+S4st+Czgd86jHhw/IrcVjANBgkqhkiG9w0BAQsFAAOCAYEA
cDOlbHXemVIn2KMGPqoBKL6VkVpbTuHDMKTp441N3HR5C11YH5u+5q6STifsOwoJ
L2rhLpkZrWFwjc/qpLG8rMHeD7n9Wrmpstmzo164U6qQu1IOHnQ/RATHhUWhlCzk
SIkpKdSXQp9kZUtwdQo2D0CVM2g9AZbjwtTVwktOKI1nBBcYVpLNHEunI5QHho+E
NK+gRbOlxrFNHMSjw9roWdJjhjNQcD04C4AAOUSTqTjCl/O4Eam9blsP3mw26EZQ
N9fB2q2hRwJB7BnFkJl3Y9sxFeHekJqp+VeUB+/x1wJ58oJ4t3E7rdH4jOYPO8XZ
OQeF+tP2Ux++XA/Zi1W1I8v5BtLiMYiOKB1NgQSCkPUAJwx3udevm7dcDXHwmXrx
SnoNwuISXRJOZguKBWFqWElPnKPZxVusHKzyZng7ZSGbS+GfMZmAnQoxMF4YBW/T
PFVYcgsLheLNhd5LIHq/XlbFuLO6t253BNTmDVkvG3pksYBidFsl6JbwmJ02D9lZ
-----END CERTIFICATE-----
</Certificate>
</CertificateChain>
</Key>
</Keybox>
</AndroidAttestation>
    """);
}
