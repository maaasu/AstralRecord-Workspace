import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;

// Run with the exact Java executable registered in Control. Uses the JVM default
// trust manager and normal hostname/IP verification; no authentication is sent.
class TestApiTls {
    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("No arguments are accepted.");
        for (String address : new String[] {
                "https://app-01:444/api/health",
                "https://192.168.0.88:444/api/health" }) {
            HttpURLConnection connection = (HttpURLConnection) URI.create(address).toURL().openConnection(Proxy.NO_PROXY);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setRequestMethod("GET");
            try {
                int status = connection.getResponseCode();
                if (status != 200 && status != 401) {
                    throw new IllegalStateException("Unexpected HTTP status for " + address + ": " + status);
                }
                System.out.println("TLS verified: " + address + " HTTP " + status);
            } finally {
                connection.disconnect();
            }
        }
    }
}
