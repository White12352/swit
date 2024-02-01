package io.nekohasekai.sagernet.group

import android.annotation.SuppressLint
import android.net.Uri
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteria1Json
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.parseShadowsocks
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.trojan_go.parseTrojanGo
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.isTLS
import io.nekohasekai.sagernet.fmt.v2ray.setTLS
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.ktx.*
import libcore.Libcore
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.utils.JavaUtil.gson
import org.ini4j.Ini
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.StringReader

@Suppress("EXPERIMENTAL_API_USAGE")
object RawUpdater : GroupUpdater() {

    @SuppressLint("Recycle")
    override suspend fun doUpdate(
        proxyGroup: ProxyGroup,
        subscription: SubscriptionBean,
        userInterface: GroupManager.Interface?,
        byUser: Boolean
    ) {

        val link = subscription.link
        var proxies: List<AbstractBean>
        if (link.startsWith("content://")) {
            val contentText = app.contentResolver.openInputStream(Uri.parse(link))
                ?.bufferedReader()
                ?.readText()

            proxies = contentText?.let { parseRaw(contentText) }
                ?: error(app.getString(R.string.no_proxies_found_in_subscription))
        } else {

            val response = Libcore.newHttpClient().apply {
                trySocks5(DataStore.mixedPort)
                when (DataStore.appTLSVersion) {
                    "1.3" -> restrictedTLS()
                }
                setInsecure(DataStore.allowInsecureOnRequest)
            }.newRequest().apply {
                setURL(subscription.link)
                setUserAgent(subscription.customUserAgent.takeIf { it.isNotBlank() } ?: USER_AGENT)
            }.execute()
            proxies = parseRaw(response.contentString)
                ?: error(app.getString(R.string.no_proxies_found))

            subscription.subscriptionUserinfo = response.getHeader("Subscription-Userinfo")
        }

        val proxiesMap = LinkedHashMap<String, AbstractBean>()
        for (proxy in proxies) {
            var index = 0
            var name = proxy.displayName()
            while (proxiesMap.containsKey(name)) {
                println("Exists name: $name")
                index++
                name = name.replace(" (${index - 1})", "")
                name = "$name ($index)"
                proxy.name = name
            }
            proxiesMap[proxy.displayName()] = proxy
        }
        proxies = proxiesMap.values.toList()

        if (subscription.forceResolve) forceResolve(proxies, proxyGroup.id)

        val exists = SagerDatabase.proxyDao.getByGroup(proxyGroup.id)
        val duplicate = ArrayList<String>()
        if (subscription.deduplication) {
            Logs.d("Before deduplication: ${proxies.size}")
            val uniqueProxies = LinkedHashSet<Protocols.Deduplication>()
            val uniqueNames = HashMap<Protocols.Deduplication, String>()
            for (_proxy in proxies) {
                val proxy = Protocols.Deduplication(_proxy, _proxy.javaClass.toString())
                if (!uniqueProxies.add(proxy)) {
                    val index = uniqueProxies.indexOf(proxy)
                    if (uniqueNames.containsKey(proxy)) {
                        val name = uniqueNames[proxy]!!.replace(" ($index)", "")
                        if (name.isNotBlank()) {
                            duplicate.add("$name ($index)")
                            uniqueNames[proxy] = ""
                        }
                    }
                    duplicate.add(_proxy.displayName() + " ($index)")
                } else {
                    uniqueNames[proxy] = _proxy.displayName()
                }
            }
            uniqueProxies.retainAll(uniqueNames.keys)
            proxies = uniqueProxies.toList().map { it.bean }
        }

        Logs.d("New profiles: ${proxies.size}")

        val nameMap = proxies.associateBy { bean ->
            bean.displayName()
        }

        Logs.d("Unique profiles: ${nameMap.size}")

        val toDelete = ArrayList<ProxyEntity>()
        val toReplace = exists.mapNotNull { entity ->
            val name = entity.displayName()
            if (nameMap.contains(name)) name to entity else let {
                toDelete.add(entity)
                null
            }
        }.toMap()

        Logs.d("toDelete profiles: ${toDelete.size}")
        Logs.d("toReplace profiles: ${toReplace.size}")

        val toUpdate = ArrayList<ProxyEntity>()
        val added = mutableListOf<String>()
        val updated = mutableMapOf<String, String>()
        val deleted = toDelete.map { it.displayName() }

        var userOrder = 1L
        var changed = toDelete.size
        for ((name, bean) in nameMap.entries) {
            if (toReplace.contains(name)) {
                val entity = toReplace[name]!!
                val existsBean = entity.requireBean()
                existsBean.applyFeatureSettings(bean)
                when {
                    existsBean != bean -> {
                        changed++
                        entity.putBean(bean)
                        toUpdate.add(entity)
                        updated[entity.displayName()] = name

                        Logs.d("Updated profile: $name")
                    }

                    entity.userOrder != userOrder -> {
                        entity.putBean(bean)
                        toUpdate.add(entity)
                        entity.userOrder = userOrder

                        Logs.d("Reordered profile: $name")
                    }

                    else -> {
                        Logs.d("Ignored profile: $name")
                    }
                }
            } else {
                changed++
                SagerDatabase.proxyDao.addProxy(ProxyEntity(
                    groupId = proxyGroup.id, userOrder = userOrder
                ).apply {
                    putBean(bean)
                })
                added.add(name)
                Logs.d("Inserted profile: $name")
            }
            userOrder++
        }

        SagerDatabase.proxyDao.updateProxy(toUpdate).also {
            Logs.d("Updated profiles: $it")
        }

        SagerDatabase.proxyDao.deleteProxy(toDelete).also {
            Logs.d("Deleted profiles: $it")
        }

        val existCount = SagerDatabase.proxyDao.countByGroup(proxyGroup.id).toInt()

        if (existCount != proxies.size) {
            Logs.e("Exist profiles: $existCount, new profiles: ${proxies.size}")
        }

        subscription.lastUpdated = (System.currentTimeMillis() / 1000).toInt()
        SagerDatabase.groupDao.updateGroup(proxyGroup)
        finishUpdate(proxyGroup)

        userInterface?.onUpdateSuccess(
            proxyGroup, changed, added, updated, deleted, duplicate, byUser
        )
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun parseRaw(text: String, fileName: String = ""): List<AbstractBean>? {

        val proxies = mutableListOf<AbstractBean>()

        if (text.contains("outbounds")) {

            // sing-box

            try {

                val json = gson.fromJson(text, Map::class.java)

                for (proxy in (json["outbounds"] as? (List<Map<String, Any?>>) ?: error(
                    app.getString(R.string.no_proxies_found_in_file)
                ))) {
                    // Note: YAML numbers parsed as "Long"

                    when (proxy["type"] as String) {
                        "socks" -> {
                            proxies.add(SOCKSBean().apply {
                                serverAddress = proxy["server"] as String
                                serverPort = proxy["server_port"].toString().toInt()
                                username = proxy["username"]?.toString()
                                password = proxy["password"]?.toString()
                                name = proxy["tag"]?.toString()
                                protocol = when (proxy["version"]?.toString()) {
                                    "4" -> SOCKSBean.PROTOCOL_SOCKS4
                                    "4a" -> SOCKSBean.PROTOCOL_SOCKS4A
                                    else -> SOCKSBean.PROTOCOL_SOCKS5
                                }
                                sUoT = proxy["udp_over_tcp"]?.toString() == "true"
                            })
                        }

                        "http" -> {
                            proxies.add(HttpBean().apply {
                                serverAddress = proxy["server"] as String
                                serverPort = proxy["server_port"].toString().toInt()
                                username = proxy["username"]?.toString()
                                password = proxy["password"]?.toString()
                                setTLS(proxy["tls"]?.toString() == "true")
                                sni = proxy["sni"]?.toString()
                                name = proxy["tag"]?.toString()
                            })
                        }

                        "shadowsocks" -> {
                            val ssPlugin = mutableListOf<String>().apply {
                                add(proxy["plugin"].toString())
                                add(proxy["plugin_opts"].toString())
                            }
                            proxies.add(ShadowsocksBean().apply {
                                serverAddress = proxy["server"] as String
                                serverPort = proxy["server_port"].toString().toInt()
                                password = proxy["password"]?.toString()
                                method = proxy["method"] as String
                                plugin = ssPlugin.joinToString(";")
                                name = proxy["tag"]?.toString()
                                sUoT = proxy["udp_over_tcp"]?.toString() == "true"
                            })
                        }

                        "vmess", "vless", "trojan" -> {
                            val protocol = when (proxy["type"]?.toString()) {
                                "vless" -> 0
                                "vmess" -> 1
                                "trojan" -> 2
                                else -> 0
                            }
                            val bean = when (protocol) {
                                0, 1 -> VMessBean().apply {
                                    alterId = when (protocol) {
                                        0 -> -1
                                        else -> proxy["alter_id"]?.toString()?.toInt()
                                    }
                                    packetEncoding = when (proxy["packet_encoding"]?.toString()) {
                                        "packetaddr" -> 1
                                        "xudp" -> 2
                                        else -> if (protocol == 1) 2 else 0 // VLESS use XUDP
                                    }
                                }

                                else -> TrojanBean()
                            }


                            for (opt in proxy) {
                                if (opt.value == null) continue
                                when (opt.key) {
                                    "tag" -> bean.name = opt.value.toString()
                                    "uuid" -> bean.uuid = opt.value.toString()
                                    "flow" -> if (protocol == 0) bean.encryption =
                                        opt.value.toString()

                                    "security" -> if (protocol == 1) bean.encryption =
                                        opt.value.toString()

                                    "transport" -> {
                                        for (transportOpt in (opt.value as Map<String, Any>)) {
                                            when (transportOpt.key) {
                                                "type" -> bean.type = transportOpt.value as String
                                                "host" -> bean.host = transportOpt.value as
                                                        String

                                                "path", "service_name" -> bean.path =
                                                    transportOpt.value as String

                                                "max_early_data" -> bean.wsMaxEarlyData =
                                                    transportOpt.value
                                                        .toString().toInt()

                                                "early_data_header_name" -> bean.earlyDataHeaderName =
                                                    transportOpt.value as String
                                            }
                                        }
                                    }

                                    "tls" -> {
                                        for (tlsOpt in (opt.value as Map<String, Any>)) {
                                            when (tlsOpt.key) {
                                                "enabled" -> bean.setTLS(tlsOpt.value.toString() == "true")
                                                "server_name" -> bean.sni = tlsOpt.value as String
                                                "insecure" -> bean.allowInsecure =
                                                    tlsOpt.value.toString() ==
                                                            "true"

                                                "alpn" -> {
                                                    val alpn = (tlsOpt.value as? (List<String>))
                                                    bean.alpn = alpn?.joinToString("\n")
                                                }

                                                "certificate" -> bean.certificates =
                                                    tlsOpt.value as String

                                                "ech" -> {
                                                    for (echOpt in (tlsOpt.value as Map<String, Any>)) {
                                                        when (echOpt.key) {
                                                            "enabled" -> bean.ech =
                                                                echOpt.value.toString() ==
                                                                        "true"

                                                            "config" -> bean.echCfg =
                                                                echOpt.value as String
                                                        }
                                                    }
                                                }

                                                "utls" -> {
                                                    for (utlsOpt in (tlsOpt.value as Map<String, Any>)) {
                                                        when (utlsOpt.key) {
                                                            "fingerprint" -> bean.utlsFingerprint =
                                                                utlsOpt
                                                                    .value as String
                                                        }
                                                    }
                                                }

                                                "reality" -> {
                                                    for (realityOpt in (tlsOpt.value as Map<String, Any>)) {
                                                        when (realityOpt.key) {
                                                            "public_key" -> bean.realityPubKey =
                                                                realityOpt
                                                                    .value
                                                                        as String

                                                            "short_id" -> bean.realityShortId =
                                                                realityOpt
                                                                    .value as String
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                }

                            }
                            proxies.add(bean)
                        }

                        "wireguard" -> {
                            val peers = proxy["peers"] as? List<Map<String, Any?>>

                            // If it has peers, use the first.
                            val configToUse = peers?.firstOrNull() ?: proxy

                            val bean = WireGuardBean().apply {
                                name = proxy["tag"].toString()

                                serverAddress = configToUse["server"] as String
                                serverPort = configToUse["server_port"].toString().toInt()
                                localAddress = {
                                    val addrList = configToUse["local_address"] as (List<String>)
                                    addrList.joinToString("\n")
                                }.toString()
                                privateKey = configToUse["private_key"] as String
                                peerPublicKey = configToUse["peer_public_key"] as String
                                peerPreSharedKey = configToUse["pre_shared_key"] as String
                                mtu = configToUse["mtu"]?.toString()?.toIntOrNull() ?: 1408
                                reserved = {
                                    val reservedList = (configToUse["reserved"] as? List<String>)
                                    reservedList?.joinToString(",")
                                }.toString()
                            }

                            proxies.add(bean)
                        }

                        "hysteria" -> {
                            var hopPorts = ""
                            val bean = HysteriaBean().apply {
                                protocolVersion = 1

                                for (opt in proxy) {
                                    if (opt.value == null) continue
                                    when (opt.key) {
                                        "tag" -> name = opt.value.toString()
                                        "server" -> serverAddress = opt.value.toString()
                                        "server_port" -> serverPort = opt.value.toString().toInt()
                                        "ports" -> hopPorts = opt.value.toString()

                                        "obfs" -> obfuscation = opt.value.toString()

                                        "auth" -> {
                                            authPayloadType = HysteriaBean.TYPE_BASE64
                                            authPayload = opt.value.toString()
                                        }

                                        "auth_str" -> {
                                            authPayloadType = HysteriaBean.TYPE_STRING
                                            authPayload = opt.value.toString()
                                        }

                                        "tls" -> {
                                            for (tlsOpt in (proxy["tls"] as Map<String, Any>)) {
                                                when (tlsOpt.key) {
                                                    "server_name" -> sni = tlsOpt.value as String
                                                    "insecure" -> allowInsecure =
                                                        tlsOpt.value.toString() ==
                                                                "true"

                                                    "alpn" -> {
                                                        val alpnTmp =
                                                            (tlsOpt.value as? (List<String>))
                                                        alpn = alpnTmp?.joinToString("\n")
                                                    }

                                                    "certificate" -> caText = tlsOpt.value as String
                                                    "ech" -> {
                                                        for (echOpt in (tlsOpt.value as Map<String, Any>)) {
                                                            when (echOpt.key) {
                                                                "enabled" -> ech =
                                                                    echOpt.value.toString() ==
                                                                            "true"

                                                                "config" -> echCfg =
                                                                    echOpt.value as String
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            if (hopPorts.isNotBlank()) {
                                bean.serverPorts = hopPorts
                            }
                            proxies.add(bean)
                        }

                        "hysteria2" -> {
                            val bean = HysteriaBean()
                            bean.protocolVersion = 2
                            var hopPorts = ""
                            for (opt in proxy) {
                                if (opt.value == null) continue
                                when (opt.key) {
                                    "tag" -> bean.name = opt.value.toString()
                                    "server" -> bean.serverAddress = opt.value as String
                                    "server_port" -> bean.serverPorts = opt.value.toString()
                                    "ports" -> hopPorts = opt.value.toString()

                                    "obfs" -> {
                                        for (obfsOpt in (opt.value as Map<String, Any>)) {
                                            when (obfsOpt.key) {
                                                "password" -> bean.obfuscation = obfsOpt.value
                                                    .toString()
                                            }
                                        }
                                    }

                                    "password" -> bean.authPayload = opt.value.toString()

                                    "tls" -> {
                                        for (tlsOpt in (opt.value as Map<String, Any>)) {
                                            when (tlsOpt.key) {
                                                "server_name" -> bean.sni = tlsOpt.value as String
                                                "insecure" -> bean.allowInsecure = tlsOpt.value
                                                    .toString() ==
                                                        "true"

                                                "alpn" -> {
                                                    val alpn = (tlsOpt.value as? (List<String>))
                                                    bean.alpn = alpn?.joinToString("\n")
                                                }

                                                "certificate" -> bean.caText = tlsOpt.value as
                                                        String

                                                "ech" -> {
                                                    for (echOpt in (tlsOpt.value as Map<String, Any>)) {
                                                        when (echOpt.key) {
                                                            "enabled" -> bean.ech =
                                                                echOpt.value.toString() ==
                                                                        "true"

                                                            "config" -> bean.echCfg = echOpt
                                                                .value as String
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                }
                            }
                            if (hopPorts.isNotBlank()) {
                                bean.serverPorts = hopPorts
                            }
                            proxies.add(bean)
                        }

                        "tuic" -> {
                            val bean = TuicBean()
                            var ip = ""
                            for (opt in proxy) {
                                if (opt.value == null) continue
                                when (opt.key) {
                                    "tag" -> bean.name = opt.value.toString()
                                    "server" -> bean.serverAddress = opt.value.toString()
                                    "ip" -> ip = opt.value.toString()
                                    "server_port" -> bean.serverPort = opt.value.toString().toInt()
                                    "uuid" -> bean.uuid = opt.value.toString()
                                    "password" -> bean.token = opt.value.toString()
                                    "zero_rtt_handshake" -> bean.reduceRTT =
                                        opt.value.toString() == "true"

                                    "congestion_control" -> bean.congestionController =
                                        opt.value.toString()

                                    "udp_relay_mode" -> bean.udpRelayMode = opt.value.toString()
                                    "udp_over_stream" -> {
                                        if (opt.value.toString() == "true") {
                                            bean.udpRelayMode = "UDP over Stream"
                                        }
                                    }

                                    "tls" -> {
                                        for (tlsOpt in (opt.value as Map<String, Any>)) {
                                            when (tlsOpt.key) {
                                                "server_name" -> bean.sni = tlsOpt.value as String
                                                "disable_sni" -> bean.disableSNI = tlsOpt.value
                                                    .toString() == "true"

                                                "insecure" -> bean.allowInsecure = tlsOpt.value
                                                    .toString() ==
                                                        "true"

                                                "alpn" -> {
                                                    val alpn = (tlsOpt.value as? (List<String>))
                                                    bean.alpn = alpn?.joinToString("\n")
                                                }

                                                "certificate" -> bean.caText = tlsOpt.value as
                                                        String

                                                "ech" -> {
                                                    for (echOpt in (tlsOpt.value as Map<String, Any>)) {
                                                        when (echOpt.key) {
                                                            "enabled" -> bean.ech =
                                                                echOpt.value.toString() ==
                                                                        "true"

                                                            "config" -> bean.echCfg = echOpt
                                                                .value as String
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                }
                            }
                            if (ip.isNotBlank()) {
                                bean.serverAddress = ip
                                if (bean.sni.isNullOrBlank() && !bean.serverAddress.isNullOrBlank() && !bean.serverAddress.isIpAddress()) {
                                    bean.sni = bean.serverAddress
                                }
                            }
                            proxies.add(bean)
                        }

                        "ssh" -> {
                            val bean = SSHBean()
                            for (opt in proxy) {
                                if (opt.value == null) continue
                                when (opt.key) {
                                    "tag" -> bean.name = opt.value.toString()
                                    "server" -> bean.serverAddress = opt.value.toString()
                                    "server_port" -> bean.serverPort = opt.value.toString().toInt()

                                    "user" -> bean.username = opt.value.toString()
                                    "password" -> bean.password = opt.value.toString()
                                    "private_key" -> bean.privateKey = opt.value.toString()
                                    "private_key_passphrase" -> bean.privateKeyPassphrase =
                                        opt.value.toString()
                                    "host_key" -> {
                                        val hostKey = (opt.value as? List<String>)
                                        if (hostKey != null) {
                                            bean.publicKey = hostKey.first()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Fix ent
                proxies.forEach {
                    it.initializeDefaultValues()
                    if (it is StandardV2RayBean) {
                        // 1. SNI
                        if (it.isTLS() && it.sni.isNullOrBlank() && !it.host.isNullOrBlank() && !it.host.isIpAddress()) {
                            it.sni = it.host
                        }
                    }
                }
                return proxies
            } catch (e: Exception) {
                Logs.w(e)
            }
        } else if (text.contains("[Interface]")) {
            // wireguard
            try {
                proxies.addAll(parseWireGuard(text).map {
                    if (fileName.isNotBlank()) it.name = fileName.removeSuffix(".conf")
                    it
                })
                return proxies
            } catch (e: Exception) {
                Logs.w(e)
            }
        }

        try {
            val json = JSONTokener(text).nextValue()
            return parseJSON(json)
        } catch (ignored: Exception) {
        }

        try {
            return parseProxies(text.decodeBase64UrlSafe()).takeIf { it.isNotEmpty() }
                ?: error("Not found")
        } catch (e: Exception) {
            Logs.w(e)
        }

        try {
            return parseProxies(text).takeIf { it.isNotEmpty() } ?: error("Not found")
        } catch (e: SubscriptionFoundException) {
            throw e
        } catch (ignored: Exception) {
        }

        return null
    }

    fun parseWireGuard(conf: String): List<WireGuardBean> {
        val ini = Ini(StringReader(conf))
        val iface = ini["Interface"] ?: error("Missing 'Interface' selection")
        val bean = WireGuardBean().applyDefaultValues()
        val localAddresses = iface.getAll("Address")
        if (localAddresses.isNullOrEmpty()) error("Empty address in 'Interface' selection")
        bean.localAddress = localAddresses.flatMap { it.split(",") }.joinToString("\n")
        bean.privateKey = iface["PrivateKey"]
        bean.mtu = iface["MTU"]?.toIntOrNull() ?: 1408
        val peers = ini.getAll("Peer")
        if (peers.isNullOrEmpty()) error("Missing 'Peer' selections")
        val beans = mutableListOf<WireGuardBean>()
        loopPeer@ for (peer in peers) {
            val peerBean = bean.clone()
            for ((keyName, keyValue) in peer) {
                when (keyName.lowercase()) {
                    "endpoint" -> {
                        peerBean.serverAddress =  keyValue.substringBeforeLast(":")
                        peerBean.serverPort = keyValue.substringAfterLast(":").toIntOrNull() ?:
                        continue@loopPeer
                    }
                    "publickey" -> peerBean.peerPublicKey = keyValue ?: continue@loopPeer
                    "presharedkey" -> peerBean.peerPreSharedKey = keyValue
                }
            }
            beans.add(peerBean.applyDefaultValues())
        }
        if (beans.isEmpty()) error("Empty available peer list")
        return beans
    }

    fun parseJSON(json: Any): List<AbstractBean> {
        val proxies = ArrayList<AbstractBean>()

        if (json is JSONObject) {
            when {
                json.has("server") && (json.has("up") || json.has("up_mbps")) -> {
                    return listOf(json.parseHysteria1Json())
                }

                json.has("method") -> {
                    return listOf(json.parseShadowsocks())
                }

                json.has("remote_addr") -> {
                    return listOf(json.parseTrojanGo())
                }

                json.has("outbounds") -> {
                    return listOf(ConfigBean().applyDefaultValues().apply {
                        config = json.toStringPretty()
                    })
                }

                json.has("server") && json.has("server_port") -> {
                    return listOf(ConfigBean().applyDefaultValues().apply {
                        type = 1
                        config = json.toStringPretty()
                    })
                }
            }
        } else {
            json as JSONArray
            json.forEach { _, it ->
                if (isJsonObjectValid(it)) {
                    proxies.addAll(parseJSON(it))
                }
            }
        }

        proxies.forEach { it.initializeDefaultValues() }
        return proxies
    }

}
