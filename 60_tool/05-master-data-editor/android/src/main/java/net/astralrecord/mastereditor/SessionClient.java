package net.astralrecord.mastereditor;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;

final class SessionClient {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "astralrecord-master-editor-device-v1";
    private static final int MAX_RESPONSE_BYTES = 16 * 1024;
    private static final Pattern DEVICE_ID = Pattern.compile("[a-fA-F0-9]{16,128}");
    private static final Pattern TOKEN = Pattern.compile("[a-zA-Z0-9_\\-.~+/=]{16,1024}");

    static final class HttpFailure extends IOException {
        private static final long serialVersionUID = 1L;
        final int status;

        HttpFailure(int status) {
            this.status = status;
        }
    }

    private SessionClient() {}

    static String pair(EditorOrigin origin, String code, String name)
            throws IOException, JSONException, GeneralSecurityException {
        KeyStore store = openKeyStore();
        if (!store.containsAlias(KEY_ALIAS)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE);
            generator.initialize(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build());
            generator.generateKeyPair();
        }
        String publicKey = Base64.encodeToString(store.getCertificate(KEY_ALIAS).getPublicKey().getEncoded(),
                Base64.NO_WRAP);
        String deviceId = post(origin, "pair", new JSONObject()
                .put("code", code)
                .put("publicKey", publicKey)
                .put("name", name)).getString("deviceId");
        if (!DEVICE_ID.matcher(deviceId).matches()) {
            throw new IOException("Invalid pairing response");
        }
        return deviceId;
    }

    static String openSession(EditorOrigin origin, String deviceId)
            throws IOException, JSONException, GeneralSecurityException {
        PrivateKey key = (PrivateKey) openKeyStore().getKey(KEY_ALIAS, null);
        if (key == null) {
            throw new GeneralSecurityException("Device key is missing");
        }
        String challenge = post(origin, "challenge", new JSONObject().put("deviceId", deviceId))
                .getString("challenge");
        byte[] bytes = Base64.decode(challenge, Base64.DEFAULT);
        if (bytes.length != 32) {
            throw new IOException("Invalid challenge size");
        }
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(key);
        signature.update(bytes);
        String proof = Base64.encodeToString(signature.sign(), Base64.NO_WRAP);

        String token = post(origin, "session", new JSONObject()
                .put("deviceId", deviceId)
                .put("challenge", challenge)
                .put("signature", proof)).getString("token");
        if (!TOKEN.matcher(token).matches()) {
            throw new IOException("Invalid session response");
        }
        return token;
    }

    static void removeDeviceKey() throws IOException, GeneralSecurityException {
        openKeyStore().deleteEntry(KEY_ALIAS);
    }

    private static KeyStore openKeyStore() throws IOException, GeneralSecurityException {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        return store;
    }

    private static JSONObject post(EditorOrigin origin, String endpoint, JSONObject payload)
            throws IOException, JSONException {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(origin.address() + "/api/mobile/" + endpoint)
                .openConnection();
        try {
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setDoOutput(true);
            byte[] request = payload.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(request.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(request);
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new HttpFailure(status);
            }
            try (InputStream input = connection.getInputStream();
                    ByteArrayOutputStream response = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[2048];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (response.size() + count > MAX_RESPONSE_BYTES) {
                        throw new IOException("Response too large");
                    }
                    response.write(buffer, 0, count);
                }
                return new JSONObject(new String(response.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally {
            connection.disconnect();
        }
    }
}
