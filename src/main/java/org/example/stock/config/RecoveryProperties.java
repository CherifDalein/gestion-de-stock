package org.example.stock.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

@ConfigurationProperties(prefix = "stock.recovery")
public class RecoveryProperties {
    private boolean enabled;
    private String baseUrl = "http://localhost:8080";
    private String from = "";
    private Duration ttl = Duration.ofMinutes(30);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getFrom() { return from; }
    public void setFrom(String from) { this.from = from; }
    public Duration getTtl() { return ttl; }
    public void setTtl(Duration ttl) { this.ttl = ttl; }

    @PostConstruct
    public void valider() {
        URI uri;
        try { uri = URI.create(baseUrl); }
        catch (RuntimeException exception) { throw new IllegalStateException("L'URL de récupération est invalide"); }
        String host = uri.getHost();
        boolean boucleLocale = Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(host == null ? "" : host);
        if (host == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()) && boucleLocale)
                || uri.getPath() != null && !Set.of("", "/").contains(uri.getPath())
                || uri.getPort() == 0 || uri.getPort() > 65535) {
            throw new IllegalStateException("L'URL de récupération doit être une origine HTTPS, ou HTTP sur localhost");
        }
        if (ttl == null || ttl.compareTo(Duration.ofMinutes(1)) < 0 || ttl.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalStateException("Le lien de récupération doit durer entre 1 et 60 minutes");
        }
        if (enabled && (from == null || from.isBlank() || from.indexOf('\n') >= 0 || from.indexOf('\r') >= 0)) {
            throw new IllegalStateException("Configurer l'adresse d'expédition de récupération");
        }
        baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
