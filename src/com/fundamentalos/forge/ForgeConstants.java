package com.fundamentalos.forge;

import android.util.Base64;

final class ForgeConstants {
    static final String API_BASE = "https://api.fundamentalos.org";
    static final String KEY_ALIAS = "fundamental_forge_attest";
    static final String KEYBOX_PATH = "/data/misc/fundamental/keybox.xml";
    // Settings.Secure contract (byte-for-byte identical to Settings IntegritySpoofKeys).
    static final String SECURE_ENABLED = "fundamental_integrity_enabled";
    static final String SECURE_OVERRIDE_FETCHING = "fundamental_integrity_override_fetching";

    /** Forge is enabled out of the box; the master switch in Settings mirrors this default. */
    static final boolean DEFAULT_ENABLED = true;

    /** ROM-pinned server identity public keys (65-byte P-256 uncompressed points). */
    static final java.util.Map<String, byte[]> SERVER_KEYS = new java.util.HashMap<>();
    static {
        SERVER_KEYS.put("k1", Base64.decode(
            "BA98WQMwsZL10dvUcH+xz0bBdZ1gzq5hwSNIkmPazBjCwlYMgzRKAnIZDq8nGRyuU0A1pCLUBL79iKNJHmdM/sw=",
            Base64.DEFAULT));
        SERVER_KEYS.put("k2", Base64.decode(
            "BFavdQB/P8nVH19JO4lqRoKH2y4nfp57Khz2rY00v9WoFuEKuIwJIFB1sZhXlXdiLLvW7eL5gFnEhMUm9PIWQtc=",
            Base64.DEFAULT));
    }

    private ForgeConstants() {}
}
