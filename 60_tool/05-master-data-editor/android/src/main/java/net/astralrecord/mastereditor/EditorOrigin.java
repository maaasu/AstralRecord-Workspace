package net.astralrecord.mastereditor;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

final class EditorOrigin {
    private static final String LABEL = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?";
    private static final Pattern TAILSCALE_HOST = Pattern.compile(LABEL + "\\." + LABEL + "\\.ts\\.net");

    private final String host;
    private final int port;

    private EditorOrigin(String host, int port) {
        this.host = host;
        this.port = port;
    }

    static EditorOrigin parse(String address) {
        try {
            URI uri = new URI(address.trim());
            String host = uri.getHost();
            String path = uri.getRawPath();
            int port = uri.getPort();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (path != null && !path.isEmpty() && !"/".equals(path))
                    || port < -1 || port == 0 || port > 65535) {
                throw new IllegalArgumentException();
            }
            host = host.toLowerCase(Locale.ROOT);
            if (!TAILSCALE_HOST.matcher(host).matches()) {
                throw new IllegalArgumentException();
            }
            return new EditorOrigin(host, port == -1 ? 443 : port);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
    }

    boolean contains(String url) {
        if (url == null) {
            return false;
        }
        try {
            URI uri = new URI(url);
            return "https".equalsIgnoreCase(uri.getScheme()) && host.equalsIgnoreCase(uri.getHost())
                    && uri.getRawUserInfo() == null && port == (uri.getPort() == -1 ? 443 : uri.getPort());
        } catch (URISyntaxException e) {
            return false;
        }
    }

    String address() {
        return "https://" + host + (port == 443 ? "" : ":" + port);
    }

    String editorUrl() {
        return address() + "/?mobile=android";
    }
}
