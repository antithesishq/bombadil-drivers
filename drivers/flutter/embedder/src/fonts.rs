//! Gives the app fonts when the system has none where the engine looks
//! (`/usr/share/fonts/`): the app runs from an overlay of its assets
//! directory whose `FontManifest.json` also lists the extra fonts.

use std::fs;
use std::io;
use std::os::unix::fs::symlink;
use std::path::{Path, PathBuf};

use serde_json::{json, Value};

/// The directory in the overlay that links to the extra fonts.
const LINK: &str = "bombadil_fonts";

/// Creates `overlay`, a copy of `assets` made of symbolic links whose font
/// manifest adds every `.ttf` and `.otf` file in each subdirectory of
/// `fonts`, as a family named after the subdirectory. Families the app
/// declares itself keep the app's fonts.
pub fn overlay(assets: &Path, fonts: &Path, overlay: &Path) -> io::Result<()> {
    fs::create_dir_all(overlay)?;
    let assets = fs::canonicalize(assets)?;
    for entry in fs::read_dir(&assets)? {
        let name = entry?.file_name();
        if name != "FontManifest.json" {
            symlink(assets.join(&name), overlay.join(&name))?;
        }
    }
    symlink(fs::canonicalize(fonts)?, overlay.join(LINK))?;

    let mut manifest: Vec<Value> = match fs::read(assets.join("FontManifest.json")) {
        Ok(bytes) => serde_json::from_slice(&bytes)
            .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?,
        Err(e) if e.kind() == io::ErrorKind::NotFound => Vec::new(),
        Err(e) => return Err(e),
    };
    let mut families: Vec<PathBuf> = fs::read_dir(fonts)?
        .map(|entry| entry.map(|e| e.path()))
        .collect::<io::Result<_>>()?;
    families.sort();
    for dir in families.into_iter().filter(|p| p.is_dir()) {
        let family = dir.file_name().unwrap().to_string_lossy().into_owned();
        if manifest.iter().any(|f| f["family"] == family.as_str()) {
            continue;
        }
        let mut files: Vec<String> = fs::read_dir(&dir)?
            .filter_map(|entry| {
                let name = entry.ok()?.file_name().to_string_lossy().into_owned();
                let lower = name.to_lowercase();
                (lower.ends_with(".ttf") || lower.ends_with(".otf")).then_some(name)
            })
            .collect();
        files.sort();
        let fonts: Vec<Value> = files
            .iter()
            .map(|file| json!({"asset": format!("{LINK}/{family}/{file}")}))
            .collect();
        if !fonts.is_empty() {
            manifest.push(json!({"family": family, "fonts": fonts}));
        }
    }
    fs::write(
        overlay.join("FontManifest.json"),
        serde_json::to_vec(&manifest)?,
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn adds_fonts_to_the_manifest() {
        let root = std::env::temp_dir().join(format!("bombadil-fonts-test-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        let (assets, fonts, out) = (root.join("assets"), root.join("fonts"), root.join("out"));
        fs::create_dir_all(&assets).unwrap();
        fs::write(assets.join("kernel_blob.bin"), "kernel").unwrap();
        fs::write(
            assets.join("FontManifest.json"),
            r#"[{"family":"Roboto","fonts":[{"asset":"fonts/app.ttf"}]}]"#,
        )
        .unwrap();
        for (family, file) in [
            ("Roboto", "R.ttf"),
            ("DejaVu Sans", "D.ttf"),
            ("DejaVu Sans", "notes.txt"),
        ] {
            fs::create_dir_all(fonts.join(family)).unwrap();
            fs::write(fonts.join(family).join(file), "font").unwrap();
        }

        overlay(&assets, &fonts, &out).unwrap();

        assert_eq!(
            fs::read_to_string(out.join("kernel_blob.bin")).unwrap(),
            "kernel"
        );
        assert_eq!(
            fs::read_to_string(out.join("bombadil_fonts/DejaVu Sans/D.ttf")).unwrap(),
            "font"
        );
        let manifest: Value =
            serde_json::from_slice(&fs::read(out.join("FontManifest.json")).unwrap()).unwrap();
        assert_eq!(
            manifest,
            json!([
                {"family": "Roboto", "fonts": [{"asset": "fonts/app.ttf"}]},
                {"family": "DejaVu Sans", "fonts": [{"asset": "bombadil_fonts/DejaVu Sans/D.ttf"}]},
            ])
        );
        // The original manifest is untouched.
        assert!(fs::read_to_string(assets.join("FontManifest.json"))
            .unwrap()
            .contains("app.ttf"));
        fs::remove_dir_all(&root).unwrap();
    }
}
