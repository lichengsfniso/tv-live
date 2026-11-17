package com.tvbridge.app

/**
 * 频道 ID 迁移器。
 *
 * m3u 频道 ID 由 digest("$scope:${tvg-id 或 name}") 生成，央视 ID 为 yangshipin-$pid；
 * 虽然已非数组下标，但“改名”仍会改变 ID。迁移按三级匹配：
 *   1. ID 精确命中；
 *   2. 首条流 URL 摘要命中；
 *   3. 规范化名称命中。
 * 无法映射的收藏保留为孤儿 ID，绝不删除。
 */
object ChannelIdMigrator {

    /** 去所有空白并去掉 @SD/HD/FHD/UHD/4K 后缀；结果为空时回退到原名称。 */
    @JvmStatic fun normalize(name: String): String {
        val stripped = name.trim()
            .replace(Regex("\\s+"), "")
            .replace(Regex("@?(SD|HD|FHD|UHD|4K)$", RegexOption.IGNORE_CASE), "")
        return stripped.ifEmpty { name.trim() }
    }

    /**
     * @return 迁移后的收藏 ID 集合（命中新 ID + 保留的孤儿 ID，自动去重）
     */
    fun migrate(newChannels: List<LiveChannel>, oldIds: Set<String>, oldChannels: List<LiveChannel>): Set<String> {
        val newById = newChannels.associateBy { it.id }
        val oldById = oldChannels.associateBy { it.id }

        val streamToId = mutableMapOf<String, String>()
        val nameToId = mutableMapOf<String, String>()
        newChannels.forEach { channel ->
            channel.streams.firstOrNull()?.let { stream ->
                val key = PlaylistParser.digest(stream)
                if (!streamToId.containsKey(key)) streamToId[key] = channel.id
            }
            val nameKey = normalize(channel.name)
            if (!nameToId.containsKey(nameKey)) nameToId[nameKey] = channel.id
        }

        val result = LinkedHashSet<String>()
        oldIds.forEach { oldId ->
            if (newById.containsKey(oldId)) {
                result.add(oldId)
                return@forEach
            }
            val old = oldById[oldId]
            val byStream = old?.streams?.firstOrNull()
                ?.let { streamToId[PlaylistParser.digest(it)] }
            val byName = old?.let { nameToId[normalize(it.name)] }
            // 三级都不命中：保留孤儿 ID，不删除用户收藏
            result.add(byStream ?: byName ?: oldId)
        }
        return result
    }
}
