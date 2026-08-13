package com.github.obhen233.util;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Enumeration;

/**
 * Utility methods for network address resolution.
 *
 * <p>The main purpose is to replace the unreliable
 * {@code InetAddress.getLocalHost().getHostAddress()} pattern, which may
 * return the wrong IP when multiple network interfaces are present (e.g.,
 * VPN adapters, virtual machine bridges, Docker networks).</p>
 */
public final class NetworkUtils {

    private NetworkUtils() {
        // utility class
    }

    /**
     * Resolve a real, routable local IPv4 address by iterating over all
     * available network interfaces.
     *
     * <p>Selection rules (in order):</p>
     * <ol>
     *   <li>Skip loopback interfaces ({@code lo})</li>
     *   <li>Skip interfaces that are not {@code up}</li>
     *   <li>Skip known virtual/VPN interfaces (vmnet, vbox, docker, tap, tun, etc.)</li>
     *   <li>Skip link-local addresses (169.254.x.x)</li>
     *   <li>Return the first matching IPv4 address</li>
     * </ol>
     *
     * <p>If no suitable address is found, falls back to
     * {@code InetAddress.getLocalHost().getHostAddress()}, then to
     * {@code "127.0.0.1"}.</p>
     *
     * @return a real local IPv4 address, never {@code null}
     */
    public static String getRealLocalIP() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    NetworkInterface ni = interfaces.nextElement();
                    try {
                        if (ni.isLoopback() || !ni.isUp()) {
                            continue;
                        }
                    } catch (SocketException e) {
                        continue;
                    }

                    // Skip known virtual / VPN interfaces by name and display name
                    String name = ni.getName().toLowerCase();
                    String displayName = ni.getDisplayName().toLowerCase();
                    if (name.contains("vmnet") || name.contains("vboxnet")
                            || name.contains("docker") || name.contains("veth")
                            || displayName.contains("virtual") || displayName.contains("vmware")
                            || displayName.contains("vpn") || displayName.contains("tap")
                            || displayName.contains("tun") || displayName.contains("bridge")
                            || displayName.contains("hyper-v") || displayName.contains("pseudo")) {
                        continue;
                    }

                    Enumeration<InetAddress> addrs = ni.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        InetAddress addr = addrs.nextElement();
                        if (addr instanceof Inet4Address
                                && !addr.isLoopbackAddress()
                                && !addr.isLinkLocalAddress()) {
                            return addr.getHostAddress();
                        }
                    }
                }
            }
        } catch (SocketException e) {
            // Fall through to fallback
        }

        // Fallback 1: traditional method
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            // Fallback 2: safe default
            return "127.0.0.1";
        }
    }

    /**
     * Check whether the given address string represents a local address.
     * Handles multiple NICs and VPN scenarios correctly by enumerating all
     * interfaces and comparing their assigned addresses.
     *
     * @param address the IP address string to check (e.g. "192.168.1.100")
     * @return {@code true} if the address belongs to a local interface
     */
    public static boolean isLocalAddress(String address) {
        if (address == null || address.isEmpty()) {
            return false;
        }
        if ("localhost".equalsIgnoreCase(address)
                || "127.0.0.1".equals(address)
                || "0.0.0.0".equals(address)) {
            return true;
        }
        // Check all NICs
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            if (nics != null) {
                while (nics.hasMoreElements()) {
                    NetworkInterface ni = nics.nextElement();
                    Enumeration<InetAddress> addrs = ni.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        if (address.equals(addrs.nextElement().getHostAddress())) {
                            return true;
                        }
                    }
                }
            }
        } catch (SocketException e) {
            // Fall through
        }
        // Traditional fallback
        try {
            InetAddress localHost = InetAddress.getLocalHost();
            return localHost != null && address.equals(localHost.getHostAddress());
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
