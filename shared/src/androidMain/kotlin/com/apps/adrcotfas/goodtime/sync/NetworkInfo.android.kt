/**
 *     Goodtime Productivity
 *     Copyright (C) 2025 Adrian Cotfas
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.apps.adrcotfas.goodtime.sync

import java.net.Inet6Address
import java.net.NetworkInterface
import java.util.Collections

actual fun getLocalIpAddresses(): List<String> =
    runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { it.isUp && !it.isLoopback }
            .flatMap { Collections.list(it.inetAddresses) }
            .filterNot { it.isAnyLocalAddress }
            .mapNotNull { addr ->
                when (addr) {
                    is Inet6Address -> if (addr.isLinkLocalAddress) null else addr.hostAddress
                    else -> addr.hostAddress
                }
            }
            .distinct()
    }.getOrDefault(emptyList())
