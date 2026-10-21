package com.lightningstudio.watchrss.phone.account

import com.lightningstudio.watchrss.phone.connection.bluetooth.watchBluetoothFailureMessage
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WatchAccountConflictTest {
    private val message="这块手表已登录其他账号，请先在手表上退出"
    private val conflicts=listOf("account_switch_requires_logout","device_owned_by_another_account","installation_owned_by_another_account","device_revoked")
    @Test fun issuanceConflictsHaveActionableMessagesAndRetainTypedHttpStatusAndErrorCodes() {
        for(code in conflicts)for(field in listOf("error","error_code","code")) {
            val body=JSONObject().put(field,code).toString();val status=if(code=="device_revoked")403 else 409
            val error=PhoneAccountHttpException(status,body)
            assertEquals(message,error.message);assertEquals(status,error.statusCode);assertEquals(body,error.responseBody)
            assertEquals(code,error.accountHttpErrorCode())
        }
    }
    @Test fun bluetoothAndHttpUseTheSameConflictGuidanceWithoutReplacingUnrelatedErrors() {
        for(code in conflicts)for(field in listOf("error","errorCode")) {
            assertEquals(message,watchBluetoothFailureMessage(JSONObject().put(field,code).put("message","generic failure")))
        }
        assertEquals("server detail",watchBluetoothFailureMessage(JSONObject().put("errorCode","other").put("message","server detail")))
        assertEquals("账号请求失败（HTTP 409）",PhoneAccountHttpException(409,"{\"error\":\"phone_in_use\"}").message)
        assertEquals("账号请求失败（HTTP 500）",PhoneAccountHttpException(500,"not JSON").message)
    }
}
