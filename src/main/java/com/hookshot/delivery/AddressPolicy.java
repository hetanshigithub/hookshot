package com.hookshot.delivery;

import java.net.InetAddress;

/** Decides whether an IP address is safe to send webhooks to (i.e. a public address). */
public final class AddressPolicy {

    private AddressPolicy() {}

    public static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress() // includes 169.254.169.254, the cloud metadata endpoint
                || address.isSiteLocalAddress() // 10/8, 172.16/12, 192.168/16
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] b = address.getAddress();
        if (b.length == 4) {
            int first = b[0] & 0xFF;
            int second = b[1] & 0xFF;
            int third = b[2] & 0xFF;
            if (first == 0) {
                return false; // 0.0.0.0/8
            }
            if (first == 100 && second >= 64 && second <= 127) {
                return false; // 100.64.0.0/10 carrier-grade NAT
            }
            if (first == 192 && second == 0 && third == 0) {
                return false; // 192.0.0.0/24 IETF protocol assignments
            }
            if (first == 198 && (second == 18 || second == 19)) {
                return false; // 198.18.0.0/15 benchmarking
            }
            if (first >= 240) {
                return false; // reserved + broadcast
            }
        } else if (b.length == 16) {
            if ((b[0] & 0xFE) == 0xFC) {
                return false; // fc00::/7 unique local addresses
            }
        }
        return true;
    }
}
