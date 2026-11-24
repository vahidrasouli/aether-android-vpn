//! Minimal JNI bridge between Android `VpnService` and `CluvexStudio/Aether`.
//!
//! Design goals:
//! - Keep the original Aether Rust engine intact (no engine rewrite).
//! - Enforce a single managed engine instance via Aether's official FFI (`aether_core_start` / `aether_job_*`).
//! - Provide a zero-allocation `VpnService.protect(fd)` callback trampoline registered into `aether::egress`.

use std::ffi::{CStr, CString};
use std::sync::{Mutex, OnceLock};

use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
use jni::sys::{jboolean, jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::{JNIEnv, JavaVM};

static JVM: OnceLock<JavaVM> = OnceLock::new();
static PROTECTOR_REF: OnceLock<Mutex<Option<GlobalRef>>> = OnceLock::new();
static LOG_CALLBACK_REF: OnceLock<Mutex<Option<GlobalRef>>> = OnceLock::new();

fn protector_slot() -> &'static Mutex<Option<GlobalRef>> {
    PROTECTOR_REF.get_or_init(|| Mutex::new(None))
}

fn log_callback_slot() -> &'static Mutex<Option<GlobalRef>> {
    LOG_CALLBACK_REF.get_or_init(|| Mutex::new(None))
}

extern "C" {
    fn __android_log_write(
        prio: std::os::raw::c_int,
        tag: *const std::os::raw::c_char,
        text: *const std::os::raw::c_char,
    ) -> std::os::raw::c_int;
}

struct JniAetherLogger;

impl log::Log for JniAetherLogger {
    fn enabled(&self, metadata: &log::Metadata) -> bool {
        metadata.level() <= log::Level::Info
    }

    fn log(&self, record: &log::Record) {
        if !self.enabled(record.metadata()) {
            return;
        }

        thread_local! {
            static IN_LOG: std::cell::Cell<bool> = const { std::cell::Cell::new(false) };
        }

        let entered = IN_LOG.with(|cell| {
            if cell.get() {
                false
            } else {
                cell.set(true);
                true
            }
        });

        if !entered {
            return;
        }

        let formatted = format!("{}", record.args());

        // 1. Android Logcat
        #[cfg(target_os = "android")]
        {
            let prio = match record.level() {
                log::Level::Error => 6, // ANDROID_LOG_ERROR
                log::Level::Warn => 5,  // ANDROID_LOG_WARN
                log::Level::Info => 4,  // ANDROID_LOG_INFO
                log::Level::Debug => 3, // ANDROID_LOG_DEBUG
                log::Level::Trace => 2, // ANDROID_LOG_VERBOSE
            };
            let is_psiphon = record.target().contains("psiphon")
                || formatted.contains("[psiphon]")
                || formatted.to_lowercase().contains("psiphon");
            let tag = if is_psiphon { b"Psiphon\0" } else { b"Aether\0" };
            if let Ok(c_msg) = CString::new(formatted.clone()) {
                unsafe {
                    __android_log_write(prio, tag.as_ptr().cast(), c_msg.as_ptr());
                }
            }
        }

        // 2. Dispatch to Kotlin AetherLogCallback.onLog(level, msg)
        if let Some(vm) = JVM.get() {
            let callback_ref = {
                let guard = match log_callback_slot().lock() {
                    Ok(g) => g,
                    Err(poisoned) => poisoned.into_inner(),
                };
                guard.clone()
            };

            if let Some(cb_obj) = callback_ref {
                if let Ok(mut env) = vm.attach_current_thread_permanently() {
                    let level_int: jint = match record.level() {
                        log::Level::Error => 1,
                        log::Level::Warn => 2,
                        log::Level::Info => 3,
                        _ => 4,
                    };
                    if let Ok(jmsg) = env.new_string(&formatted) {
                        let _ = env.call_method(
                            cb_obj.as_obj(),
                            "onLog",
                            "(ILjava/lang/String;)V",
                            &[JValue::Int(level_int), (&jmsg).into()],
                        );
                        let _ = env.exception_clear();
                    }
                }
            }
        }

        IN_LOG.with(|cell| cell.set(false));
    }

    fn flush(&self) {}
}

static AETHER_LOGGER: JniAetherLogger = JniAetherLogger;

/// C callback registered in `aether::egress::set_socket_protector`.
/// Attaches the current Rust/Tokio worker thread permanently as a daemon thread to the JVM
/// and invokes `AetherNative.protectFd(fd)` -> `VpnService.protect(fd)`.
extern "C" fn android_protect_socket(fd: i32) -> i32 {
    let Some(vm) = JVM.get() else {
        return 0;
    };
    let protector = {
        let guard = match protector_slot().lock() {
            Ok(g) => g,
            Err(poisoned) => poisoned.into_inner(),
        };
        guard.clone()
    };
    let Some(obj_ref) = protector else {
        // No active VpnService registered (e.g. pre-TUN bootstrap); allow socket creation.
        return 1;
    };

    let Ok(mut env) = vm.attach_current_thread_permanently() else {
        return 0;
    };

    match env.call_method(
        obj_ref.as_obj(),
        "protectSocket",
        "(I)Z",
        &[JValue::Int(fd as jint)],
    ) {
        Ok(val) => match val.z() {
            Ok(true) => 1,
            _ => 0,
        },
        Err(_) => {
            let _ = env.exception_clear();
            0
        }
    }
}

fn take_aether_json(env: &mut JNIEnv, raw: *mut std::ffi::c_char) -> jstring {
    if raw.is_null() {
        return env
            .new_string("{\"ok\":false,\"error\":\"null native reply\"}")
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut());
    }
    let text = unsafe {
        let s = CStr::from_ptr(raw).to_string_lossy().into_owned();
        aether::ffi::aether_string_free(raw);
        s
    };
    env.new_string(text)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_io_aethervpn_android_core_AetherNative_nativeInitLogging(
    env: JNIEnv,
    _class: JClass,
) {
    if JVM.get().is_none() {
        if let Ok(vm) = env.get_java_vm() {
            let _ = JVM.set(vm);
        }
    }

    let _ = log::set_logger(&AETHER_LOGGER);
    log::set_max_level(log::LevelFilter::Info);
}

#[no_mangle]
pub extern "system" fn Java_io_aethervpn_android_core_AetherNative_nativeRegisterLogCallback(
    env: JNIEnv,
    _class: JClass,
    callback_obj: JObject,
) -> jboolean {
    if JVM.get().is_none() {
        if let Ok(vm) = env.get_java_vm() {
            let _ = JVM.set(vm);
        }
    }

    let mut slot = match log_callback_slot().lock() {
        Ok(g) => g,
        Err(poisoned) => poisoned.into_inner(),
    };

    if callback_obj.is_null() {
        *slot = None;
        return JNI_TRUE;
    }

    match env.new_global_ref(callback_obj) {
        Ok(global_ref) => {
            *slot = Some(global_ref);
            JNI_TRUE
        }
        Err(_) => JNI_FALSE,
    }
}

#[no_mangle]
pub extern "system" fn Java_io_aethervpn_android_core_AetherNative_nativeRegisterProtector(
    env: JNIEnv,
    _class: JClass,
    protector_obj: JObject,
) -> jboolean {
    if JVM.get().is_none() {
        if let Ok(vm) = env.get_java_vm() {
            let _ = JVM.set(vm);
        }
    }

    let mut slot = match protector_slot().lock() {
        Ok(g) => g,
        Err(poisoned) => poisoned.into_inner(),
    };

    if protector_obj.is_null() {
        *slot = None;
        aether::egress::set_socket_protector(None);
        return JNI_TRUE;
    }

    match env.new_global_ref(protector_obj) {
        Ok(global_ref) => {
            *slot = Some(global_ref);
            aether::egress::set_socket_protector(Some(android_protect_socket));
            JNI_TRUE
        }
        Err(_) => JNI_FALSE,
    }
}

#[no_mangle]
pub extern "system" fn Java_io_aethervpn_android_core_AetherNative_nativeVersion(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let raw = aether::ffi::aether_version();
    take_aether_json(&mut env, raw)
}

#[no_mangle]
pub extern "system" fn Java_io_aethervpn_android_core_AetherNative_nativeStartCore(
    mut env: JNIEnv,
    _class: JClass,
    args_json: JString,
) -> jstring {
    let args_str: String = match env.get_string(&args_json) {
        Ok(s) => s.into(),
        Err(_) => {
            return env
                .new_string("{\"ok\":false,\"error\":\"invalid utf-8 args_json\"}")
                .map(|s| s.into_raw())
                .unwrap_or(std::ptr::null_mut());
        }
    };

    let c_args = match CString::new(args_str) {
        Ok(c) => c,
        Err(_) => {
            return env
                .new_string("{\"ok\":false,\"error\":\"args_json contained null byte\"}")
                .map(|s| s.into_raw())
                .unwrap_or(std::ptr::null_mut());
        }
    };

    let raw = unsafe { aether::ffi::aether_core_start(c_args.as_ptr()) };
    take_aether_json(&mut env, raw)
}

#[no_mangle]
pub extern "system" fn Java_io_aethervpn_android_core_AetherNative_nativePollJob(
    mut env: JNIEnv,
    _class: JClass,
    job_id: jlong,
) -> jstring {
    let raw = aether::ffi::aether_job_poll(job_id as u64);
    take_aether_json(&mut env, raw)
}

#[no_mangle]
pub extern "system" fn Java_io_aethervpn_android_core_AetherNative_nativeCancelJob(
    mut env: JNIEnv,
    _class: JClass,
    job_id: jlong,
) -> jstring {
    let raw = aether::ffi::aether_job_cancel(job_id as u64);
    take_aether_json(&mut env, raw)
}

#[no_mangle]
pub extern "system" fn Java_io_aethervpn_android_core_AetherNative_nativeFreeJob(
    mut env: JNIEnv,
    _class: JClass,
    job_id: jlong,
) -> jstring {
    let raw = aether::ffi::aether_job_free(job_id as u64);
    take_aether_json(&mut env, raw)
}

/// Upstream BoringSSL folded `SSL_CTX_set_grease_sigalgs_enabled` into
/// `SSL_CTX_set_grease_enabled`. This compatibility definition satisfies any
/// legacy dynamic linker references and ensures zero unresolved dynamic symbols.
#[no_mangle]
pub unsafe extern "C" fn SSL_CTX_set_grease_sigalgs_enabled(
    _ctx: *mut std::ffi::c_void,
    _enabled: std::os::raw::c_int,
) {
    // Handled via BoringSSL SSL_CTX_set_grease_enabled
}

