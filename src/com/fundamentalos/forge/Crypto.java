package com.fundamentalos.forge;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.security.keystore.StrongBoxUnavailableException;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import java.security.interfaces.ECPublicKey;
import java.security.spec.ECParameterSpec;

/** FOS-KBX-v1 client crypto. Mirrors the server (foskbx.py) byte-for-byte. */
final class Crypto {
    private static final String KS = "AndroidKeyStore";
    static final byte[] LABEL = "FOS-KBX-v1".getBytes();

    /** Generate a fresh TEE-attested P-256 AGREE_KEY; return the attestation chain. */
    static Certificate[] genAttestedKey(String alias, byte[] challenge) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC, KS);
        KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(
                alias, KeyProperties.PURPOSE_AGREE_KEY)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAttestationChallenge(challenge);
        try {
            kpg.initialize(b.setIsStrongBoxBacked(true).build());
            kpg.generateKeyPair();
        } catch (Exception e) {
            kpg.initialize(b.setIsStrongBoxBacked(false).build());
            kpg.generateKeyPair();
        }
        KeyStore ks = KeyStore.getInstance(KS);
        ks.load(null);
        return ks.getCertificateChain(alias);
    }

    /** 65-byte uncompressed point (0x04|X|Y) of the AndroidKeyStore key's public half. */
    static byte[] pubPoint(Certificate leaf) {
        ECPublicKey pk = (ECPublicKey) leaf.getPublicKey();
        return encodePoint(pk.getW());
    }

    static byte[] encodePoint(ECPoint w) {
        byte[] x = fixed32(w.getAffineX());
        byte[] y = fixed32(w.getAffineY());
        byte[] out = new byte[65];
        out[0] = 0x04;
        System.arraycopy(x, 0, out, 1, 32);
        System.arraycopy(y, 0, out, 33, 32);
        return out;
    }

    private static byte[] fixed32(BigInteger v) {
        byte[] b = v.toByteArray();
        byte[] out = new byte[32];
        if (b.length == 33 && b[0] == 0) b = Arrays.copyOfRange(b, 1, 33);
        System.arraycopy(b, 0, out, 32 - b.length, b.length);
        return out;
    }

    /** Rebuild a P-256 public key from a 65-byte uncompressed point. */
    static ECPublicKey pointToKey(byte[] p) throws Exception {
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(p, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(p, 33, 65));
        // borrow curve params from a throwaway generated P-256 key
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec params = ((ECPublicKey) g.generateKeyPair().getPublic()).getParams();
        KeyFactory kf = KeyFactory.getInstance("EC");
        return (ECPublicKey) kf.generatePublic(new ECPublicKeySpec(new ECPoint(x, y), params));
    }

    static boolean verifyServerSig(byte[] serverPubPoint, byte[] msg, byte[] sig) throws Exception {
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initVerify(pointToKey(serverPubPoint));
        s.update(msg);
        return s.verify(sig);
    }

    /** ECDH in the TEE (private key never leaves hardware) -> raw shared secret (x-coord, 32B). */
    static byte[] ecdh(String alias, byte[] serverEpkPoint) throws Exception {
        KeyStore ks = KeyStore.getInstance(KS);
        ks.load(null);
        PrivateKey priv = (PrivateKey) ks.getKey(alias, null);
        KeyAgreement ka = KeyAgreement.getInstance("ECDH", KS);
        ka.init(priv);
        ka.doPhase(pointToKey(serverEpkPoint), true);
        return ka.generateSecret();
    }

    static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info, int len) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);                 // extract
        mac.init(new SecretKeySpec(prk, "HmacSHA256")); // expand
        byte[] t = new byte[0];
        byte[] out = new byte[len];
        int pos = 0, ctr = 1;
        while (pos < len) {
            mac.reset();
            mac.update(t);
            mac.update(info);
            mac.update((byte) ctr++);
            t = mac.doFinal();
            int n = Math.min(t.length, len - pos);
            System.arraycopy(t, 0, out, pos, n);
            pos += n;
        }
        return out;
    }

    static byte[] sha256(byte[] b) throws Exception {
        return java.security.MessageDigest.getInstance("SHA-256").digest(b);
    }

    static byte[] cat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, out, o, p.length); o += p.length; }
        return out;
    }

    static byte[] aesGcmOpen(byte[] key, byte[] iv, byte[] ctAndTag, byte[] aad) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        c.updateAAD(aad);
        return c.doFinal(ctAndTag);
    }

    private Crypto() {}
}
