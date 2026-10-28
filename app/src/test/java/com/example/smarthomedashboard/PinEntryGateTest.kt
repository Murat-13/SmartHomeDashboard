package com.example.smarthomedashboard
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

class IsolatedPinGateActivity:PinProtectedActivity()
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[28])
@LooperMode(LooperMode.Mode.PAUSED)
class PinEntryGateTest {
 @Before fun reset(){PinSecurityUi.session.revoke()}
 private fun activity(enabled:Boolean=false,error:Boolean=false):IsolatedPinGateActivity {
  val activity=Robolectric.buildActivity(IsolatedPinGateActivity::class.java).get()
  weatherLive(activity);activity.window.setContentView(FrameLayout(activity))
  activity.getSharedPreferences("dashboard_prefs",0).edit().putInt("pin_session_minutes",5).commit()
  val access=PinAccess(object:PinAccess.Storage {
   override fun read():PinAccess.State? {if(error)error("artificial storage failure");return PinAccess.State(if(enabled)"test-record-unused-by-status" else null,null)}
   override fun write(state:PinAccess.State)=true
  },object:PinAccess.Legacy{override fun read():String?=null;override fun remove()=true},
  clock=object:PinAccess.Clock{override fun elapsed()=0L;override fun boot()="test"},worker={})
  ReflectionHelpers.setField(activity,"pinGateAccess",access)
  return activity
 }
 private fun gate(activity:IsolatedPinGateActivity){ReflectionHelpers.callInstanceMethod<Unit>(activity,"requestPinAccess")}
 private fun content(activity:IsolatedPinGateActivity)=activity.window.decorView.findViewById<View>(android.R.id.content)
 @Test fun disabledCredentialDecisionAllowsExistingSettingsWithoutPin(){
  val a=activity();gate(a)
  weatherAwait{ReflectionHelpers.getField<Boolean>(a,"pinAuthorized")}
  assertEquals(View.VISIBLE,content(a).visibility)
 }
 @Test fun credentialStorageErrorNeverRevealsSettings(){
  val a=activity(error=true);gate(a)
  weatherAwait{a.isFinishing}
  assertFalse(ReflectionHelpers.getField(a,"pinAuthorized"));assertEquals(View.INVISIBLE,content(a).visibility)
 }
 @Test fun currentProcessSessionAllowsEntryAndRevocationRequiresAuthenticationOnNextInteraction(){
  val a=activity(enabled=true);PinSecurityUi.session.grant();gate(a)
  weatherAwait{ReflectionHelpers.getField<Boolean>(a,"pinAuthorized")}
  assertTrue(ReflectionHelpers.callInstanceMethod(a,"isPinAccessAllowed"))
  PinSecurityUi.session.revoke()
  assertFalse(ReflectionHelpers.callInstanceMethod(a,"isPinAccessAllowed"))
  gate(a)
  assertFalse(ReflectionHelpers.getField(a,"pinAuthorized"))
 }
 @Test fun lackOfAuthorizationBlocksDirectTouchBeforeAsyncDecision(){
  val a=activity()
  val event=android.view.MotionEvent.obtain(0,0,android.view.MotionEvent.ACTION_UP,1f,1f,0)
  try{assertTrue(a.dispatchTouchEvent(event));assertFalse(ReflectionHelpers.getField(a,"pinAuthorized"))}finally{event.recycle()}
 }
}
