package com.hookshot.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AddressPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254", "0.0.0.0",
        "100.64.0.1", "198.18.0.1", "224.0.0.1", "255.255.255.255", "::1", "fd00::1", "fe80::1", "::ffff:10.0.0.1"
    })
    void blocksNonPublicAddresses(String ip) throws UnknownHostException {
        assertThat(AddressPolicy.isPublic(InetAddress.getByName(ip))).as(ip).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "1.1.1.1", "93.184.216.34", "100.63.0.1", "172.32.0.1", "2606:4700:4700::1111"})
    void allowsPublicAddresses(String ip) throws UnknownHostException {
        assertThat(AddressPolicy.isPublic(InetAddress.getByName(ip))).as(ip).isTrue();
    }
}
