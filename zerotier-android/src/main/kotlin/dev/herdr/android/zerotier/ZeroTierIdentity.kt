package dev.herdr.android.zerotier

/** Formatting only; the native validator also verifies the public identity and private signature. */
internal class ZeroTierIdentity private constructor(val secret: String, val public: String, val nodeId: String) {
    override fun toString() = "ZeroTierIdentity(nodeId=$nodeId)"
    companion object {
        fun parse(text: String): ZeroTierIdentity {
            val lines = text.removePrefix("\uFEFF").trim().lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            val secrets = lines.filter { it.count { char -> char == ':' } == 3 }
            require(secrets.size == 1) { "请导入完整的 ZeroTier identity.secret，不能使用 SSH 私钥或单独的公钥。" }
            val value = secrets.single().lowercase()
            require(Regex("[0-9a-f]{10}:0:[0-9a-f]{128}:[0-9a-f]{128}").matches(value)) { "ZeroTier identity.secret 格式无效" }
            val public = value.substringBeforeLast(':')
            require(lines.all { it.lowercase() == value || it.lowercase() == public }) { "导入内容包含多个不同的节点身份" }
            return ZeroTierIdentity(value, public, value.substringBefore(':'))
        }

        fun networkId(text: String): Long {
            val normalized = text.trim().lowercase().removePrefix("0x")
            require(Regex("[0-9a-f]{16}").matches(normalized) && normalized != "0000000000000000") { "ZeroTier Network ID 必须为 16 位十六进制数字" }
            return normalized.toULong(16).toLong()
        }
    }
}
