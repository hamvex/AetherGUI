use futures_util::StreamExt;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::path::PathBuf;
use tauri::{AppHandle, Emitter, Manager};
use tokio::io::AsyncWriteExt;
use tokio::sync::Mutex;

const RELEASE_API: &str = "https://api.github.com/repos/hamvex/AetherGUI/releases?per_page=30";
const DOWNLOAD_PREFIX: &str = "https://github.com/hamvex/AetherGUI/releases/download/";
const CHECKSUM_ASSET: &str = "SHA256SUMS.txt";

#[derive(Default)]
pub struct UpdateState {
    downloaded: Mutex<Option<DownloadedUpdate>>,
}

struct DownloadedUpdate {
    path: PathBuf,
    sha256: String,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateInfo {
    pub current_version: String,
    pub latest_version: String,
    pub release_notes: String,
    pub download_url: String,
    pub sha256: String,
    pub available: bool,
}

#[derive(Deserialize)]
struct GithubRelease {
    tag_name: String,
    body: Option<String>,
    assets: Vec<GithubAsset>,
}

#[derive(Deserialize)]
struct GithubAsset {
    name: String,
    browser_download_url: String,
    digest: Option<String>,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct UpdateProgress {
    downloaded_bytes: u64,
    total_bytes: Option<u64>,
    percent: Option<u8>,
    status: &'static str,
    speed_bytes_per_second: Option<u64>,
}

fn client() -> Result<reqwest::Client, String> {
    reqwest::Client::builder()
        .user_agent("Aethon-Update/1.2.1")
        .timeout(std::time::Duration::from_secs(60))
        .build()
        .map_err(|error| error.to_string())
}

fn normalized_version(value: &str) -> &str {
    value.trim().trim_start_matches(['v', 'V'])
}

fn version_parts(value: &str) -> Option<Vec<u64>> {
    normalized_version(value)
        .split('.')
        .map(str::parse::<u64>)
        .collect::<Result<Vec<_>, _>>()
        .ok()
}

pub(crate) fn is_newer_version(latest: &str, current: &str) -> bool {
    match (version_parts(latest), version_parts(current)) {
        (Some(mut latest), Some(mut current)) => {
            let length = latest.len().max(current.len());
            latest.resize(length, 0);
            current.resize(length, 0);
            latest > current
        }
        _ => false,
    }
}

async fn checksum_from_manifest(
    http: &reqwest::Client,
    release: &GithubRelease,
    installer_name: &str,
) -> Result<String, String> {
    let asset = release
        .assets
        .iter()
        .find(|asset| asset.name == CHECKSUM_ASSET)
        .ok_or("The release does not provide a SHA-256 checksum")?;
    if !asset.browser_download_url.starts_with(DOWNLOAD_PREFIX) {
        return Err("The checksum URL is not an official Aethon release URL".into());
    }
    let text = http
        .get(&asset.browser_download_url)
        .send()
        .await
        .map_err(|error| error.to_string())?
        .error_for_status()
        .map_err(|error| error.to_string())?
        .text()
        .await
        .map_err(|error| error.to_string())?;
    text.lines()
        .filter_map(|line| {
            let mut fields = line.split_whitespace();
            Some((fields.next()?, fields.next()?.trim_start_matches('*')))
        })
        .find_map(|(hash, name)| {
            name.eq_ignore_ascii_case(installer_name)
                .then(|| hash.to_lowercase())
        })
        .filter(|hash| hash.len() == 64 && hash.chars().all(|c| c.is_ascii_hexdigit()))
        .ok_or_else(|| format!("No SHA-256 checksum was found for {installer_name}"))
}

async fn latest_update() -> Result<UpdateInfo, String> {
    let http = client()?;
    let releases = http
        .get(RELEASE_API)
        .send()
        .await
        .map_err(|error| format!("Update check failed: {error}"))?
        .error_for_status()
        .map_err(|error| format!("Update check failed: {error}"))?
        .json::<Vec<GithubRelease>>()
        .await
        .map_err(|error| format!("Invalid update metadata: {error}"))?;
    let current = "1.2.1";
    let (release, latest, installer_name, asset) = releases
        .iter()
        .filter_map(|release| {
            let latest = normalized_version(&release.tag_name).to_string();
            if !is_newer_version(&latest, current) { return None; }
            let installer_name = format!("Aethon_{latest}_x64-setup.exe");
            let asset = release.assets.iter().find(|asset| asset.name == installer_name)?;
            Some((release, latest, installer_name, asset))
        })
        .next()
        .or_else(|| releases.iter().filter_map(|release| {
            let latest = normalized_version(&release.tag_name).to_string();
            let installer_name = format!("Aethon_{latest}_x64-setup.exe");
            let asset = release.assets.iter().find(|asset| asset.name == installer_name)?;
            Some((release, latest, installer_name, asset))
        }).next())
        .ok_or("No compatible Windows update package is available")?;
    if !asset.browser_download_url.starts_with(DOWNLOAD_PREFIX) {
        return Err("The installer URL is not an official Aethon release URL".into());
    }
    let sha256 = match asset
        .digest
        .as_deref()
        .and_then(|value| value.strip_prefix("sha256:"))
    {
        Some(hash) if hash.len() == 64 && hash.chars().all(|c| c.is_ascii_hexdigit()) => {
            hash.to_lowercase()
        }
        _ => checksum_from_manifest(&http, &release, &installer_name).await?,
    };
    Ok(UpdateInfo {
        available: is_newer_version(&latest, current),
        current_version: current.into(),
        latest_version: latest,
        release_notes: release.body.clone().unwrap_or_default(),
        download_url: asset.browser_download_url.clone(),
        sha256,
    })
}

#[tauri::command]
pub async fn check_for_update(app: AppHandle) -> Result<UpdateInfo, String> {
    let info = latest_update().await?;
    if info.available {
        if let Some(tray) = app.tray_by_id("main") {
            let _ = tray.set_tooltip(Some(format!(
                "Aethon {} update available",
                info.latest_version
            )));
        }
    }
    Ok(info)
}

#[tauri::command]
pub async fn download_update(
    app: AppHandle,
    state: tauri::State<'_, UpdateState>,
) -> Result<String, String> {
    let info = latest_update().await?;
    if !info.available {
        return Err("Aethon is already up to date".into());
    }
    let directory = app
        .path()
        .app_cache_dir()
        .map_err(|error| error.to_string())?
        .join("updates");
    tokio::fs::create_dir_all(&directory)
        .await
        .map_err(|error| error.to_string())?;
    let filename = format!("Aethon_{}_x64-setup.exe", info.latest_version);
    let final_path = directory.join(&filename);
    let partial_path = directory.join(format!("{filename}.part"));
    let http = client()?;
    let response = http
        .get(&info.download_url)
        .send()
        .await
        .map_err(|error| format!("Update download failed: {error}"))?
        .error_for_status()
        .map_err(|error| format!("Update download failed: {error}"))?;
    let total = response.content_length();
    let mut stream = response.bytes_stream();
    let mut file = tokio::fs::File::create(&partial_path)
        .await
        .map_err(|error| error.to_string())?;
    let mut hasher = Sha256::new();
    let mut downloaded = 0u64;
    let started = std::time::Instant::now();
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|error| format!("Update download failed: {error}"))?;
        file.write_all(&chunk)
            .await
            .map_err(|error| error.to_string())?;
        hasher.update(&chunk);
        downloaded += chunk.len() as u64;
        let percent =
            total.map(|size| ((downloaded.saturating_mul(100) / size.max(1)).min(100)) as u8);
        let _ = app.emit(
            "update-progress",
            UpdateProgress {
                downloaded_bytes: downloaded,
                total_bytes: total,
                percent,
                status: "downloading",
                speed_bytes_per_second: Some(downloaded / started.elapsed().as_secs().max(1)),
            },
        );
    }
    file.flush().await.map_err(|error| error.to_string())?;
    let actual = format!("{:x}", hasher.finalize());
    if actual != info.sha256 {
        let _ = tokio::fs::remove_file(&partial_path).await;
        return Err("The downloaded installer failed SHA-256 verification".into());
    }
    if tokio::fs::try_exists(&final_path).await.unwrap_or(false) {
        tokio::fs::remove_file(&final_path)
            .await
            .map_err(|error| error.to_string())?;
    }
    tokio::fs::rename(&partial_path, &final_path)
        .await
        .map_err(|error| error.to_string())?;
    *state.downloaded.lock().await = Some(DownloadedUpdate {
        path: final_path,
        sha256: info.sha256,
    });
    let _ = app.emit(
        "update-progress",
        UpdateProgress {
            downloaded_bytes: downloaded,
            total_bytes: total,
            percent: Some(100),
            status: "ready",
            speed_bytes_per_second: Some(downloaded / started.elapsed().as_secs().max(1)),
        },
    );
    Ok(info.latest_version)
}

async fn file_sha256(path: &PathBuf) -> Result<String, String> {
    let bytes = tokio::fs::read(path)
        .await
        .map_err(|error| error.to_string())?;
    Ok(format!("{:x}", Sha256::digest(bytes)))
}

#[tauri::command]
pub async fn install_update(
    app: AppHandle,
    state: tauri::State<'_, UpdateState>,
) -> Result<(), String> {
    let downloaded = state.downloaded.lock().await;
    let update = downloaded
        .as_ref()
        .ok_or("No verified update is ready to install")?;
    if file_sha256(&update.path).await? != update.sha256 {
        return Err("The cached installer failed SHA-256 verification".into());
    }
    std::process::Command::new(&update.path)
        .spawn()
        .map_err(|error| format!("Could not start the installer: {error}"))?;
    app.exit(0);
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn semantic_versions_compare_numerically() {
        assert!(is_newer_version("1.11.1", "1.11.0"));
        assert!(is_newer_version("v2.0.0", "1.99.99"));
        assert!(!is_newer_version("1.11.1", "1.11.1"));
        assert!(!is_newer_version("invalid", "1.11.1"));
    }
}
