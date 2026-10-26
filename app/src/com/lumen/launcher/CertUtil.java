package com.lumen.launcher;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * Makes a self-signed TLS certificate for the phone remote, once per projector, so the phone
 * browser treats the remote page as a secure origin and allows the microphone.
 * Pure Java (hand-built X.509 DER, no BouncyCastle) — runs on Android and on a desktop JVM.
 */
final class CertUtil {

    private static final String ALIAS = "lumen";

    private CertUtil() {
    }

    /** Loads the stored key + certificate, or creates and stores new ones. */
    static SSLContext serverContext(File store, char[] pass) throws Exception {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        boolean loaded = false;
        if (store.exists()) {
            FileInputStream in = new FileInputStream(store);
            try {
                ks.load(in, pass);
                loaded = ks.containsAlias(ALIAS);
            } catch (Exception e) {
                loaded = false;
            } finally {
                in.close();
            }
        }
        if (!loaded) {
            ks.load(null, pass);
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048, new SecureRandom());
            KeyPair kp = g.generateKeyPair();
            X509Certificate cert = selfSign(kp, "Lumen Projector");
            ks.setKeyEntry(ALIAS, kp.getPrivate(), pass, new Certificate[]{cert});
            FileOutputStream out = new FileOutputStream(store);
            try {
                ks.store(out, pass);
            } finally {
                out.close();
            }
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pass);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, new SecureRandom());
        return ctx;
    }

    // ------------------------------------------------------------ X.509 DER

    static X509Certificate selfSign(KeyPair kp, String cn) throws Exception {
        byte[] sigAlg = seq(oid(new int[]{1, 2, 840, 113549, 1, 1, 11}), new byte[]{0x05, 0x00}); // sha256WithRSA
        byte[] name = seq(set(seq(oid(new int[]{2, 5, 4, 3}), tlv(0x0C, cn.getBytes("UTF-8")))));
        long now = System.currentTimeMillis();
        byte[] validity = seq(utcTime(new Date(now - 86400000L)), utcTime(new Date(now + 3650L * 86400000L)));
        byte[] serial = new BigInteger(64, new SecureRandom()).abs().add(BigInteger.ONE).toByteArray();

        // extensions: basicConstraints CA:false, subjectAltName DNS:lumen.local
        byte[] basic = seq(oid(new int[]{2, 5, 29, 19}), tlv(0x04, seq()));
        byte[] san = seq(oid(new int[]{2, 5, 29, 17}),
                tlv(0x04, seq(tlv(0x82, "lumen.local".getBytes("UTF-8")))));
        byte[] exts = tlv(0xA3, seq(basic, san));

        byte[] tbs = seq(
                tlv(0xA0, tlv(0x02, new byte[]{0x02})),   // version v3
                tlv(0x02, serial),
                sigAlg,
                name,
                validity,
                name,
                kp.getPublic().getEncoded(),              // SubjectPublicKeyInfo
                exts);

        PrivateKey key = kp.getPrivate();
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(key);
        s.update(tbs);
        byte[] sig = s.sign();
        byte[] bitString = new byte[sig.length + 1];
        System.arraycopy(sig, 0, bitString, 1, sig.length); // leading 0 = no unused bits
        byte[] der = seq(tbs, sigAlg, tlv(0x03, bitString));

        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der));
    }

    private static byte[] utcTime(Date d) throws IOException {
        SimpleDateFormat f = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(0x17, f.format(d).getBytes("US-ASCII"));
    }

    private static byte[] oid(int[] parts) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(parts[0] * 40 + parts[1]);
        for (int i = 2; i < parts.length; i++) {
            int v = parts[i];
            byte[] tmp = new byte[5];
            int n = 0;
            do {
                tmp[n++] = (byte) (v & 0x7F);
                v >>>= 7;
            } while (v != 0);
            for (int j = n - 1; j >= 0; j--) b.write(tmp[j] | (j > 0 ? 0x80 : 0));
        }
        return tlv(0x06, b.toByteArray());
    }

    private static byte[] seq(byte[]... items) throws IOException {
        return tlv(0x30, concat(items));
    }

    private static byte[] set(byte[]... items) throws IOException {
        return tlv(0x31, concat(items));
    }

    private static byte[] concat(byte[]... items) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (byte[] i : items) b.write(i);
        return b.toByteArray();
    }

    private static byte[] tlv(int tag, byte[] value) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(tag);
        int len = value.length;
        if (len < 0x80) {
            b.write(len);
        } else if (len < 0x100) {
            b.write(0x81);
            b.write(len);
        } else if (len < 0x10000) {
            b.write(0x82);
            b.write(len >> 8);
            b.write(len & 0xFF);
        } else {
            b.write(0x83);
            b.write(len >> 16);
            b.write((len >> 8) & 0xFF);
            b.write(len & 0xFF);
        }
        b.write(value);
        return b.toByteArray();
    }
}
