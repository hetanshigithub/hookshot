package com.hookshot.delivery;

import com.hookshot.config.HookshotProperties;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import org.springframework.stereotype.Component;

/**
 * SSRF protection. A webhook service makes HTTP requests to URLs chosen by its users, so without
 * this check anyone could point an endpoint at {@code http://169.254.169.254/} (cloud metadata) or
 * an internal admin service. We validate at registration and again at send time, because DNS
 * answers can change between the two.
 */
@Component
public class TargetValidator {

    private final boolean allowPrivate;

    public TargetValidator(HookshotProperties properties) {
        this.allowPrivate = properties.allowPrivateTargets();
    }

    public void validate(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new InvalidTargetException("Malformed URL");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new InvalidTargetException("URL scheme must be http or https");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new InvalidTargetException("URL must include a host");
        }
        if (uri.getUserInfo() != null) {
            throw new InvalidTargetException("Credentials in the URL are not allowed");
        }
        if (allowPrivate) {
            return;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new InvalidTargetException("Host cannot be resolved");
        }
        for (InetAddress address : addresses) {
            if (!AddressPolicy.isPublic(address)) {
                throw new InvalidTargetException("Target resolves to a non-public address");
            }
        }
    }
}
