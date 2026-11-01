package com.sametgkte.gktedpi.profiles

import android.content.Context
import android.content.SharedPreferences
import com.sametgkte.gktedpi.utility.getPreferences
import com.sametgkte.gktedpi.utility.getStringNotNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class DpiProfile(
    val id: String,
    val name: String,
    val useCommandLine: Boolean,
    val commandArgs: String,
    val dnsIp: String,
    val dnsSecondary: String,
    val vpnMtu: Int,
    val lanBypass: Boolean,
    val ipv6Enable: Boolean,
    val settingsSnapshot: Map<String, Any?> = emptyMap(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("useCommandLine", useCommandLine)
        put("commandArgs", commandArgs)
        put("dnsIp", dnsIp)
        put("dnsSecondary", dnsSecondary)
        put("vpnMtu", vpnMtu)
        put("lanBypass", lanBypass)
        put("ipv6Enable", ipv6Enable)
        val snap = JSONObject()
        for ((k, v) in settingsSnapshot) {
            val isGlobalKey = k in EXCLUDED_GLOBAL_KEYS ||
                k.startsWith("smart_") ||
                k.startsWith("app_trigger_") ||
                k.startsWith("shizuku_") ||
                k.startsWith("glass_") ||
                k.startsWith("gkte_glass_") ||
                k.startsWith("gkte_vpn_") ||
                k.startsWith("gkte_tunnel_") ||
                k.startsWith("api_")
            if (v != null && !isGlobalKey) {
                snap.put(k, v)
            }
        }
        put("settingsSnapshot", snap)
    }

    companion object {
        val EXCLUDED_GLOBAL_KEYS = setOf(
            "app_theme",
            "active_profile_id",
            "split_tunnel_apps",
            "split_tunnel_mode",
            "auto_start_boot",
            "auto_connect_launch",
            "cmd_history_json"
        )

        fun fromJson(obj: JSONObject): DpiProfile {
            val snapObj = obj.optJSONObject("settingsSnapshot")
            val map = mutableMapOf<String, Any?>()
            if (snapObj != null) {
                val keys = snapObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    map[k] = snapObj.opt(k)
                }
            }
            return DpiProfile(
                id = obj.optString("id", UUID.randomUUID().toString()),
                name = obj.optString("name", "Varsayılan Profil"),
                useCommandLine = obj.optBoolean("useCommandLine", false),
                commandArgs = obj.optString("commandArgs", "-d 1 -r 1+s -M h,d -a 2"),
                dnsIp = obj.optString("dnsIp", "1.1.1.1"),
                dnsSecondary = obj.optString("dnsSecondary", "1.0.0.1"),
                vpnMtu = obj.optInt("vpnMtu", 8500),
                lanBypass = obj.optBoolean("lanBypass", true),
                ipv6Enable = obj.optBoolean("ipv6Enable", false),
                settingsSnapshot = map,
            )
        }
    }
}

object ProfileManager {
    private const val STORE_NAME = "gktedpi_profiles_store"
    private const val KEY_PROFILES_JSON = "profiles_json"
    private const val KEY_ACTIVE_ID = "active_profile_id"

    private fun store(context: Context): SharedPreferences =
        context.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)

    fun getProfiles(context: Context): List<DpiProfile> {
        val raw = store(context).getString(KEY_PROFILES_JSON, null)
        if (raw.isNullOrBlank()) {
            val initial = createInitialProfiles(context)
            saveProfilesList(context, initial)
            return initial
        }
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<DpiProfile>()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { list.add(DpiProfile.fromJson(it)) }
            }
            if (list.isEmpty()) createInitialProfiles(context) else list
        } catch (_: Exception) {
            createInitialProfiles(context)
        }
    }

    private fun createInitialProfiles(context: Context): List<DpiProfile> {
        val prefs = context.getPreferences()
        val defaultProfile = captureFromPreferences(
            prefs = prefs,
            id = "default",
            name = "Otomatik (Önerilen)"
        )
        val discordVoice = DpiProfile(
            id = "preset_discord",
            name = "Discord & Sesli Sohbet",
            useCommandLine = true,
            commandArgs = "-d 1+s -r 1+s -a 5 -F",
            dnsIp = "1.1.1.1",
            dnsSecondary = "1.0.0.1",
            vpnMtu = 8500,
            lanBypass = true,
            ipv6Enable = false,
        )
        val strongAccess = DpiProfile(
            id = "preset_superonline",
            name = "Güçlü Erişim",
            useCommandLine = true,
            commandArgs = "-s 1+s -d 3+s -r 1+s -M h,d,r -a 3 -A t,r,s -T 2.5 -f -1 -S -t 6",
            dnsIp = "94.140.14.14",
            dnsSecondary = "94.140.15.15",
            vpnMtu = 1450,
            lanBypass = true,
            ipv6Enable = false,
        )
        store(context).edit().putString(KEY_ACTIVE_ID, defaultProfile.id).apply()
        return listOf(defaultProfile, discordVoice, strongAccess)
    }

    private fun saveProfilesList(context: Context, profiles: List<DpiProfile>) {
        val arr = JSONArray()
        profiles.forEach { arr.put(it.toJson()) }
        store(context).edit().putString(KEY_PROFILES_JSON, arr.toString()).apply()
    }

    fun getActiveProfile(context: Context): DpiProfile {
        val profiles = getProfiles(context)
        val activeId = store(context).getString(KEY_ACTIVE_ID, null)
        return profiles.firstOrNull { it.id == activeId } ?: profiles.first()
    }

    fun syncCurrentToActiveProfile(context: Context) {
        val profiles = getProfiles(context).toMutableList()
        val active = getActiveProfile(context)
        val updated = captureFromPreferences(context.getPreferences(), active.id, active.name)
        val idx = profiles.indexOfFirst { it.id == active.id }
        if (idx >= 0) {
            profiles[idx] = updated
            saveProfilesList(context, profiles)
        }
    }

    fun activateProfile(context: Context, profileId: String): DpiProfile? {
        // First persist edits on current active profile
        syncCurrentToActiveProfile(context)
        val profiles = getProfiles(context)
        val target = profiles.firstOrNull { it.id == profileId } ?: return null
        applyProfileToPreferences(context, target)
        store(context).edit().putString(KEY_ACTIVE_ID, target.id).apply()
        return target
    }

    fun createNewProfile(context: Context, name: String, fromPreset: StrategyPreset? = null): DpiProfile {
        syncCurrentToActiveProfile(context)
        val profiles = getProfiles(context).toMutableList()
        val newId = UUID.randomUUID().toString()
        val newProfile = if (fromPreset != null) {
            DpiProfile(
                id = newId,
                name = name.ifBlank { fromPreset.nameTr },
                useCommandLine = true,
                commandArgs = fromPreset.commandArgs,
                dnsIp = fromPreset.recommendedDns,
                dnsSecondary = "1.0.0.1",
                vpnMtu = fromPreset.recommendedMtu,
                lanBypass = true,
                ipv6Enable = false,
            )
        } else {
            captureFromPreferences(context.getPreferences(), newId, name.ifBlank { "Yeni Profil" })
        }
        profiles.add(newProfile)
        saveProfilesList(context, profiles)
        applyProfileToPreferences(context, newProfile)
        store(context).edit().putString(KEY_ACTIVE_ID, newProfile.id).apply()
        return newProfile
    }

    fun applyPresetToActive(context: Context, preset: StrategyPreset) {
        val prefs = context.getPreferences()
        prefs.edit()
            .putBoolean("byedpi_enable_cmd_settings", true)
            .putString("byedpi_cmd_args", preset.commandArgs)
            .putString("dns_ip", preset.recommendedDns)
            .putString("vpn_mtu", preset.recommendedMtu.toString())
            .apply()
        addCommandHistory(context, preset.commandArgs)
        syncCurrentToActiveProfile(context)
    }

    fun applyCustomCommandToActive(context: Context, commandArgs: String) {
        val prefs = context.getPreferences()
        prefs.edit()
            .putBoolean("byedpi_enable_cmd_settings", true)
            .putString("byedpi_cmd_args", commandArgs)
            .apply()
        addCommandHistory(context, commandArgs)
        syncCurrentToActiveProfile(context)
    }

    fun deleteProfile(context: Context, profileId: String): Boolean {
        val profiles = getProfiles(context).toMutableList()
        if (profiles.size <= 1) return false
        val removed = profiles.removeAll { it.id == profileId }
        if (!removed) return false
        saveProfilesList(context, profiles)
        if (store(context).getString(KEY_ACTIVE_ID, null) == profileId) {
            val next = profiles.first()
            applyProfileToPreferences(context, next)
            store(context).edit().putString(KEY_ACTIVE_ID, next.id).apply()
        }
        return true
    }

    fun renameProfile(context: Context, profileId: String, newName: String) {
        val profiles = getProfiles(context).toMutableList()
        val idx = profiles.indexOfFirst { it.id == profileId }
        if (idx >= 0 && newName.isNotBlank()) {
            profiles[idx] = profiles[idx].copy(name = newName.trim())
            saveProfilesList(context, profiles)
        }
    }

    fun addCommandHistory(context: Context, cmd: String) {
        val trimmed = cmd.trim()
        if (trimmed.isEmpty()) return
        val prefs = context.getPreferences()
        val existing = getCommandHistory(context).toMutableList()
        existing.remove(trimmed)
        existing.add(0, trimmed)
        val capped = existing.take(12)
        val arr = JSONArray()
        capped.forEach { arr.put(it) }
        prefs.edit().putString("cmd_history_json", arr.toString()).apply()
    }

    fun getCommandHistory(context: Context): List<String> {
        val raw = context.getPreferences().getString("cmd_history_json", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val item = arr.optString(i)
                    if (item.isNotBlank()) add(item)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun exportFullBackupJson(context: Context): String {
        syncCurrentToActiveProfile(context)
        val prefs = context.getPreferences()
        val root = JSONObject()
        root.put("app", "GkTeDPI")
        root.put("package", "com.sametgkte.gktedpi")
        root.put("version", 2)
        root.put("activeProfileId", getActiveProfile(context).id)

        val profilesArr = JSONArray()
        getProfiles(context).forEach { profilesArr.put(it.toJson()) }
        root.put("profiles", profilesArr)

        val globalObj = JSONObject()
        for ((k, v) in prefs.all) {
            when (v) {
                is Set<*> -> {
                    val arr = JSONArray()
                    v.forEach { item -> if (item != null) arr.put(item.toString()) }
                    globalObj.put(k, arr)
                }
                null -> Unit
                else -> globalObj.put(k, v)
            }
        }
        root.put("preferences", globalObj)
        return root.toString(2)
    }

    fun importFullBackupJson(context: Context, jsonText: String): Boolean = try {
        val root = JSONObject(jsonText)
        val prefs = context.getPreferences()
        val editor = prefs.edit()

        root.optJSONObject("preferences")?.let { globalObj ->
            val keys = globalObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = globalObj.opt(k)
                when (v) {
                    is Boolean -> editor.putBoolean(k, v)
                    is Int -> editor.putInt(k, v)
                    is Long -> editor.putLong(k, v)
                    is Double -> editor.putFloat(k, v.toFloat())
                    is String -> editor.putString(k, v)
                    is JSONArray -> {
                        val set = HashSet<String>()
                        for (i in 0 until v.length()) {
                            set.add(v.optString(i))
                        }
                        editor.putStringSet(k, set)
                    }
                }
            }
        }
        editor.apply()

        root.optJSONArray("profiles")?.let { arr ->
            val imported = mutableListOf<DpiProfile>()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { imported.add(DpiProfile.fromJson(it)) }
            }
            if (imported.isNotEmpty()) {
                saveProfilesList(context, imported)
                val activeId = root.optString("activeProfileId", imported.first().id)
                store(context).edit().putString(KEY_ACTIVE_ID, activeId).apply()
            }
        }
        true
    } catch (_: Exception) {
        false
    }

    private fun captureFromPreferences(
        prefs: SharedPreferences,
        id: String,
        name: String,
    ): DpiProfile {
        val snap = mutableMapOf<String, Any?>()
        for ((k, v) in prefs.all) {
            if (k !in DpiProfile.EXCLUDED_GLOBAL_KEYS && v !is Set<*>) {
                snap[k] = v
            }
        }
        return DpiProfile(
            id = id,
            name = name,
            useCommandLine = prefs.getBoolean("byedpi_enable_cmd_settings", false),
            commandArgs = prefs.getStringNotNull("byedpi_cmd_args", "-d 1 -r 1+s -M h,d -a 2"),
            dnsIp = prefs.getStringNotNull("dns_ip", "1.1.1.1"),
            dnsSecondary = prefs.getStringNotNull("dns_ip_secondary", "1.0.0.1"),
            vpnMtu = prefs.getString("vpn_mtu", null)?.toIntOrNull() ?: 8500,
            lanBypass = prefs.getBoolean("lan_bypass", true),
            ipv6Enable = prefs.getBoolean("ipv6_enable", false),
            settingsSnapshot = snap,
        )
    }

    private fun applyProfileToPreferences(context: Context, profile: DpiProfile) {
        val prefs = context.getPreferences()
        val editor = prefs.edit()
        for ((k, v) in profile.settingsSnapshot) {
            when (v) {
                is Boolean -> editor.putBoolean(k, v)
                is Int -> editor.putInt(k, v)
                is Long -> editor.putLong(k, v)
                is Float -> editor.putFloat(k, v)
                is Double -> editor.putFloat(k, v.toFloat())
                is String -> editor.putString(k, v)
            }
        }
        editor.putBoolean("byedpi_enable_cmd_settings", profile.useCommandLine)
        editor.putString("byedpi_cmd_args", profile.commandArgs)
        editor.putString("dns_ip", profile.dnsIp)
        editor.putString("dns_ip_secondary", profile.dnsSecondary)
        editor.putString("vpn_mtu", profile.vpnMtu.toString())
        editor.putBoolean("lan_bypass", profile.lanBypass)
        editor.putBoolean("ipv6_enable", profile.ipv6Enable)
        editor.apply()
    }
}
