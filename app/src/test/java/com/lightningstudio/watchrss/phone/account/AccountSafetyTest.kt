package com.lightningstudio.watchrss.phone.account
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class AccountSafetyTest {
 @Test fun pendingNoticeHasDateAndNoAccountOwnershipMutation(){
  val profile=parseAccountSafetyProfile(JSONObject("""{"userId":"same-user","phoneMasked":"138****0000","pendingPhoneChange":{"appealId":"appeal","status":"cooling","effectiveAt":1893456000000,"phoneMasked":"139****0000","cancellable":true}}"""))
  assertEquals("same-user",profile.userId);assertTrue(profile.pendingPhoneChange!!.message.contains("2030年1月1日"));assertTrue(profile.pendingPhoneChange!!.cancellable)
  assertNull(parseAccountSafetyProfile(JSONObject("""{"userId":"same-user","phoneMasked":"138****0000","pendingPhoneChange":null}""")).pendingPhoneChange)
 }
 @Test fun r31AndRecoveryMessagesAreActionableAndNeverEchoServerDetail(){
  assertTrue(accountSafetyErrorMessage(PhoneAccountHttpException(409,"""{"error":"wechat_login_linked_to_other_account","detail":"private"}""")).contains("登录原账号解绑"))
  assertFalse(accountSafetyErrorMessage(PhoneAccountHttpException(500,"private")).contains("private"))
 }
}
