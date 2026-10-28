package com.example.smarthomedashboard
import org.robolectric.util.ReflectionHelpers
import kotlinx.coroutines.Job

/** Existing gesture tests use an explicitly disabled isolated credential store, never production IO. */
internal fun pinGestureFixture(activity:MainActivity) {
 if(ReflectionHelpers.getField<PinAccess?>(activity,"pinAccess")!=null)return
 val access=PinAccess(object:PinAccess.Storage {
  override fun read()=PinAccess.State(null,null)
  override fun write(state:PinAccess.State)=true
 },object:PinAccess.Legacy{override fun read():String?=null;override fun remove()=true},
 clock=object:PinAccess.Clock{override fun elapsed()=0L;override fun boot()="test"},worker={})
 ReflectionHelpers.setField(activity,"pinAccess",access)
}
internal fun awaitPinGate(activity:MainActivity) {
 weatherAwait {ReflectionHelpers.getField<Job?>(activity,"pendingPin")?.isCompleted!=false}
}
