// Diagnostic binary: resolve shortcuts with HRESULTs printed.
use std::collections::HashMap;
use std::path::PathBuf;
use windows_sys::core::GUID;

const CLSID_SHELL_LINK: GUID = GUID::from_u128(0x00021401_0000_0000_c000_000000000046);
const IID_ISHELL_LINK_W: GUID = GUID::from_u128(0x000214f9_0000_0000_c000_000000000046);
const IID_IPERSIST_FILE: GUID = GUID::from_u128(0x0000010b_0000_0000_c000_000000000046);

#[repr(C)]
struct IUnknownVtbl {
    query_interface: unsafe extern "system" fn(*mut core::ffi::c_void, *const GUID, *mut *mut core::ffi::c_void) -> i32,
    add_ref: unsafe extern "system" fn(*mut core::ffi::c_void) -> u32,
    release: unsafe extern "system" fn(*mut core::ffi::c_void) -> u32,
}
#[repr(C)]
struct IShellLinkWVtbl {
    unknown: IUnknownVtbl,
    get_path: unsafe extern "system" fn(*mut core::ffi::c_void, windows_sys::core::PWSTR, i32, *mut windows_sys::Win32::Storage::FileSystem::WIN32_FIND_DATAW, u32) -> i32,
}
#[repr(C)]
struct IPersistFileVtbl {
    unknown: IUnknownVtbl,
    get_class_id: unsafe extern "system" fn(*mut core::ffi::c_void, *mut GUID) -> i32,
    is_dirty: unsafe extern "system" fn(*mut core::ffi::c_void) -> i32,
    load: unsafe extern "system" fn(*mut core::ffi::c_void, windows_sys::core::PCWSTR, u32) -> i32,
}

fn hr_name(hr: i32) -> String {
    let candidates = [
        (0i32, "S_OK"), (1, "S_FALSE"), (-2147024809, "E_INVALIDARG"), (-2147467259, "E_FAIL"),
        (-2147467263, "E_NOINTERFACE"), (-2147221164, "CLASS_E_NOAGGREGATION"), (-2147221008, "CO_E_NOTINITIALIZED"),
        (-2147287038, "STG_E_FILENOTFOUND"), (-2147287036, "STG_E_PATHNOTFOUND"),
        (-2147024894, "E_OUTOFMEMORY"), (-2147467262, "E_POINTER"),
    ];
    for (code, name) in candidates { if code == hr { return name.into(); } }
    format!("0x{:08X}", hr as u32)
}

unsafe fn release(ptr: *mut core::ffi::c_void) {
    let vt = *(ptr as *mut *const IUnknownVtbl);
    ((*vt).release)(ptr);
}

fn main() {
    unsafe { windows_sys::Win32::System::Com::CoInitializeEx(std::ptr::null(), 0x2 | 0x4) };
    let appdata = std::env::var("APPDATA").unwrap();
    let root = PathBuf::from(appdata).join("Microsoft\\Windows\\Start Menu\\Programs");
    let mut links = Vec::new();
    let mut stack = vec![root];
    while let Some(dir) = stack.pop() {
        for entry in std::fs::read_dir(&dir).unwrap().flatten() {
            let p = entry.path();
            if entry.file_type().map(|t| t.is_dir()).unwrap_or(false) { stack.push(p); }
            else if p.extension().and_then(|e| e.to_str()).is_some_and(|e| e.eq_ignore_ascii_case("lnk")) { links.push(p); }
        }
    }
    println!("links: {}", links.len());
    let mut stats: HashMap<String, usize> = HashMap::new();
    let mut examples: Vec<String> = Vec::new();
    unsafe {
        for link in links.iter().take(80) {
            let mut obj: *mut core::ffi::c_void = std::ptr::null_mut();
            let hr_create = windows_sys::Win32::System::Com::CoCreateInstance(&CLSID_SHELL_LINK, std::ptr::null_mut(), windows_sys::Win32::System::Com::CLSCTX_INPROC_SERVER, &IID_ISHELL_LINK_W, &mut obj);
            if hr_create != 0 { *stats.entry(format!("create:{}", hr_name(hr_create))).or_default() += 1; continue; }
            let vt_unknown = *(obj as *mut *const IUnknownVtbl);
            let mut ipf: *mut core::ffi::c_void = std::ptr::null_mut();
            let hr_qi = ((*vt_unknown).query_interface)(obj, &IID_IPERSIST_FILE, &mut ipf);
            if hr_qi != 0 { *stats.entry(format!("qi:{}", hr_name(hr_qi))).or_default() += 1; release(obj); continue; }
            let wide: Vec<u16> = link.as_os_str().to_string_lossy().encode_utf16().chain(std::iter::once(0)).collect();
            let vt_persist = *(ipf as *mut *const IPersistFileVtbl);
            let hr_load = ((*vt_persist).load)(ipf, wide.as_ptr(), 0);
            if hr_load != 0 {
                *stats.entry(format!("load:{}", hr_name(hr_load))).or_default() += 1;
                if examples.len() < 8 { examples.push(format!("{} -> load {}", link.file_name().unwrap().to_string_lossy(), hr_name(hr_load))); }
                release(ipf); release(obj); continue;
            }
            let mut buffer = [0u16; 260];
            let mut fd: windows_sys::Win32::Storage::FileSystem::WIN32_FIND_DATAW = std::mem::zeroed();
            let vt_link = *(obj as *mut *const IShellLinkWVtbl);
            let hr_gp = ((*vt_link).get_path)(obj, buffer.as_mut_ptr(), 260, &mut fd, 0x2);
            if hr_gp != 0 { *stats.entry(format!("getpath:{}", hr_name(hr_gp))).or_default() += 1; }
            else {
                let len = buffer.iter().position(|&c| c == 0).unwrap_or(0);
                if len == 0 { *stats.entry("getpath:empty".into()).or_default() += 1; }
                else { *stats.entry("OK".into()).or_default() += 1; }
            }
            release(ipf); release(obj);
        }
    }
    println!("stats: {:?}", stats);
    for e in examples { println!("  {}", e); }
}
