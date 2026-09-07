package com.fundamentalos.forge;

import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.cert.Certificate;

/** Orchestrates one keybox fetch+rotate. Returns a short status string. */
final class ForgeClient {
    static final String TAG = "FundamentalForge";
    private static final byte[] BAR = "|".getBytes();

    static String fetchAndRotate() {
        try {
            // 1) challenge
            JSONObject ch = new JSONObject(httpGet(ForgeConstants.API_BASE + "/v1/challenge"));
            byte[] nonce = Base64.decode(ch.getString("nonce"), Base64.DEFAULT);

            // 2) fresh TEE-attested key bound to the nonce
            Certificate[] chain = Crypto.genAttestedKey(ForgeConstants.KEY_ALIAS, nonce);
            byte[] epkD = Crypto.pubPoint(chain[0]);

            // 3) attested fetch
            JSONArray arr = new JSONArray();
            for (Certificate c : chain) {
                arr.put(Base64.encodeToString(c.getEncoded(), Base64.NO_WRAP));
            }
            JSONObject reqBody = new JSONObject().put("chain", arr);
            JSONObject resp = new JSONObject(
                    httpPost(ForgeConstants.API_BASE + "/v1/keybox", reqBody.toString()));

            String kid = resp.getString("kid");
            byte[] epkS = Base64.decode(resp.getString("epk_s"), Base64.DEFAULT);
            byte[] iv = Base64.decode(resp.getString("iv"), Base64.DEFAULT);
            byte[] ct = Base64.decode(resp.getString("ct"), Base64.DEFAULT);
            byte[] sig = Base64.decode(resp.getString("sig"), Base64.DEFAULT);
            byte[] date = resp.getString("date").getBytes();
            String tier = resp.optString("tier", "?");

            // 4) verify server signature against the ROM-pinned key
            byte[] serverKey = ForgeConstants.SERVER_KEYS.get(kid);
            if (serverKey == null) return "FAIL: unknown server kid " + kid;
            byte[] sigMsg = Crypto.cat(Crypto.LABEL, "-resp".getBytes(), BAR, nonce, BAR,
                    epkS, BAR, iv, BAR, Crypto.sha256(ct), BAR, date);
            if (!Crypto.verifyServerSig(serverKey, sigMsg, sig)) {
                return "FAIL: bad server signature (MITM?)";
            }

            // 5) ECDH in TEE -> HKDF -> AES-GCM open
            byte[] z = Crypto.ecdh(ForgeConstants.KEY_ALIAS, epkS);
            byte[] info = Crypto.cat(Crypto.LABEL, BAR, date, BAR,
                    Crypto.sha256(epkD), BAR, Crypto.sha256(epkS));
            byte[] key = Crypto.hkdf(z, nonce, info, 32);
            byte[] aad = Crypto.cat(Crypto.LABEL, BAR, date, BAR, Crypto.sha256(epkD));
            byte[] keybox = Crypto.aesGcmOpen(key, iv, ct, aad);

            // 6) rotate: write + bump mtime so keystore2 Forge reloads
            boolean written = writeKeybox(keybox);
            String r = "OK tier=" + tier + " keybox=" + keybox.length + "B written=" + written;
            Log.i(TAG, r);
            return r;
        } catch (Exception e) {
            Log.e(TAG, "fetchAndRotate failed", e);
            return "FAIL: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private static boolean writeKeybox(byte[] keybox) {
        try {
            File f = new File(ForgeConstants.KEYBOX_PATH);
            File dir = f.getParentFile();
            if (dir != null) dir.mkdirs();
            try (FileOutputStream o = new FileOutputStream(f)) {
                o.write(keybox);
            }
            f.setReadable(true, false); // keystore uid != system uid
            f.setLastModified(System.currentTimeMillis());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "write keybox failed (sepolicy?)", e);
            return false;
        }
    }

    // -------- tiny HTTP --------
    private static String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(30000);
        return readBody(c);
    }

    private static String httpPost(String url, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(60000);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream o = c.getOutputStream()) { o.write(json.getBytes()); }
        return readBody(c);
    }

    private static String readBody(HttpURLConnection c) throws Exception {
        int code = c.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[4096]; int n;
        while (is != null && (n = is.read(buf)) > 0) b.write(buf, 0, n);
        String body = b.toString();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code + ": " + body);
        return body;
    }

    private ForgeClient() {}
}
