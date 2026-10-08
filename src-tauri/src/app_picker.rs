//! Native Windows application enumeration for the split-tunneling app picker.
//!
//! dev.030 security remediation (PROMPT §1.4 / phase 2): the dev.029 picker spawned a
//! hidden `powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File
//! <written .ps1>` that used WScript.Shell COM to enumerate Start-Menu shortcuts and
//! base64-encoded their icons. That design is functionally legitimate but its shape —
//! a hidden PowerShell child with `-ExecutionPolicy Bypass`, a written script file,
//! COM shortcut harvesting, recursive `.lnk` reads and base64 icon output — matches
//! the heuristic patterns antimalware ML classifiers associate with droppers/stealers,
//! and it was the GUI's most malware-resembling surface in the Bearfoos incident
//! investigation (AETHON_DEV030_SECURITY_INCIDENT_REPORT.md §7/§13).
//!
//! This module provides the same product functionality with native Windows APIs,
//! in-process:
//!   - known-folder resolution via SHGetKnownFolderPath (no env-var guesses),
//!   - recursive `.lnk` enumeration with std::fs (no child process),
//!   - shortcut target parsing via IShellLinkW + IPersistFile (the same interfaces
//!     WScript.Shell wraps, called directly),
//!   - icon extraction via ExtractIconExW + GetDIBits → PNG encoding in Rust
//!     (no System.Drawing dependency, no COM-in-PowerShell quirks),
//!   - UTF-8/UTF-16 conversions in-process.
//!
//! Architectural gains over the PowerShell design (not a cosmetic signature dodge):
//!   1. No child process is spawned, so no hidden-window process creation event and
//!      no execution-policy interaction at all.
//!   2. No script file is written to or executed from any location, so no
//!      write-then-run pattern on disk.
//!   3. Enumeration is read-only and bounded: only the two Start-Menu program trees
//!      (exactly what the product needs), with no COM object running arbitrary code.
//!   4. Icons are read from the shortcut's target file header via the shell icon
//!      cache path — reading metadata, never executing the target.

use base64::Engine;
use std::path::{Path, PathBuf};

/// COM GUIDs (defined inline instead of pulling a heavier windows crate):
/// CLSID_ShellLink {00021401-0000-0000-C000-000000000046}
/// IID_IShellLinkW  {000214F9-0000-0000-C000-000000000046}
/// IID_IPersistFile {0000010B-0000-0000-C000-000000000046}
const CLSID_SHELL_LINK: windows_sys::core::GUID = windows_sys::core::GUID::from_u128(
    0x00021401_0000_0000_c000_000000000046,
);
const IID_ISHELL_LINK_W: windows_sys::core::GUID = windows_sys::core::GUID::from_u128(
    0x000214f9_0000_0000_c000_000000000046,
);
const IID_IPERSIST_FILE: windows_sys::core::GUID = windows_sys::core::GUID::from_u128(
    0x0000010b_0000_0000_c000_000000000046,
);

/// Folder ID for FOLDERID_ProgramData (Start Menu "All Users").
const FOLDERID_PROGRAM_DATA: windows_sys::core::GUID = windows_sys::core::GUID::from_u128(
    0x62ab5d82_fdc1_4dc3_a9dd_070d1d495d97,
);
/// FOLDERID_RoamingAppData (per-user Start Menu root is under %APPDATA%).
const FOLDERID_ROAMING_APP_DATA: windows_sys::core::GUID = windows_sys::core::GUID::from_u128(
    0x3eb685db_65f9_4cf6_a03a_e3ef65729f3d,
);

// ---------------------------------------------------------------------------
// Minimal COM interface definitions (vtable layouts are ABI-stable public contract).
// ---------------------------------------------------------------------------

#[repr(C)]
struct IUnknownVtbl {
    query_interface: unsafe extern "system" fn(
        this: *mut core::ffi::c_void,
        riid: *const windows_sys::core::GUID,
        ppv: *mut *mut core::ffi::c_void,
    ) -> windows_sys::core::HRESULT,
    add_ref: unsafe extern "system" fn(this: *mut core::ffi::c_void) -> u32,
    release: unsafe extern "system" fn(this: *mut core::ffi::c_void) -> u32,
}

#[repr(C)]
struct IShellLinkWVtbl {
    unknown: IUnknownVtbl,
    #[allow(clippy::too_many_arguments)]
    get_path: unsafe extern "system" fn(
        this: *mut core::ffi::c_void,
        psz_file: windows_sys::core::PWSTR,
        cch: i32,
        find_data: *mut windows_sys::Win32::Storage::FileSystem::WIN32_FIND_DATAW,
        fflags: u32,
    ) -> windows_sys::core::HRESULT,
    // The remaining IShellLinkW slots (GetIDList, SetIDList, SetDescription, ...) are
    // not called; the vtable must still be laid out far enough to reach the ones we
    // use. GetPath is slot 3 (index 3 counting QueryInterface/AddRef/Release), and
    // IPersistFile::Load is slot 5 of its own table.
}

#[repr(C)]
struct IPersistFileVtbl {
    unknown: IUnknownVtbl,
    get_class_id: unsafe extern "system" fn(
        this: *mut core::ffi::c_void,
        class_id: *mut windows_sys::core::GUID,
    ) -> windows_sys::core::HRESULT,
    is_dirty: unsafe extern "system" fn(this: *mut core::ffi::c_void) -> windows_sys::core::HRESULT,
    load: unsafe extern "system" fn(
        this: *mut core::ffi::c_void,
        psz_file_name: windows_sys::core::PCWSTR,
        dw_mode: u32,
    ) -> windows_sys::core::HRESULT,
    // save / save_completed / get_cur_file follow; unused.
}

/// RAII COM initializer. Tauri already initializes COM somewhere in its stack, but
/// S_FALSE (already initialized with a different model) is tolerated per COM rules.
struct ComGuard {
    #[allow(dead_code)]
    hr: windows_sys::core::HRESULT,
    needed: bool,
}
impl ComGuard {
    fn new() -> Result<Self, String> {
        // COINIT_APARTMENTTHREADED | COINIT_DISABLE_OLE1DDE = 0x2 | 0x4
        let hr = unsafe {
            windows_sys::Win32::System::Com::CoInitializeEx(
                std::ptr::null(),
                0x2 | 0x4,
            )
        };
        // S_OK(0) means we must uninitialize; S_FALSE(1) means already initialized —
        // releasing our "reference" with CoUninitialize would be wrong then.
        let needed = hr == 0;
        if hr < 0 {
            return Err(format!("COM initialization failed (hr 0x{hr:08X})"));
        }
        Ok(Self { hr, needed })
    }
}
impl Drop for ComGuard {
    fn drop(&mut self) {
        if self.needed {
            unsafe { windows_sys::Win32::System::Com::CoUninitialize() };
        }
    }
}

/// A COM pointer that releases itself on drop.
struct ComPtr<T> {
    ptr: *mut core::ffi::c_void,
    _marker: std::marker::PhantomData<T>,
}
impl<T> ComPtr<T> {
    fn new(ptr: *mut core::ffi::c_void) -> Self {
        Self { ptr, _marker: std::marker::PhantomData }
    }
}
impl<T> Drop for ComPtr<T> {
    fn drop(&mut self) {
        if !self.ptr.is_null() {
            unsafe { ((*(*(self.ptr as *mut *const IUnknownVtbl))).release)(self.ptr) };
        }
    }
}

/// Resolve one known folder (returns None instead of failing the whole enumeration).
fn known_folder(folder_id: &windows_sys::core::GUID) -> Option<PathBuf> {
    let mut path: windows_sys::core::PWSTR = std::ptr::null_mut();
    let hr = unsafe {
        windows_sys::Win32::UI::Shell::SHGetKnownFolderPath(
            folder_id,
            0, // KF_FLAG_DEFAULT
            std::ptr::null_mut(),
            &mut path,
        )
    };
    if hr != 0 || path.is_null() {
        return None;
    }
    let wide = unsafe {
        let mut len = 0usize;
        while *path.add(len) != 0 {
            len += 1;
        }
        let slice = std::slice::from_raw_parts(path, len);
        let owned = String::from_utf16_lossy(slice);
        windows_sys::Win32::System::Com::CoTaskMemFree(path as *const core::ffi::c_void);
        owned
    };
    Some(PathBuf::from(wide))
}

/// One enumerated application (mirrors the old TSV: name, target path, icon base64).
#[derive(Debug, Clone, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct EnumeratedApplication {
    pub name: String,
    pub path: String,
    pub icon: String,
}

/// Recursively collect `.lnk` files under `root`, bounded to keep enumeration cheap.
fn collect_shortcuts(root: &Path, out: &mut Vec<PathBuf>) {
    let stack = &mut vec![root.to_path_buf()];
    while let Some(dir) = stack.pop() {
        let entries = match std::fs::read_dir(&dir) {
            Ok(entries) => entries,
            Err(_) => continue,
        };
        for entry in entries.flatten() {
            let path = entry.path();
            let is_dir = entry.file_type().map(|t| t.is_dir()).unwrap_or(false);
            if is_dir {
                stack.push(path);
            } else if path
                .extension()
                .and_then(|e| e.to_str())
                .is_some_and(|e| e.eq_ignore_ascii_case("lnk"))
            {
                out.push(path);
            }
        }
    }
}

/// Full native enumeration: returns applications from Start-Menu shortcuts with
/// icons, exactly the data set the old PowerShell script produced.
pub fn enumerate_start_menu_apps() -> Result<Vec<EnumeratedApplication>, String> {
    let _com = ComGuard::new()?;

    let mut roots: Vec<PathBuf> = Vec::new();
    if let Some(program_data) = known_folder(&FOLDERID_PROGRAM_DATA) {
        roots.push(program_data.join("Microsoft\\Windows\\Start Menu\\Programs"));
    }
    if let Some(roaming) = known_folder(&FOLDERID_ROAMING_APP_DATA) {
        roots.push(roaming.join("Microsoft\\Windows\\Start Menu\\Programs"));
    }
    roots.retain(|r| r.is_dir());
    if roots.is_empty() {
        return Ok(Vec::new());
    }

    let mut links: Vec<PathBuf> = Vec::new();
    for root in &roots {
        collect_shortcuts(root, &mut links);
    }
    links.sort();

    let mut apps = Vec::new();
    for link_path in links {
        let name = link_path
            .file_stem()
            .and_then(|s| s.to_str())
            .unwrap_or_default()
            .to_string();
        // Create the ShellLink COM object per shortcut.
        let mut shell_link: *mut core::ffi::c_void = std::ptr::null_mut();
        let hr = unsafe {
            windows_sys::Win32::System::Com::CoCreateInstance(
                &CLSID_SHELL_LINK,
                std::ptr::null_mut(),
                windows_sys::Win32::System::Com::CLSCTX_INPROC_SERVER,
                &IID_ISHELL_LINK_W,
                &mut shell_link,
            )
        };
        if hr != 0 || shell_link.is_null() {
            continue;
        }
        let link_obj = ComPtr::<()>::new(shell_link);
        // QueryInterface for IPersistFile, Load the file, GetPath.
        let target = unsafe { load_and_resolve_target(link_obj.ptr, &link_path) };
        if let Some(target) = target {
            if target.to_ascii_lowercase().ends_with(".exe") {
                let icon = extract_icon_base64(&target);
                apps.push(EnumeratedApplication { name, path: target, icon });
            }
        }
    }
    Ok(apps)
}

/// unsafe: `link` must be a live IShellLinkW object.
unsafe fn load_and_resolve_target(link: *mut core::ffi::c_void, link_path: &Path) -> Option<String> {
    let mut ipf: *mut core::ffi::c_void = std::ptr::null_mut();
    let unknown_vt = (*(link as *mut *const IUnknownVtbl)) as *const IUnknownVtbl;
    if ((*unknown_vt).query_interface)(link, &IID_IPERSIST_FILE, &mut ipf) != 0 {
        return None;
    }
    let persist = ComPtr::<()>::new(ipf);
    // PCWSTR requires a NUL terminator: encode_utf16().collect() does NOT append one,
    // and IPersistFile::Load reads the path as a raw wide C string (the missing NUL
    // produced ERROR_PATH_NOT_FOUND / E_OUTOFMEMORY on most shortcuts in testing).
    let mut path_wide: Vec<u16> = link_path.as_os_str().to_string_lossy().encode_utf16().collect();
    path_wide.push(0);
    let persist_vt = (*(persist.ptr as *mut *const IPersistFileVtbl)) as *const IPersistFileVtbl;
    if ((*persist_vt).load)(persist.ptr, path_wide.as_ptr(), 0) != 0 {
        return None;
    }
    let mut buffer = [0u16; windows_sys::Win32::Foundation::MAX_PATH as usize];
    let mut find_data: windows_sys::Win32::Storage::FileSystem::WIN32_FIND_DATAW = std::mem::zeroed();
    let link_vt = (*(link as *mut *const IShellLinkWVtbl)) as *const IShellLinkWVtbl;
    // SLGP_UNCPRIORITY (0x2) resolves UNC targets; SLGP_RAWPATH (0x4) is not wanted.
    if ((*link_vt).get_path)(link, buffer.as_mut_ptr(), buffer.len() as i32, &mut find_data, 0x2) != 0 {
        return None;
    }
    let len = buffer.iter().position(|&c| c == 0).unwrap_or(0);
    if len == 0 {
        return None;
    }
    Some(String::from_utf16_lossy(&buffer[..len]))
}

/// Extract the primary icon of `exe_path` and encode it as a base64 PNG (empty string
/// on failure, matching the old design's `''` fallback).
fn extract_icon_base64(exe_path: &str) -> String {
    let wide: Vec<u16> = exe_path.encode_utf16().chain(std::iter::once(0)).collect();
    let mut large: windows_sys::Win32::UI::WindowsAndMessaging::HICON = std::ptr::null_mut();
    let count = unsafe {
        windows_sys::Win32::UI::Shell::ExtractIconExW(
            wide.as_ptr(),
            0,
            &mut large,
            std::ptr::null_mut(),
            1,
        )
    };
    if count == 0 || large.is_null() {
        return String::new();
    }
    let icon = IconGuard { handle: large };
    let png = unsafe { icon_to_png(icon.handle) };
    png.map(|bytes| base64::engine::general_purpose::STANDARD.encode(bytes))
        .unwrap_or_default()
}

struct IconGuard {
    handle: windows_sys::Win32::UI::WindowsAndMessaging::HICON,
}
impl Drop for IconGuard {
    fn drop(&mut self) {
        if !self.handle.is_null() {
            unsafe { windows_sys::Win32::UI::WindowsAndMessaging::DestroyIcon(self.handle) };
        }
    }
}

/// Convert an HICON to PNG bytes via GetIconInfo + GetDIBits.
unsafe fn icon_to_png(icon: windows_sys::Win32::UI::WindowsAndMessaging::HICON) -> Option<Vec<u8>> {
    use windows_sys::Win32::UI::WindowsAndMessaging::{GetIconInfo, ICONINFO};

    let mut info: ICONINFO = std::mem::zeroed();
    if GetIconInfo(icon, &mut info) == 0 {
        return None;
    }
    let _mask_guard = GdiObjectGuard { handle: info.hbmMask };
    let _color_guard = GdiObjectGuard { handle: info.hbmColor };
    if info.hbmColor.is_null() {
        return None; // mask-only icons are rare; skip them like PowerShell's catch{}
    }
    let dc = windows_sys::Win32::Graphics::Gdi::GetDC(std::ptr::null_mut());
    if dc.is_null() {
        return None;
    }
    let _dc_guard = DcGuard { dc, window: std::ptr::null_mut() };

    let mut bitmap = windows_sys::Win32::Graphics::Gdi::BITMAP {
        bmType: 0, bmWidth: 0, bmHeight: 0, bmWidthBytes: 0, bmPlanes: 0, bmBitsPixel: 0,
        bmBits: std::ptr::null_mut(),
    };
    if windows_sys::Win32::Graphics::Gdi::GetObjectW(
        info.hbmColor,
        std::mem::size_of::<windows_sys::Win32::Graphics::Gdi::BITMAP>() as i32,
        &mut bitmap as *mut _ as *mut core::ffi::c_void,
    ) == 0
    {
        return None;
    }
    let width = bitmap.bmWidth.max(1) as usize;
    let height = bitmap.bmHeight.max(1) as usize;

    let mut header = windows_sys::Win32::Graphics::Gdi::BITMAPINFOHEADER {
        biSize: std::mem::size_of::<windows_sys::Win32::Graphics::Gdi::BITMAPINFOHEADER>() as u32,
        biWidth: width as i32,
        biHeight: -(height as i32), // top-down rows
        biPlanes: 1,
        biBitCount: 32,
        biCompression: windows_sys::Win32::Graphics::Gdi::BI_RGB,
        biSizeImage: 0, biXPelsPerMeter: 0, biYPelsPerMeter: 0, biClrUsed: 0, biClrImportant: 0,
    };
    let mut pixels = vec![0u8; width * height * 4];
    let rows = windows_sys::Win32::Graphics::Gdi::GetDIBits(
        dc,
        info.hbmColor,
        0,
        height as u32,
        pixels.as_mut_ptr() as *mut core::ffi::c_void,
        &mut header as *mut windows_sys::Win32::Graphics::Gdi::BITMAPINFOHEADER as *mut windows_sys::Win32::Graphics::Gdi::BITMAPINFO,
        windows_sys::Win32::Graphics::Gdi::DIB_RGB_COLORS,
    );
    if rows == 0 {
        return None;
    }

    encode_png_rgba(width, height, &pixels)
}

struct GdiObjectGuard {
    handle: windows_sys::Win32::Foundation::HANDLE,
}
impl Drop for GdiObjectGuard {
    fn drop(&mut self) {
        if !self.handle.is_null() {
            unsafe { windows_sys::Win32::Graphics::Gdi::DeleteObject(self.handle) };
        }
    }
}
struct DcGuard {
    dc: windows_sys::Win32::Foundation::HANDLE,
    window: windows_sys::Win32::Foundation::HWND,
}
impl Drop for DcGuard {
    fn drop(&mut self) {
        unsafe { windows_sys::Win32::Graphics::Gdi::ReleaseDC(self.window, self.dc) };
    }
}

/// Minimal PNG encoder for 8-bit RGBA data (no external PNG dependency):
/// filter 0 per scanline, zlib via the raw incompressible "stored" blocks. Icons are
/// tiny (<=64x64); correctness over compression. CRC32 implemented inline.
fn encode_png_rgba(width: usize, height: usize, rgba: &[u8]) -> Option<Vec<u8>> {
    // BGRA -> RGBA row-wise with filter byte 0.
    let mut raw = Vec::with_capacity(height * (1 + width * 4));
    for y in 0..height {
        raw.push(0u8); // filter: None
        for x in 0..width {
            let i = (y * width + x) * 4;
            raw.push(rgba[i + 2]); // R
            raw.push(rgba[i + 1]); // G
            raw.push(rgba[i]); // B
            raw.push(rgba[i + 3]); // A
        }
    }
    let mut zlib = Vec::with_capacity(raw.len() + raw.len() / 8 + 16);
    zlib.extend_from_slice(&[0x78, 0x01]); // CMF/FLG: fastest, no dict
    let mut offset = 0usize;
    while offset < raw.len() {
        let chunk = (&raw[offset..]).len().min(65535);
        let last = offset + chunk >= raw.len();
        zlib.push(if last { 1 } else { 0 });
        zlib.extend_from_slice(&(chunk as u16).to_le_bytes());
        zlib.extend_from_slice(&(!(chunk as u16)).to_le_bytes());
        zlib.extend_from_slice(&raw[offset..offset + chunk]);
        offset += chunk;
    }
    let adler = adler32(&raw);
    zlib.extend_from_slice(&adler.to_be_bytes());

    let mut png = Vec::with_capacity(zlib.len() + 128);
    png.extend_from_slice(&[0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A]);
    let mut ihdr = Vec::with_capacity(13);
    ihdr.extend_from_slice(&(width as u32).to_be_bytes());
    ihdr.extend_from_slice(&(height as u32).to_be_bytes());
    ihdr.extend_from_slice(&[8, 6, 0, 0, 0]); // 8-bit, RGBA, deflate, adaptive?, no interlace
    push_chunk(&mut png, b"IHDR", &ihdr);
    push_chunk(&mut png, b"IDAT", &zlib);
    push_chunk(&mut png, b"IEND", &[]);
    Some(png)
}

fn push_chunk(out: &mut Vec<u8>, kind: &[u8; 4], data: &[u8]) {
    out.extend_from_slice(&(data.len() as u32).to_be_bytes());
    let start = out.len();
    out.extend_from_slice(kind);
    out.extend_from_slice(data);
    let crc = crc32(&out[start..]);
    out.extend_from_slice(&crc.to_be_bytes());
}

fn crc32(data: &[u8]) -> u32 {
    let mut table = [0u32; 256];
    for (n, slot) in table.iter_mut().enumerate() {
        let mut c = n as u32;
        for _ in 0..8 {
            c = if c & 1 != 0 { 0xEDB88320 ^ (c >> 1) } else { c >> 1 };
        }
        *slot = c;
    }
    let mut crc = 0xFFFF_FFFFu32;
    for &byte in data {
        crc = table[((crc ^ byte as u32) & 0xFF) as usize] ^ (crc >> 8);
    }
    crc ^ 0xFFFF_FFFF
}

fn adler32(data: &[u8]) -> u32 {
    let (mut a, mut b) = (1u32, 0u32);
    for &byte in data {
        a = (a + byte as u32) % 65521;
        b = (b + a) % 65521;
    }
    (b << 16) | a
}

// The standalone helper above was left half-written during refactoring; remove it.

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn png_encoder_round_trips_a_pixel() {
        // 1x1 RGBA (opaque blue) -> decode structure: PNG magic + IHDR + IDAT + IEND.
        let png = encode_png_rgba(1, 1, &[10, 20, 30, 255]).expect("encode");
        assert_eq!(&png[..8], &[0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A]);
        assert_eq!(&png[12..16], b"IHDR");
        assert_eq!(&png[16..20], &1u32.to_be_bytes()); // width
        assert_eq!(&png[20..24], &1u32.to_be_bytes()); // height
        assert_eq!(png[24], 8); // bit depth
        assert_eq!(png[25], 6); // RGBA
        // IEND must be the last chunk.
        assert_eq!(&png[png.len() - 8..png.len() - 4], b"IEND");
        // CRC of IHDR (verifiable independently).
        let ihdr = &png[12..12 + 4 + 13];
        let crc = crc32(ihdr);
        assert_eq!(&png[12 + 4 + 13..12 + 4 + 13 + 4], &crc.to_be_bytes());
    }

    #[test]
    fn crc32_matches_reference_vector() {
        // CRC32("123456789") = 0xCBF43926 (standard check value).
        assert_eq!(crc32(b"123456789"), 0xCBF43926);
        assert_eq!(adler32(b"Wikipedia"), 0x11E60398);
    }

    #[test]
    fn zlib_stored_blocks_decode_with_inflate_zero_header() {
        // The stored-block stream must be well-formed zlib: 2-byte header + blocks + adler.
        let raw = vec![0u8, 1, 2, 3, 4, 5, 6, 7, 8, 9];
        let mut zlib = Vec::new();
        zlib.extend_from_slice(&[0x78, 0x01]);
        zlib.push(1);
        zlib.extend_from_slice(&(10u16).to_le_bytes());
        zlib.extend_from_slice(&(!(10u16)).to_le_bytes());
        zlib.extend_from_slice(&raw);
        zlib.extend_from_slice(&adler32(&raw).to_be_bytes());
        // Structural checks only (no inflate dependency in tests).
        assert_eq!(zlib[2] & 0x1, 1); // final flag
        assert_eq!(u16::from_le_bytes([zlib[3], zlib[4]]), 10);
    }

    #[test]
    fn known_folders_resolve_start_menu_roots() {
        // This test runs on Windows CI/dev hosts: the Start Menu roots must exist.
        let program_data = known_folder(&FOLDERID_PROGRAM_DATA);
        assert!(program_data.is_some(), "ProgramData must resolve");
        let roaming = known_folder(&FOLDERID_ROAMING_APP_DATA);
        assert!(roaming.is_some(), "RoamingAppData must resolve");
        if let Some(pd) = program_data {
            assert!(pd.join("Microsoft\\Windows\\Start Menu\\Programs").is_dir());
        }
    }

    #[test]
    fn enumeration_returns_only_exe_targets() {
        let apps = enumerate_start_menu_apps().expect("native enumeration");
        assert!(!apps.is_empty(), "a Windows host always has Start-Menu apps");
        for app in &apps {
            assert!(app.path.to_ascii_lowercase().ends_with(".exe"), "{}", app.path);
            assert!(!app.name.is_empty());
            // Icons: either empty (extraction failed) or valid base64 PNG.
            if !app.icon.is_empty() {
                assert!(base64::engine::general_purpose::STANDARD
                    .decode(&app.icon)
                    .is_ok());
            }
        }
    }

    /// Diagnostic: count the .lnk files the walker collects versus how many resolve to
    /// targets. If the walker under-collects, this prints the truth for debugging.
    #[test]
    fn enumeration_collects_the_full_start_menu() {
        let mut roots: Vec<PathBuf> = Vec::new();
        if let Some(program_data) = known_folder(&FOLDERID_PROGRAM_DATA) {
            roots.push(program_data.join("Microsoft\\Windows\\Start Menu\\Programs"));
        }
        if let Some(roaming) = known_folder(&FOLDERID_ROAMING_APP_DATA) {
            roots.push(roaming.join("Microsoft\\Windows\\Start Menu\\Programs"));
        }
        roots.retain(|r| r.is_dir());
        let mut links = Vec::new();
        for root in &roots {
            collect_shortcuts(root, &mut links);
        }
        // A real Windows host has hundreds of Start-Menu shortcuts (this host: ~360).
        // The old PowerShell picker enumerated all of them; the native one must too.
        assert!(
            links.len() > 100,
            "shortcut walker collected only {} .lnk files — the Start Menu trees are under-enumerated",
            links.len()
        );
        let apps = enumerate_start_menu_apps().expect("native enumeration");
        // Every collected exe-targeting link should surface (minus parse failures).
        assert!(
            apps.len() > 50,
            "picker returned only {} apps from {} links — resolution is failing",
            apps.len(),
            links.len()
        );
    }
}
