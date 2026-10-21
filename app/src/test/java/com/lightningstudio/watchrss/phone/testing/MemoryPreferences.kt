package com.lightningstudio.watchrss.phone.testing

import android.content.SharedPreferences

/** Android-independent persistence double, including the commit-failure boundary. */
class MemoryPreferences : SharedPreferences {
    private val values = linkedMapOf<String, Any>()
    var failCommit = false
    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String?, defValue: String?) = values[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?) = values[key] as? MutableSet<String> ?: defValues
    override fun getInt(key: String?, defValue: Int) = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long) = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float) = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean) = values[key] as? Boolean ?: defValue
    override fun contains(key: String?) = key in values
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        val changed = linkedMapOf<String, Any?>(); var clear = false
        private fun set(key: String?, value: Any?): SharedPreferences.Editor { changed[requireNotNull(key)] = value; return this }
        override fun putString(key: String?, value: String?) = set(key,value)
        override fun putStringSet(key: String?, values: MutableSet<String>?) = set(key,values)
        override fun putInt(key: String?, value: Int) = set(key,value)
        override fun putLong(key: String?, value: Long) = set(key,value)
        override fun putFloat(key: String?, value: Float) = set(key,value)
        override fun putBoolean(key: String?, value: Boolean) = set(key,value)
        override fun remove(key: String?) = set(key,null)
        override fun clear(): SharedPreferences.Editor { clear=true;return this }
        override fun commit(): Boolean {
            if(failCommit)return false
            if(clear)values.clear()
            changed.forEach { (key,value) -> if(value==null) values.remove(key) else values[key]=value }
            return true
        }
        override fun apply() { check(commit()) }
    }
}
