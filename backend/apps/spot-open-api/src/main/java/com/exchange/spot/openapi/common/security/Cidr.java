package com.exchange.spot.openapi.common.security;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** Whether an address falls in a CIDR block, or equals a bare address. IPv4 and IPv6. */
public final class Cidr {

    private Cidr() {
    }

    public static boolean contains(String cidrOrAddress, String address) {
        try {
            String[] parts = cidrOrAddress.split("/", 2);
            byte[] net = InetAddress.getByName(parts[0]).getAddress();
            byte[] addr = InetAddress.getByName(address).getAddress();
            if (net.length != addr.length) {
                return false; // an IPv4 block never contains an IPv6 address, or the reverse
            }
            int prefix = parts.length == 2 ? Integer.parseInt(parts[1]) : net.length * 8;
            if (prefix < 0 || prefix > net.length * 8) {
                return false;
            }
            for (int i = 0; i < net.length; i++) {
                int bits = Math.max(0, Math.min(8, prefix - i * 8));
                int mask = bits == 0 ? 0 : (0xFF << (8 - bits)) & 0xFF;
                if ((net[i] & mask) != (addr[i] & mask)) {
                    return false;
                }
            }
            return true;
        } catch (UnknownHostException | NumberFormatException e) {
            return false; // a malformed entry on the allowlist admits nobody, never everybody
        }
    }
}
