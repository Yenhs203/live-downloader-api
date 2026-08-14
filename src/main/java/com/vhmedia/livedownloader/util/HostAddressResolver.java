package com.vhmedia.livedownloader.util;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Resolves a hostname to one or more addresses. Extracted for testability.
 */
@FunctionalInterface
public interface HostAddressResolver {

	InetAddress[] resolve(String host) throws UnknownHostException;
}
