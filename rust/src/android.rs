//! JNI bridge: `com.foulfoxhacks.mellozone.core.NativeCore.nativeCall`.
#![cfg(target_os = "android")]

use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;

#[no_mangle]
pub extern "system" fn Java_com_foulfoxhacks_mellozone_core_NativeCore_nativeCall<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    method: JString<'local>,
    payload: JString<'local>,
) -> jstring {
    let method: String = env.get_string(&method).map(Into::into).unwrap_or_default();
    let payload: String = env.get_string(&payload).map(Into::into).unwrap_or_default();
    let response = crate::core::dispatch(&method, &payload);
    match env.new_string(response) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
