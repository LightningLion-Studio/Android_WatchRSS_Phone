package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONObject

/** One public pseudonym per approved legacy device and account scope. Survives credential logout.
 * Descriptor creator keys and registered author keys remain independent P256 keys. */
internal class SyncV2DeviceAlias(private val store:SyncV2AccountStore,account:String,legacy:String,epoch:String,generation:String) {
    private val fence=SyncV2RepoBootstrap.fence(account,legacy,epoch,generation)
    private val name="local/device-alias/$fence"
    private fun read():JSONObject?=store.read(name)?.also {
        SyncV2Codec.unhex(it.getString("deviceId"),16)
        if(it.get("migrationPending") !is Boolean)SyncV2Codec.fail("identity_conflict")
    }
    private fun save(device:String,migration:Boolean,previous:String?=null):String {
        SyncV2Codec.unhex(device,16)
        store.write(name,JSONObject().put("deviceId",device).put("migrationPending",migration)
            .also{if(previous!=null)it.put("previousDeviceId",previous)})
        return device
    }
    /** Import an old immutable pending creator BEFORE repo-init or pending-record deletion. */
    fun bootstrap(pending:JSONObject?):String {
        if(pending!=null && pending.getString("fence")!=fence)SyncV2Codec.fail("scope_mismatch")
        val old=read()
        val creator=pending?.getString("creatorDeviceId")
        if(creator!=null && old!=null && creator!=old.getString("deviceId"))SyncV2Codec.fail("device_alias_recovery_required")
        return old?.getString("deviceId")?:save(creator?:SyncV2Codec.hex(SyncV2Codec.random(16)),false)
    }
    /** A pre-P6J install may have lost pending creator provenance. Keep its signer first.
     * Only an authenticated registration can establish which alias the server bound locally. */
    fun installed(repo:String):String = read()?.getString("deviceId")?:save(
        store.read("local/signing/$repo")?.getString("deviceId")?:SyncV2Codec.hex(SyncV2Codec.random(16)),true)

    /** register returns false ONLY for a definitive invalid_schema from the registration POST.
     * Persist every choice before HTTP, so an unknown outcome retries the same alias and key. */
    fun reconcile(vault:SyncV2SigningVault,descriptor:JSONObject,register:(SyncV2Signing)->Boolean) {
        val record=read()?:SyncV2Codec.fail("identity_conflict")
        if(!record.getBoolean("migrationPending"))return
        if(register(vault.signer)) {save(vault.signer.deviceId,false);return}
        val body=descriptor.getJSONObject("body")
        val creator=body.getString("creatorDeviceId")
        if(body.getString("creatorPlatform")!="android" || creator==vault.signer.deviceId)SyncV2Codec.fail("device_alias_recovery_required")
        // The descriptor is already authenticated. The server still enforces that this alias
        // belongs to this exact approved legacy device. Never reassign a published author.
        vault.requireUnpublished()
        save(creator,true,vault.signer.deviceId)
        vault.rebind(creator)
        if(!register(vault.signer))SyncV2Codec.fail("device_alias_recovery_required")
        save(creator,false)
    }
}
