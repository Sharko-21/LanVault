import com.android.apksig.ApkVerifier;
import com.android.apksig.SigningCertificateLineage;
import java.io.File;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Uses the Android build tools verifier; prints public certificate information only. */
class CheckApk {
    static String digest(java.security.cert.X509Certificate certificate) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
    }
    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("usage: CheckApk after.apk expected-signer-sha256 [before.apk]");
        File after = new File(args[0]);
        for (int sdk : new int[]{29, 32, 33, 37}) {
            var result = new ApkVerifier.Builder(after).setMinCheckedPlatformVersion(sdk).setMaxCheckedPlatformVersion(sdk).build().verify();
            if (!result.isVerified()) throw new SecurityException("APK verification failed for SDK " + sdk + ": " + result.getErrors());
            if (result.getSignerCertificates().size() != 1 || !digest(result.getSignerCertificates().get(0)).equalsIgnoreCase(args[1]))
                throw new SecurityException("Unexpected release signer for SDK " + sdk);
        }
        if (args.length > 2) {
            java.security.cert.X509Certificate previous;
            if (args[2].endsWith(".der")) {
                try (var input = new java.io.FileInputStream(args[2])) {
                    previous = (java.security.cert.X509Certificate) java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(input);
                }
            } else {
                var before = new ApkVerifier.Builder(new File(args[2])).build().verify();
                if (!before.isVerified()) throw new SecurityException("Previous APK is not valid");
                previous = before.getSignerCertificates().get(0);
            }
            var lineage = SigningCertificateLineage.readFromApkFile(after);
            if (lineage == null) throw new SecurityException("Missing proof of rotation");
            if (lineage.getCertificatesInLineage().stream().noneMatch(previous::equals))
                throw new SecurityException("Previous signing certificate missing from rotation history");
            if (!lineage.getSignerCapabilities(previous).hasInstalledData())
                throw new SecurityException("Rotation does not preserve installed app data");
            System.out.println("previousSignerSha256=" + digest(previous));
            System.out.println("installedDataCapability=true");
        }
        System.out.println("verifiedSdks=29,32,33,37");
        System.out.println("releaseSignerSha256=" + args[1].toLowerCase());
    }
}
